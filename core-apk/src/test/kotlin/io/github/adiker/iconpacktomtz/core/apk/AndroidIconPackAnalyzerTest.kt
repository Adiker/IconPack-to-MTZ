package io.github.adiker.iconpacktomtz.core.apk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.DrawableKind
import io.github.adiker.iconpacktomtz.core.model.DrawableSource
import io.github.adiker.iconpacktomtz.core.model.IconRenderer
import io.github.adiker.iconpacktomtz.core.model.RenderCache
import io.github.adiker.iconpacktomtz.core.model.RenderCacheKey
import io.github.adiker.iconpacktomtz.core.model.RenderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 37])
class AndroidIconPackAnalyzerTest {
    @Test
    fun analyzesPlainAndCompiledAppfilterFixtures() = runBlocking {
        val analyzer = AndroidIconPackAnalyzer(ApplicationProvider.getApplicationContext())
        ZipFile(fixture("fixturePlainApk")).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val mapping = checkNotNull(zip.getEntry("assets/appfilter.xml")) {
                "Fixture entries: $names"
            }
            check("assets/appfilter.xml" in names) { "Enumerated fixture entries: $names" }
            val parsed = zip.getInputStream(mapping).use { input ->
                AppFilterParser().parse(input.readBytes(), ArchiveLimits())
            }
            check(parsed.entries.size == 7) { "Direct parse: $parsed" }
        }

        val plain = analyzer.analyze(
            fixture("fixturePlainApk"),
            ArchiveLimits(),
            sampleConfig = RenderConfig(),
        )
        val compiled = analyzer.analyze(
            fixture("fixtureCompiledApk"),
            ArchiveLimits(),
            sampleConfig = RenderConfig(),
        )

        assertThat(plain.entries).hasSize(7)
        assertThat(plain.mappingLocation).isEqualTo("assets/appfilter.xml")
        assertThat(compiled.entries).hasSize(7)
        assertThat(compiled.mappingLocation).isEqualTo("res/xml/appfilter.xml")
        assertThat(plain.drawables["fixture_svg"]?.kind).isEqualTo(DrawableKind.SVG_ASSET)
        assertThat(plain.drawables["fixture_blue"]?.kind).isEqualTo(DrawableKind.VECTOR_RESOURCE)
        assertThat(plain.drawables["fixture_adaptive"]?.kind)
            .isEqualTo(DrawableKind.ADAPTIVE_RESOURCE)
        assertThat(plain.drawables["fixture_raster"]?.kind)
            .isEqualTo(DrawableKind.RASTER_RESOURCE)
    }

    @Test
    fun analysisRendersAtMost64RepresentativeIconsAndReusesTheirCache() = runBlocking {
        val cacheDirectory = Files.createTempDirectory("analysis-sample-cache").toFile()
        try {
            val renderCalls = AtomicInteger()
            val renderer = object : IconRenderer {
                override suspend fun render(
                    apkFile: File,
                    source: DrawableSource,
                    config: RenderConfig,
                    limits: ArchiveLimits,
                ): ByteArray {
                    renderCalls.incrementAndGet()
                    return ByteArray(120) { 1 }
                }
            }
            val cache = object : RenderCache {
                override suspend fun get(cacheKey: String): File? =
                    cacheDirectory.resolve("$cacheKey.png").takeIf(File::isFile)

                override suspend fun put(cacheKey: String, pngBytes: ByteArray): File =
                    cacheDirectory.resolve("$cacheKey.png").apply { writeBytes(pngBytes) }

                override suspend fun touch(cacheKey: String) = Unit
                override suspend fun clear() = Unit
                override suspend fun trimToSize(maxBytes: Long) = Unit
            }
            val config = RenderConfig()
            val analyzer = AndroidIconPackAnalyzer(
                context = ApplicationProvider.getApplicationContext(),
                sampleRenderer = renderer,
                sampleCache = cache,
            )

            val first = analyzer.analyze(
                fixture("fixturePlainApk"),
                ArchiveLimits(),
                sampleConfig = config,
            )
            val uniqueSampleKeys = first.drawables.values
                .map { RenderCacheKey.create(it, config) }
                .distinct()
                .size
                .coerceAtMost(64)
            assertThat(renderCalls.get()).isEqualTo(uniqueSampleKeys)
            assertThat(first.estimatedMtzBytes)
                .isEqualTo(16_384L + first.predictedOutputFiles * 120L)

            analyzer.analyze(
                fixture("fixturePlainApk"),
                ArchiveLimits(),
                sampleConfig = config,
            )
            assertThat(renderCalls.get()).isEqualTo(uniqueSampleKeys)
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    private fun fixture(property: String): File =
        File(requireNotNull(System.getProperty(property))).also {
            check(it.isFile) { "Fixture APK was not built: $it" }
        }
}
