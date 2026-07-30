package io.github.adiker.iconpacktomtz.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import io.github.adiker.iconpacktomtz.core.archive.copyLimitedTo
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class PreparedSafInput internal constructor(
    val file: File,
    private val descriptor: ParcelFileDescriptor?,
    private val temporaryFile: File?,
) : Closeable {
    override fun close() {
        runCatching { descriptor?.close() }
        temporaryFile?.delete()
    }
}

data class PublishedArtifacts(
    val mtzUri: Uri,
    val jsonReportUri: Uri,
    val textReportUri: Uri,
)

data class PublishedReports(
    val jsonReportUri: Uri,
    val textReportUri: Uri,
)

class SafFileAccess(
    private val context: Context,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun persistReadPermission(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun persistTreePermission(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun displayName(uri: Uri): String =
        DocumentFile.fromSingleUri(context, uri)?.name
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.take(256)
            ?: "selected-file"

    suspend fun prepareInput(
        uri: Uri,
        workspace: File,
        fileName: String,
        limits: ArchiveLimits,
        requireReadableApkManifest: Boolean = false,
    ): PreparedSafInput = withContext(Dispatchers.IO) {
        val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "r")) {
            "Cannot open the selected document."
        }
        if (isSeekable(descriptor)) {
            val procFile = File("/proc/self/fd/${descriptor.fd}")
            val manifestReadable = !requireReadableApkManifest ||
                context.packageManager.getPackageArchiveInfo(procFile.absolutePath, 0) != null
            if (procFile.canRead() && manifestReadable) {
                return@withContext PreparedSafInput(procFile, descriptor, null)
            }
        }
        descriptor.close()
        check(workspace.exists() || workspace.mkdirs()) { "Cannot create private workspace." }
        val temporary = File(workspace, fileName)
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read the selected document." }
                FileOutputStream(temporary).use { output ->
                    input.copyLimitedTo(output, limits.maxCompressedBytes)
                    output.fd.sync()
                }
            }
            PreparedSafInput(temporary, null, temporary)
        } catch (exception: Exception) {
            temporary.delete()
            throw exception
        }
    }

    suspend fun publish(
        treeUri: Uri,
        baseName: String,
        mtzFile: File,
        jsonReportFile: File,
        textReportFile: File,
    ): PublishedArtifacts = withContext(Dispatchers.IO) {
        val tree = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) {
            "Cannot access the selected output folder."
        }
        require(tree.canWrite()) { "The selected output folder is not writable." }
        val safeName = sanitizeBaseName(baseName)
        val created = mutableListOf<DocumentFile>()
        try {
            val mtz = createUnique(tree, "application/zip", "$safeName.mtz").also(created::add)
            copyToDocument(mtzFile, mtz)
            val json = createUnique(
                tree,
                "application/json",
                "$safeName-report.json",
            ).also(created::add)
            copyToDocument(jsonReportFile, json)
            val text = createUnique(
                tree,
                "text/plain",
                "$safeName-report.txt",
            ).also(created::add)
            copyToDocument(textReportFile, text)
            PublishedArtifacts(
                requireNotNull(mtz.uri),
                requireNotNull(json.uri),
                requireNotNull(text.uri),
            )
        } catch (exception: Exception) {
            created.forEach { runCatching { it.delete() } }
            throw exception
        }
    }

    suspend fun publishReports(
        treeUri: Uri,
        baseName: String,
        jsonReportFile: File,
        textReportFile: File,
    ): PublishedReports = withContext(Dispatchers.IO) {
        val tree = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) {
            "Cannot access the selected output folder."
        }
        require(tree.canWrite()) { "The selected output folder is not writable." }
        val safeName = sanitizeBaseName(baseName)
        val created = mutableListOf<DocumentFile>()
        try {
            val json = createUnique(
                tree,
                "application/json",
                "$safeName-report.json",
            ).also(created::add)
            copyToDocument(jsonReportFile, json)
            val text = createUnique(
                tree,
                "text/plain",
                "$safeName-report.txt",
            ).also(created::add)
            copyToDocument(textReportFile, text)
            PublishedReports(json.uri, text.uri)
        } catch (exception: Exception) {
            created.forEach { runCatching { it.delete() } }
            throw exception
        }
    }

    private fun isSeekable(descriptor: ParcelFileDescriptor): Boolean =
        try {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            true
        } catch (_: Exception) {
            false
        }

    private fun copyToDocument(source: File, destination: DocumentFile) {
        resolver.openOutputStream(destination.uri, "w").use { output ->
            requireNotNull(output) { "Cannot create an output document." }
            source.inputStream().buffered().use { input -> input.copyTo(output) }
        }
    }

    private fun createUnique(tree: DocumentFile, mime: String, requestedName: String): DocumentFile {
        val extension = requestedName.substringAfterLast('.', "")
        val stem = requestedName.removeSuffix(if (extension.isEmpty()) "" else ".$extension")
        var candidate = requestedName
        var suffix = 1
        while (tree.findFile(candidate) != null && suffix <= 999) {
            candidate = "$stem-$suffix${if (extension.isEmpty()) "" else ".$extension"}"
            suffix++
        }
        if (suffix > 999) throw IOException("Cannot choose a unique output name.")
        return requireNotNull(tree.createFile(mime, candidate)) {
            "Document provider refused to create $candidate."
        }
    }

    private fun sanitizeBaseName(value: String): String {
        val sanitized = value
            .trim()
            .replace(Regex("""[^\p{L}\p{N}._ -]+"""), "_")
            .replace("..", "_")
            .trim('.', ' ')
            .take(96)
        return sanitized.ifBlank { "hyperos-icons" }
    }
}
