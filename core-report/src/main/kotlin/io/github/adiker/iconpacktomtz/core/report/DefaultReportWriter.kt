package io.github.adiker.iconpacktomtz.core.report

import com.google.gson.GsonBuilder
import io.github.adiker.iconpacktomtz.core.model.ConversionIssue
import io.github.adiker.iconpacktomtz.core.model.ConversionReportV1
import io.github.adiker.iconpacktomtz.core.model.ReportWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DefaultReportWriter : ReportWriter {
    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    override suspend fun writeJson(report: ConversionReportV1, destination: File) {
        withContext(Dispatchers.IO) {
            destination.parentFile?.let { check(it.exists() || it.mkdirs()) }
            destination.bufferedWriter(Charsets.UTF_8).use { writer ->
                gson.toJson(report.sanitized(), writer)
            }
        }
    }

    override suspend fun writeText(report: ConversionReportV1, destination: File) {
        withContext(Dispatchers.IO) {
            destination.parentFile?.let { check(it.exists() || it.mkdirs()) }
            val safe = report.sanitized()
            destination.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("IconPack to HyperOS MTZ — conversion report")
                writer.appendLine("Schema: ${safe.schemaVersion}")
                writer.appendLine("Operation: ${safe.operationId}")
                writer.appendLine("Created: ${safe.createdAt}")
                writer.appendLine("Source: ${safe.sourceDisplayName}")
                writer.appendLine("Status: ${safe.status}")
                writer.appendLine("Mode: ${safe.mode}")
                writer.appendLine("Naming: ${safe.namingStrategy}")
                writer.appendLine()
                writer.appendLine("Entries: ${safe.mappingEntries}")
                writer.appendLine("Packages: ${safe.uniquePackages}")
                writer.appendLine("Drawables: ${safe.uniqueDrawables}")
                writer.appendLine("Generated files: ${safe.generatedFiles}")
                writer.appendLine("Skipped entries: ${safe.skippedEntries}")
                writer.appendLine("Output bytes: ${safe.outputBytes}")
                writer.appendLine("Output SHA-256: ${safe.outputSha256.orEmpty()}")
                writer.appendLine()
                writer.appendLine("Deduplication")
                writer.appendLine("  Requested: ${safe.deduplication.requestedDrawables}")
                writer.appendLine("  Rendered: ${safe.deduplication.renderedDrawables}")
                writer.appendLine("  Source duplicates: ${safe.deduplication.sourceHashDuplicates}")
                writer.appendLine("  PNG duplicates: ${safe.deduplication.pngHashDuplicates}")
                writer.appendLine("  Cache hits: ${safe.deduplication.cacheHits}")
                writer.appendLine("  Cache misses: ${safe.deduplication.cacheMisses}")
                writer.appendLine("  Aliases: ${safe.deduplication.aliasCount}")
                writer.appendLine()
                writer.appendLine("Stage timings")
                safe.stageTimings.forEach { timing ->
                    writer.appendLine("  ${timing.stage}: ${timing.durationMillis} ms")
                }
                writer.appendLine()
                writer.appendLine("Issues (${safe.issues.size})")
                safe.issues.forEach { issue ->
                    writer.append("  [${issue.severity}] ${issue.code}")
                    issue.subject?.let { writer.append(" — $it") }
                    issue.detail?.let { writer.append(": $it") }
                    writer.appendLine()
                }
                writer.appendLine()
                writer.appendLine("Compatibility")
                writer.appendLine(safe.compatibilityNote)
            }
        }
    }

    private fun ConversionReportV1.sanitized(): ConversionReportV1 = copy(
        sourceDisplayName = sourceDisplayName.substringAfterLast('/').substringAfterLast('\\').take(256),
        issues = issues.map { issue -> issue.sanitized() },
    )

    private fun ConversionIssue.sanitized(): ConversionIssue = copy(
        subject = subject?.redacted(),
        detail = detail?.redacted(),
    )

    private fun String.redacted(): String {
        var value = take(1_024)
        value = URI_PATTERN.replace(value, "[private-uri]")
        value = ABSOLUTE_PATH_PATTERN.replace(value, "[private-path]")
        return value
    }

    private companion object {
        val URI_PATTERN = Regex("""\b(?:content|file)://[^\s]+""", RegexOption.IGNORE_CASE)
        val ABSOLUTE_PATH_PATTERN = Regex("""(?<![\w.])/(?:[^/\s]+/)+[^/\s]*""")
    }
}
