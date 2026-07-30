package io.github.adiker.iconpacktomtz.core.renderer

import io.github.adiker.iconpacktomtz.core.model.RenderCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DiskLruRenderCache(
    private val directory: File,
) : RenderCache {
    private val mutex = Mutex()

    override suspend fun get(cacheKey: String): File? = withContext(Dispatchers.IO) {
        requireValidKey(cacheKey)
        mutex.withLock {
            val file = fileFor(cacheKey)
            if (!file.isFile) return@withLock null
            file.setLastModified(System.currentTimeMillis())
            file
        }
    }

    override suspend fun put(cacheKey: String, pngBytes: ByteArray): File =
        withContext(Dispatchers.IO) {
            requireValidKey(cacheKey)
            mutex.withLock {
                check(directory.exists() || directory.mkdirs()) { "Cannot create render cache." }
                val target = fileFor(cacheKey)
                if (!target.isFile) {
                    val temporary = File(directory, "$cacheKey.${System.nanoTime()}.tmp")
                    try {
                        FileOutputStream(temporary).use { output ->
                            output.write(pngBytes)
                            output.fd.sync()
                        }
                        check(temporary.renameTo(target)) { "Cannot commit cache entry." }
                    } finally {
                        temporary.delete()
                    }
                }
                target.setLastModified(System.currentTimeMillis())
                target
            }
        }

    override suspend fun touch(cacheKey: String) {
        withContext(Dispatchers.IO) {
            requireValidKey(cacheKey)
            mutex.withLock { fileFor(cacheKey).takeIf(File::isFile)?.setLastModified(System.currentTimeMillis()) }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                directory.listFiles()
                    ?.filter { it.isFile && (it.extension == "png" || it.extension == "tmp") }
                    ?.forEach(File::delete)
            }
        }
    }

    override suspend fun trimToSize(maxBytes: Long) {
        require(maxBytes > 0)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val files = directory.listFiles()
                    ?.filter { it.isFile && it.extension == "png" }
                    ?.sortedBy { it.lastModified() }
                    .orEmpty()
                    .toMutableList()
                var total = files.sumOf { it.length() }
                files.forEach { file ->
                    if (total <= maxBytes) return@forEach
                    val size = file.length()
                    if (file.delete()) total -= size
                }
            }
        }
    }

    private fun fileFor(cacheKey: String) = File(directory, "$cacheKey.png")

    private fun requireValidKey(cacheKey: String) {
        require(cacheKey.length == 64 && cacheKey.all { it in "0123456789abcdef" }) {
            "Cache keys must be lowercase SHA-256 values."
        }
    }
}
