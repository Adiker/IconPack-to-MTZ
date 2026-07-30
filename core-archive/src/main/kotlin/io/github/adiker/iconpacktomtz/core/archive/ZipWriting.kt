package io.github.adiker.iconpacktomtz.core.archive

import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry

fun ZipArchiveOutputStream.configureDeterministic() {
    setUseZip64(Zip64Mode.Never)
    setLevel(6)
    setEncoding("UTF-8")
}

fun ZipArchiveOutputStream.putStoredBytes(name: String, bytes: ByteArray) {
    SafeArchivePath.requireSafeEntryName(name)
    putStoredStream(name, bytes.size.toLong(), crc32(bytes), ByteArrayInputStream(bytes))
}

fun ZipArchiveOutputStream.putStoredFile(name: String, file: File) {
    SafeArchivePath.requireSafeEntryName(name)
    val crc = CRC32()
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            crc.update(buffer, 0, read)
        }
    }
    file.inputStream().buffered().use { input ->
        putStoredStream(name, file.length(), crc.value, input)
    }
}

fun ZipArchiveOutputStream.putDeflatedBytes(name: String, bytes: ByteArray) {
    SafeArchivePath.requireSafeEntryName(name)
    val entry = ZipArchiveEntry(name).apply {
        method = ZipEntry.DEFLATED
        time = DETERMINISTIC_ZIP_TIME
    }
    CommonsCompressCompat.putArchiveEntry(this, entry)
    write(bytes)
    closeArchiveEntry()
}

fun ZipArchiveOutputStream.copyRawEntriesFrom(
    source: ZipFile,
    skipExactNames: Set<String>,
): Int {
    var copied = 0
    val entries = source.entries.asSequence().toList().sortedBy { it.name }
    entries.forEach { original ->
        SafeArchivePath.requireSafeEntryName(original.name)
        if (original.name in skipExactNames) return@forEach
        val copy = ZipArchiveEntry(original)
        source.getRawInputStream(original).use { raw ->
            addRawArchiveEntry(copy, raw)
        }
        copied++
    }
    return copied
}

private fun ZipArchiveOutputStream.putStoredStream(
    name: String,
    size: Long,
    crc: Long,
    input: InputStream,
) {
    val entry = ZipArchiveEntry(name).apply {
        method = ZipEntry.STORED
        this.size = size
        compressedSize = size
        this.crc = crc
        time = DETERMINISTIC_ZIP_TIME
    }
    CommonsCompressCompat.putArchiveEntry(this, entry)
    input.copyTo(this)
    closeArchiveEntry()
}

private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

private const val DETERMINISTIC_ZIP_TIME = 315532800000L
