package io.github.adiker.iconpacktomtz.core.report

import com.google.common.truth.Truth.assertThat
import io.github.adiker.iconpacktomtz.core.model.ConversionIssue
import io.github.adiker.iconpacktomtz.core.model.ConversionMode
import io.github.adiker.iconpacktomtz.core.model.ConversionReportV1
import io.github.adiker.iconpacktomtz.core.model.ConversionStatus
import io.github.adiker.iconpacktomtz.core.model.DeduplicationStats
import io.github.adiker.iconpacktomtz.core.model.IssueCode
import io.github.adiker.iconpacktomtz.core.model.IssueSeverity
import io.github.adiker.iconpacktomtz.core.model.NamingStrategy
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files

class DefaultReportWriterTest {
    @Test
    fun exportedReportsRedactUrisAndAbsolutePaths() = runBlocking {
        val directory = Files.createTempDirectory("report").toFile()
        try {
            val report = ConversionReportV1(
                operationId = "test",
                sourceDisplayName = "/private/source.apk",
                mode = ConversionMode.FULL,
                namingStrategy = NamingStrategy.OPTIMIZED,
                status = ConversionStatus.FAILED,
                mappingEntries = 0,
                uniquePackages = 0,
                uniqueDrawables = 0,
                generatedFiles = 0,
                skippedEntries = 0,
                outputBytes = 0,
                outputSha256 = null,
                deduplication = DeduplicationStats(),
                stageTimings = emptyList(),
                issues = listOf(
                    ConversionIssue(
                        IssueCode.IO_ERROR,
                        IssueSeverity.ERROR,
                        "content://provider/private",
                        "Failed at /data/user/0/app/cache/file",
                    ),
                ),
            )
            val json = directory.resolve("report.json")
            val text = directory.resolve("report.txt")
            DefaultReportWriter().writeJson(report, json)
            DefaultReportWriter().writeText(report, text)

            listOf(json.readText(), text.readText()).forEach { output ->
                assertThat(output).doesNotContain("content://")
                assertThat(output).doesNotContain("/data/user")
                assertThat(output).contains("source.apk")
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
