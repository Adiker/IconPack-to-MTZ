package io.github.adiker.iconpacktomtz.core.archive

import com.google.common.truth.Truth.assertThat
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.IssueCode
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveSecurityTest {
    @Test
    fun rejectsZipSlipNames() {
        val error = runCatching { SafeArchivePath.requireSafeEntryName("../escape") }.exceptionOrNull()
        assertThat(error).isInstanceOf(UnsafeArchiveException::class.java)
        assertThat((error as UnsafeArchiveException).issue.code).isEqualTo(IssueCode.ZIP_SLIP)
    }

    @Test
    fun detectsCompressionBombRatio() {
        val file = Files.createTempFile("bomb", ".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("large.txt"))
                zip.write(ByteArray(1_000_000))
                zip.closeEntry()
            }
            val inspection = ArchiveValidator().inspect(
                file,
                ArchiveLimits(maxCompressionRatio = 10),
            )
            assertThat(inspection.issues.map { it.code })
                .contains(IssueCode.ARCHIVE_COMPRESSION_RATIO)
        } finally {
            file.delete()
        }
    }

    @Test
    fun safeRelativeName_isAccepted() {
        assertThat(SafeArchivePath.requireSafeEntryName("res/drawable/a.png"))
            .isEqualTo("res/drawable/a.png")
    }
}
