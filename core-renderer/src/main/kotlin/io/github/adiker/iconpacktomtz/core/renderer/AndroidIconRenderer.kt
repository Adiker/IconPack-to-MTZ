package io.github.adiker.iconpacktomtz.core.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.caverock.androidsvg.SVG
import io.github.adiker.iconpacktomtz.core.apk.ApkResourceSession
import io.github.adiker.iconpacktomtz.core.apk.CompiledResourceResolver
import io.github.adiker.iconpacktomtz.core.archive.UnsafeArchiveException
import io.github.adiker.iconpacktomtz.core.archive.copyLimitedTo
import io.github.adiker.iconpacktomtz.core.archive.hasForbiddenXmlDeclaration
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.ConversionIssue
import io.github.adiker.iconpacktomtz.core.model.DrawableKind
import io.github.adiker.iconpacktomtz.core.model.DrawableSource
import io.github.adiker.iconpacktomtz.core.model.IconRenderer
import io.github.adiker.iconpacktomtz.core.model.IssueCode
import io.github.adiker.iconpacktomtz.core.model.IssueSeverity
import io.github.adiker.iconpacktomtz.core.model.RenderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

class AndroidIconRenderer(
    private val context: Context,
) : IconRenderer {
    override suspend fun render(
        apkFile: File,
        source: DrawableSource,
        config: RenderConfig,
        limits: ArchiveLimits,
    ): ByteArray = withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        try {
            val bitmap = Bitmap.createBitmap(config.sizePx, config.sizePx, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.TRANSPARENT)
                when (source.kind) {
                    DrawableKind.SVG_ASSET -> renderSvg(apkFile, source, canvas, config, limits)
                    DrawableKind.RASTER_ASSET -> renderRasterAsset(apkFile, source, canvas, config, limits)
                    DrawableKind.VECTOR_RESOURCE,
                    DrawableKind.ADAPTIVE_RESOURCE,
                    DrawableKind.RASTER_RESOURCE,
                    -> renderAndroidResource(apkFile, source, canvas, config, limits)
                    DrawableKind.UNKNOWN -> throw IOException("Unsupported drawable kind.")
                }
                coroutineContext.ensureActive()
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "PNG encoding failed."
                    }
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        } catch (error: OutOfMemoryError) {
            throw IOException("Icon rendering exceeded the available memory.", error)
        }
    }

    private fun renderSvg(
        apkFile: File,
        source: DrawableSource,
        canvas: Canvas,
        config: RenderConfig,
        limits: ArchiveLimits,
    ) {
        val bytes = readArchiveEntry(
            apkFile,
            requireNotNull(source.archivePath),
            minOf(limits.maxEntryBytes, MAX_SVG_BYTES),
        )
        if (bytes.hasForbiddenXmlDeclaration() || hasExternalSvgReference(bytes)) {
            throw UnsafeArchiveException(
                ConversionIssue(
                    IssueCode.XML_FORBIDDEN_DECLARATION,
                    IssueSeverity.ERROR,
                    source.drawableName,
                    "SVG contains a forbidden declaration or external reference.",
                ),
            )
        }
        val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
        val width = svg.documentWidth.takeIf { it > 0f } ?: config.sizePx.toFloat()
        val height = svg.documentHeight.takeIf { it > 0f } ?: config.sizePx.toFloat()
        svg.renderToCanvas(canvas, fittedRect(width, height, config))
    }

    private fun renderRasterAsset(
        apkFile: File,
        source: DrawableSource,
        canvas: Canvas,
        config: RenderConfig,
        limits: ArchiveLimits,
    ) {
        val bytes = readArchiveEntry(apkFile, requireNotNull(source.archivePath), limits.maxEntryBytes)
        val bitmap = decodeBitmap(bytes, config.sizePx, limits, source.drawableName)
        try {
            val destination = fittedRect(bitmap.width.toFloat(), bitmap.height.toFloat(), config)
            canvas.drawBitmap(bitmap, null, destination, null)
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderAndroidResource(
        apkFile: File,
        source: DrawableSource,
        canvas: Canvas,
        config: RenderConfig,
        limits: ArchiveLimits,
    ) {
        source.width?.let { width ->
            source.height?.let { height -> requirePixelLimit(width, height, limits, source.drawableName) }
        }
        if (source.kind == DrawableKind.RASTER_RESOURCE && source.resourceId == null) {
            renderRasterAsset(apkFile, source, canvas, config, limits)
            return
        }
        val renderedNatively = source.resourceId?.let { resourceId ->
            runCatching {
                ApkResourceSession.open(
                    context,
                    apkFile,
                    indexCompiledResources = false,
                ).use { session ->
                    val drawable = if (source.densityDpi > 0) {
                        session.resources.getDrawableForDensity(resourceId, source.densityDpi, null)
                    } else {
                        session.resources.getDrawable(resourceId, null)
                    }
                    drawDrawable(requireNotNull(drawable), canvas, config)
                }
                true
            }.getOrDefault(false)
        } ?: false
        if (!renderedNatively) {
            val destination = fittedRect(config.sizePx.toFloat(), config.sizePx.toFloat(), config)
            CompiledResourceResolver.open(apkFile).use { resolver ->
                CompiledDrawableRenderer(
                    resolver,
                    limits,
                ) { path -> readArchiveEntry(apkFile, path, limits.maxEntryBytes) }
                    .render(requireNotNull(source.archivePath), canvas, destination)
            }
        }
    }

    private fun drawDrawable(drawable: Drawable, canvas: Canvas, config: RenderConfig) {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: config.sizePx
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: config.sizePx
        val destination = fittedRect(width.toFloat(), height.toFloat(), config)
        drawable.bounds = Rect(
            destination.left.roundToInt(),
            destination.top.roundToInt(),
            destination.right.roundToInt(),
            destination.bottom.roundToInt(),
        )
        drawable.draw(canvas)
    }

    private fun decodeBitmap(
        bytes: ByteArray,
        targetSize: Int,
        limits: ArchiveLimits,
        subject: String,
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Unsupported or corrupt raster icon.")
        }
        requirePixelLimit(bounds.outWidth, bounds.outHeight, limits, subject)
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > targetSize * 2) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
            "Raster icon decoding failed."
        }
    }

    private fun requirePixelLimit(
        width: Int,
        height: Int,
        limits: ArchiveLimits,
        subject: String,
    ) {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= 0 || pixels > limits.maxBitmapPixels) {
            throw UnsafeArchiveException(
                ConversionIssue(
                    IssueCode.SOURCE_BITMAP_TOO_LARGE,
                    IssueSeverity.ERROR,
                    subject,
                    "Source bitmap exceeds the configured pixel limit.",
                ),
            )
        }
    }

    private fun fittedRect(width: Float, height: Float, config: RenderConfig): RectF {
        val margin = config.sizePx * config.marginFraction
        val available = config.sizePx - 2f * margin
        val scale = minOf(available / width, available / height)
        val targetWidth = width * scale
        val targetHeight = height * scale
        val left = (config.sizePx - targetWidth) / 2f
        val top = (config.sizePx - targetHeight) / 2f
        return RectF(left, top, left + targetWidth, top + targetHeight)
    }

    private fun readArchiveEntry(
        apkFile: File,
        entryName: String,
        maxBytes: Long,
    ): ByteArray = ZipFile(apkFile).use { zip ->
        val entry = requireNotNull(zip.getEntry(entryName)) { "Drawable asset is missing." }
        if (entry.size < 0 || entry.size > maxBytes) {
            throw IOException("Drawable asset exceeds its configured limit.")
        }
        ByteArrayOutputStream(entry.size.coerceAtMost(64 * 1024).toInt()).use { output ->
            zip.getInputStream(entry).use { input -> input.copyLimitedTo(output, maxBytes) }
            output.toByteArray()
        }
    }

    private fun hasExternalSvgReference(bytes: ByteArray): Boolean {
        val text = bytes.toString(Charsets.UTF_8).lowercase()
        return listOf(
            "href=\"http:",
            "href='http:",
            "href=\"https:",
            "href='https:",
            "href=\"file:",
            "href='file:",
            "xlink:href=\"http",
            "xlink:href='http",
        ).any(text::contains)
    }

    private companion object {
        const val MAX_SVG_BYTES = 32L * 1024 * 1024
    }
}
