package io.github.adiker.iconpacktomtz.core.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.adiker.iconpacktomtz.core.apk.AndroidIconPackAnalyzer
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.DrawableKind
import io.github.adiker.iconpacktomtz.core.model.DrawableSource
import io.github.adiker.iconpacktomtz.core.model.RenderConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 37])
class AndroidIconRendererTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val renderer = AndroidIconRenderer(context)

    @Test
    fun rendersVectorAdaptiveSvgAndPngWithAlphaAndMargin() = runBlocking {
        val apk = fixture()
        val analysis = AndroidIconPackAnalyzer(context).analyze(apk, ArchiveLimits())
        listOf("fixture_blue", "fixture_adaptive", "fixture_svg", "fixture_raster").forEach { name ->
            val png = renderer.render(
                apk,
                analysis.drawables.getValue(name),
                RenderConfig(sizePx = 168, marginFraction = 0.08f),
                ArchiveLimits(),
            )
            val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
            assertThat(bitmap.width).isEqualTo(168)
            assertThat(bitmap.height).isEqualTo(168)
            assertThat(Color.alpha(bitmap.getPixel(0, 0))).isEqualTo(0)
            bitmap.recycle()
        }
    }

    @Test
    fun rendersDifferentResourcesConcurrently() = runBlocking {
        val apk = fixture()
        val analysis = AndroidIconPackAnalyzer(context).analyze(apk, ArchiveLimits())
        coroutineScope {
            listOf("fixture_blue", "fixture_adaptive", "fixture_svg", "fixture_raster")
                .map { name ->
                    async(Dispatchers.Default) {
                        renderer.render(
                            apk,
                            analysis.drawables.getValue(name),
                            RenderConfig(),
                            ArchiveLimits(),
                        )
                    }
                }
                .awaitAll()
        }.forEach { png ->
            assertThat(BitmapFactory.decodeByteArray(png, 0, png.size)).isNotNull()
        }
    }

    @Test
    fun rendersJpegAndWebpAssets() = runBlocking {
        val archive = Files.createTempFile("raster-assets", ".apk").toFile()
        try {
            val jpeg = rasterBytes(Bitmap.CompressFormat.JPEG)
            val webp = rasterBytes(Bitmap.CompressFormat.WEBP_LOSSLESS)
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/icon.jpg"))
                zip.write(jpeg)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/icon.webp"))
                zip.write(webp)
                zip.closeEntry()
            }
            listOf("assets/icon.jpg", "assets/icon.webp").forEach { path ->
                val output = renderer.render(
                    archive,
                    DrawableSource(
                        drawableName = path.substringAfterLast('/').substringBefore('.'),
                        kind = DrawableKind.RASTER_ASSET,
                        archivePath = path,
                    ),
                    RenderConfig(),
                    ArchiveLimits(),
                )
                assertThat(BitmapFactory.decodeByteArray(output, 0, output.size)).isNotNull()
            }
        } finally {
            archive.delete()
        }
    }

    private fun rasterBytes(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        return try {
            ByteArrayOutputStream().use {
                assertThat(bitmap.compress(format, 90, it)).isTrue()
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun fixture(): File =
        File(requireNotNull(System.getProperty("fixturePlainApk"))).also {
            check(it.isFile) { "Fixture APK was not built: $it" }
        }
}
