package io.github.adiker.iconpacktomtz.feature.converter

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import io.github.adiker.iconpacktomtz.core.apk.AndroidIconPackAnalyzer
import io.github.adiker.iconpacktomtz.core.model.ConversionMode
import io.github.adiker.iconpacktomtz.core.model.ConversionStatus
import io.github.adiker.iconpacktomtz.core.model.ConversionWorkRequest
import io.github.adiker.iconpacktomtz.core.model.ConverterSettings
import io.github.adiker.iconpacktomtz.core.model.NamingStrategy
import io.github.adiker.iconpacktomtz.core.model.RenderConfig
import io.github.adiker.iconpacktomtz.core.model.ThemeMetadata
import io.github.adiker.iconpacktomtz.core.mtz.DefaultMtzBuilder
import io.github.adiker.iconpacktomtz.core.renderer.AndroidIconRenderer
import io.github.adiker.iconpacktomtz.core.renderer.DiskLruRenderCache
import io.github.adiker.iconpacktomtz.core.report.DefaultReportWriter
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 37])
class ConversionEngineE2eTest {
    @Test
    fun fixtureApkToStandaloneAndBaseMtz() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = Files.createTempDirectory("conversion-e2e").toFile()
        try {
            val cache = DiskLruRenderCache(root.resolve("cache"))
            fun engine() = DefaultConversionEngine(
                AndroidIconPackAnalyzer(context),
                AndroidIconRenderer(context),
                cache,
                DefaultMtzBuilder(),
                DefaultReportWriter(),
            )
            val standalone = engine().convert(
                request(
                    operation = "standalone",
                    workspace = root.resolve("standalone"),
                    base = null,
                ),
            )
            assertThat(standalone.artifacts.report.status).isEqualTo(ConversionStatus.SUCCEEDED)
            assertWithMessage(standalone.artifacts.report.issues.joinToString())
                .that(standalone.artifacts.report.generatedFiles)
                .isEqualTo(11)
            assertOuterAndInnerStructure(standalone.artifacts.mtzFile)

            val base = root.resolve("base.mtz")
            ZipOutputStream(base.outputStream()).use { zip ->
                listOf(
                    "description.xml" to "<MIUI-Theme><title>Base fixture</title></MIUI-Theme>",
                    "icons" to "old icons",
                    "lockscreen/advance/manifest.xml" to "<Lockscreen />",
                ).forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
            val based = engine().convert(
                request(
                    operation = "based",
                    workspace = root.resolve("based"),
                    base = base,
                ),
            )
            @Suppress("DEPRECATION")
            ZipFile(based.artifacts.mtzFile).use { zip ->
                assertThat(
                    zip.getInputStream(zip.getEntry("description.xml")).readBytes().decodeToString(),
                ).contains("Base fixture")
                assertThat(
                    zip.getInputStream(zip.getEntry("lockscreen/advance/manifest.xml"))
                        .readBytes()
                        .decodeToString(),
                ).isEqualTo("<Lockscreen />")
                assertThat(zip.getInputStream(zip.getEntry("icons")).readBytes().decodeToString())
                    .doesNotContain("old icons")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun request(operation: String, workspace: File, base: File?) =
        ConversionWorkRequest(
            operationId = operation,
            sourceDisplayName = "cc0-fixture.apk",
            apkFile = File(requireNotNull(System.getProperty("fixturePlainApk"))),
            baseMtzFile = base,
            workspaceDirectory = workspace,
            settings = ConverterSettings(
                mode = ConversionMode.FULL,
                namingStrategy = NamingStrategy.FULL_COMPATIBILITY,
                render = RenderConfig(),
                workerCount = 2,
            ),
            metadata = ThemeMetadata(
                title = "Fixture HyperOS Icons",
                author = "Tests",
                description = "CC0 fixture",
            ),
        )

    private fun assertOuterAndInnerStructure(mtz: File) {
        val temporaryIcons = File(mtz.parentFile, "readback-icons")
        @Suppress("DEPRECATION")
        ZipFile(mtz).use { outer ->
            assertThat(outer.getEntry("description.xml")).isNotNull()
            assertThat(outer.getEntry("preview/preview_icons_0.jpg")).isNotNull()
            outer.getInputStream(outer.getEntry("icons")).use { input ->
                temporaryIcons.outputStream().use { output -> input.copyTo(output) }
            }
        }
        @Suppress("DEPRECATION")
        ZipFile(temporaryIcons).use { inner ->
            val names = inner.entries.asSequence().map { it.name }.toList()
            assertThat(names).contains("res/drawable-xxhdpi/com.example.multi.MainActivity.png")
            assertThat(names).contains("res/drawable-xxhdpi/com.example.multi.SecondActivity.png")
            names.forEach { name ->
                val bytes = inner.getInputStream(inner.getEntry(name)).use { it.readNBytes(8) }
                assertThat(bytes).isEqualTo(
                    byteArrayOf(
                        0x89.toByte(),
                        0x50,
                        0x4e,
                        0x47,
                        0x0d,
                        0x0a,
                        0x1a,
                        0x0a,
                    ),
                )
            }
        }
        temporaryIcons.delete()
    }
}
