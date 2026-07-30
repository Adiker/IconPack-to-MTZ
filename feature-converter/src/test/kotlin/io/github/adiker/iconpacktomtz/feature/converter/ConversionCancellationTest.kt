package io.github.adiker.iconpacktomtz.feature.converter

import com.google.common.truth.Truth.assertThat
import io.github.adiker.iconpacktomtz.core.model.AppComponent
import io.github.adiker.iconpacktomtz.core.model.AppFilterEntry
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.ConversionStage
import io.github.adiker.iconpacktomtz.core.model.ConversionWorkRequest
import io.github.adiker.iconpacktomtz.core.model.ConverterSettings
import io.github.adiker.iconpacktomtz.core.model.DrawableKind
import io.github.adiker.iconpacktomtz.core.model.DrawableSource
import io.github.adiker.iconpacktomtz.core.model.IconPackAnalysis
import io.github.adiker.iconpacktomtz.core.model.IconPackAnalyzer
import io.github.adiker.iconpacktomtz.core.model.IconPackMetadata
import io.github.adiker.iconpacktomtz.core.model.IconRenderer
import io.github.adiker.iconpacktomtz.core.model.MtzBuildRequest
import io.github.adiker.iconpacktomtz.core.model.MtzBuildResult
import io.github.adiker.iconpacktomtz.core.model.MtzBuilder
import io.github.adiker.iconpacktomtz.core.model.RenderCache
import io.github.adiker.iconpacktomtz.core.model.RenderConfig
import io.github.adiker.iconpacktomtz.core.model.ThemeMetadata
import io.github.adiker.iconpacktomtz.core.report.DefaultReportWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConversionCancellationTest {
    @Test
    fun cancelStopsActiveRenderingCoroutine() = runBlocking {
        val root = Files.createTempDirectory("cancel-conversion").toFile()
        try {
            val source = root.resolve("source.apk").apply { writeBytes(byteArrayOf(1)) }
            val cache = root.resolve("cache").apply { mkdirs() }
            val engine = DefaultConversionEngine(
                analyzer = fakeAnalyzer(),
                renderer = object : IconRenderer {
                    override suspend fun render(
                        apkFile: File,
                        source: DrawableSource,
                        config: RenderConfig,
                        limits: ArchiveLimits,
                    ): ByteArray {
                        delay(60_000)
                        return byteArrayOf()
                    }
                },
                renderCache = inMemoryFileCache(cache),
                mtzBuilder = object : MtzBuilder {
                    override suspend fun build(request: MtzBuildRequest): MtzBuildResult =
                        error("MTZ build must not be reached after cancellation.")
                },
                reportWriter = DefaultReportWriter(),
            )
            coroutineScope {
                val conversion = async {
                    engine.convert(
                        ConversionWorkRequest(
                            operationId = "cancel",
                            sourceDisplayName = "fixture.apk",
                            apkFile = source,
                            baseMtzFile = null,
                            workspaceDirectory = root.resolve("workspace"),
                            settings = ConverterSettings(workerCount = 1),
                            metadata = ThemeMetadata(),
                        ),
                    )
                }
                engine.progress.first { it.stage == ConversionStage.RENDERING }
                engine.cancel()
                val cancelled = runCatching { conversion.await() }.exceptionOrNull()
                assertThat(cancelled).isInstanceOf(CancellationException::class.java)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun fakeAnalyzer() = object : IconPackAnalyzer {
        override suspend fun analyze(
            apkFile: File,
            limits: ArchiveLimits,
            installedPackages: Set<String>?,
            sampleConfig: RenderConfig?,
        ): IconPackAnalysis {
            val entry = AppFilterEntry(
                AppComponent("com.example", "com.example.MainActivity", ".MainActivity"),
                "icon",
                0,
            )
            return IconPackAnalysis(
                IconPackMetadata("fixture", "Fixture", "1", 1),
                "assets/appfilter.xml",
                listOf(entry),
                mapOf(
                    "icon" to DrawableSource(
                        "icon",
                        DrawableKind.SVG_ASSET,
                        sourceSha256 = "a".repeat(64),
                    ),
                ),
                1,
                1,
                1,
                1_024,
                emptyList(),
            )
        }
    }

    private fun inMemoryFileCache(directory: File) = object : RenderCache {
        override suspend fun get(cacheKey: String): File? = null
        override suspend fun put(cacheKey: String, pngBytes: ByteArray): File =
            directory.resolve("$cacheKey.png").apply { writeBytes(pngBytes) }
        override suspend fun touch(cacheKey: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun trimToSize(maxBytes: Long) = Unit
    }
}
