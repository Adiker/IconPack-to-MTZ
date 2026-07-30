package io.github.adiker.iconpacktomtz.core.archive

import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.ConversionIssue
import io.github.adiker.iconpacktomtz.core.model.IssueCode
import io.github.adiker.iconpacktomtz.core.model.IssueSeverity
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.Path

data class ArchiveInspection(
    val entries: Int,
    val compressedBytes: Long,
    val expandedBytes: Long,
    val issues: List<ConversionIssue>,
) {
    val isValid: Boolean get() = issues.none { it.severity == IssueSeverity.ERROR }
}

class UnsafeArchiveException(
    val issue: ConversionIssue,
) : IOException(issue.detail ?: issue.code.name)

object SafeArchivePath {
    fun requireSafeEntryName(name: String): String {
        if (name.isBlank() || name.indexOf('\u0000') >= 0 || name.indexOf('\\') >= 0) {
            throw unsafe(name, "Empty, NUL-containing, or backslash path.")
        }
        if (name.startsWith('/') || WINDOWS_DRIVE.matches(name)) {
            throw unsafe(name, "Absolute archive path.")
        }
        val normalized: Path = Path(name).normalize()
        if (normalized.isAbsolute || normalized.startsWith("..")) {
            throw unsafe(name, "Archive entry escapes its root.")
        }
        val normalizedName = normalized.toString().replace(File.separatorChar, '/')
        if (normalizedName != name.removeSuffix("/")) {
            throw unsafe(name, "Archive path is not canonical.")
        }
        return name
    }

    private fun unsafe(name: String, detail: String) = UnsafeArchiveException(
        ConversionIssue(IssueCode.ZIP_SLIP, IssueSeverity.ERROR, redact(name), detail),
    )

    private fun redact(value: String): String = value.take(256)

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[/\\\\].*")
}

class ArchiveValidator {
    fun inspect(file: File, limits: ArchiveLimits): ArchiveInspection {
        val issues = mutableListOf<ConversionIssue>()
        if (!file.isFile || file.length() <= 0L) {
            return ArchiveInspection(
                0,
                0,
                0,
                listOf(
                    ConversionIssue(
                        IssueCode.INVALID_ARCHIVE,
                        IssueSeverity.ERROR,
                        file.name,
                        "The selected file is empty or unavailable.",
                    ),
                ),
            )
        }
        if (file.length() > limits.maxCompressedBytes) {
            issues += ConversionIssue(
                IssueCode.ARCHIVE_COMPRESSED_LIMIT,
                IssueSeverity.ERROR,
                file.name,
                "Compressed archive exceeds the configured limit.",
            )
        }

        var count = 0
        var compressed = 0L
        var expanded = 0L
        try {
            @Suppress("DEPRECATION")
            ZipFile(file).use { zip ->
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    count++
                    if (count > limits.maxEntries) {
                        issues += error(
                            IssueCode.ARCHIVE_TOO_MANY_ENTRIES,
                            entry.name,
                            "Archive contains more than ${limits.maxEntries} entries.",
                        )
                        break
                    }
                    try {
                        SafeArchivePath.requireSafeEntryName(entry.name)
                    } catch (error: UnsafeArchiveException) {
                        issues += error.issue
                    }
                    if (entry.size < 0 || entry.compressedSize < 0) {
                        issues += error(
                            IssueCode.INVALID_ARCHIVE,
                            entry.name,
                            "Entry has unknown size in the central directory.",
                        )
                        continue
                    }
                    compressed = saturatingAdd(compressed, entry.compressedSize)
                    expanded = saturatingAdd(expanded, entry.size)
                    if (entry.size > limits.maxEntryBytes) {
                        issues += error(
                            IssueCode.ARCHIVE_ENTRY_LIMIT,
                            entry.name,
                            "Expanded entry exceeds the configured per-entry limit.",
                        )
                    }
                    val ratio = when {
                        entry.size == 0L -> 0.0
                        entry.compressedSize == 0L -> Double.POSITIVE_INFINITY
                        else -> entry.size.toDouble() / entry.compressedSize.toDouble()
                    }
                    if (ratio > limits.maxCompressionRatio) {
                        issues += error(
                            IssueCode.ARCHIVE_COMPRESSION_RATIO,
                            entry.name,
                            "Entry compression ratio exceeds ${limits.maxCompressionRatio}:1.",
                        )
                    }
                }
            }
        } catch (exception: Exception) {
            issues += error(
                IssueCode.INVALID_ARCHIVE,
                file.name,
                "The file is not a readable ZIP archive: ${exception.javaClass.simpleName}",
            )
        }

        if (compressed > limits.maxCompressedBytes) {
            issues += error(
                IssueCode.ARCHIVE_COMPRESSED_LIMIT,
                file.name,
                "Sum of compressed entry sizes exceeds the configured limit.",
            )
        }
        if (expanded > limits.maxExpandedBytes) {
            issues += error(
                IssueCode.ARCHIVE_EXPANDED_LIMIT,
                file.name,
                "Sum of expanded entry sizes exceeds the configured limit.",
            )
        }
        return ArchiveInspection(count, compressed, expanded, issues.distinct())
    }

    fun requireValid(file: File, limits: ArchiveLimits): ArchiveInspection {
        val result = inspect(file, limits)
        result.issues.firstOrNull { it.severity == IssueSeverity.ERROR }?.let {
            throw UnsafeArchiveException(it)
        }
        return result
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun error(code: IssueCode, subject: String?, detail: String) =
        ConversionIssue(code, IssueSeverity.ERROR, subject?.take(256), detail)
}

fun ZipFile.readEntryLimited(entry: ZipArchiveEntry, limit: Long): ByteArray {
    if (entry.size < 0 || entry.size > limit || limit > Int.MAX_VALUE) {
        throw UnsafeArchiveException(
            ConversionIssue(
                IssueCode.ARCHIVE_ENTRY_LIMIT,
                IssueSeverity.ERROR,
                entry.name.take(256),
                "Entry exceeds the permitted read size.",
            ),
        )
    }
    getInputStream(entry).use { input ->
        val expected = entry.size.coerceAtMost(64 * 1024).toInt()
        val output = ByteArrayOutputStream(expected)
        input.copyLimitedTo(output, limit)
        return output.toByteArray()
    }
}

fun InputStream.copyLimitedTo(output: java.io.OutputStream, maxBytes: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw UnsafeArchiveException(
                ConversionIssue(
                    IssueCode.ARCHIVE_ENTRY_LIMIT,
                    IssueSeverity.ERROR,
                    detail = "Stream expanded beyond its configured limit.",
                ),
            )
        }
        output.write(buffer, 0, read)
    }
    return total
}

fun ByteArray.hasForbiddenXmlDeclaration(): Boolean {
    val prefix = toString(StandardCharsets.UTF_8)
        .take(4096)
        .lowercase()
    return "<!doctype" in prefix || "<!entity" in prefix
}
