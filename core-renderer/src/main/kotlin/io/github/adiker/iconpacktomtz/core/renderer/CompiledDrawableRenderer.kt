package io.github.adiker.iconpacktomtz.core.renderer

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.core.graphics.PathParser
import io.github.adiker.iconpacktomtz.core.apk.CompiledResourceResolver
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.ConversionIssue
import io.github.adiker.iconpacktomtz.core.model.IssueCode
import io.github.adiker.iconpacktomtz.core.model.IssueSeverity
import io.github.adiker.iconpacktomtz.core.archive.UnsafeArchiveException
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

internal class CompiledDrawableRenderer(
    private val resolver: CompiledResourceResolver,
    private val limits: ArchiveLimits,
    private val readEntry: (String) -> ByteArray,
) {
    private var references = 0
    private val activePaths = linkedSetOf<String>()

    fun render(archivePath: String, canvas: Canvas, destination: RectF) {
        drawResource(archivePath, canvas, destination)
    }

    private fun drawResource(archivePath: String, canvas: Canvas, destination: RectF) {
        if (!activePaths.add(archivePath)) {
            throw unsafeLoop(archivePath, "Drawable resource references itself.")
        }
        references++
        if (references > limits.maxDrawableReferences) {
            throw unsafeLoop(archivePath, "Drawable reference limit was exceeded.")
        }
        try {
            if (archivePath.substringAfterLast('.', "").lowercase() != "xml") {
                drawRaster(archivePath, canvas, destination)
                return
            }
            val root = parse(resolver.decodeXml(archivePath))
            when (root.localName ?: root.tagName.substringAfter(':')) {
                "vector" -> drawVector(root, canvas, destination)
                "adaptive-icon" -> drawAdaptive(root, canvas, destination)
                else -> throw IOException("Unsupported compiled drawable XML root.")
            }
        } finally {
            activePaths.remove(archivePath)
        }
    }

    private fun drawRaster(archivePath: String, canvas: Canvas, destination: RectF) {
        val bytes = readEntry(archivePath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Referenced raster drawable is corrupt.")
        }
        val pixels = bounds.outWidth.toLong() * bounds.outHeight
        if (pixels > limits.maxBitmapPixels) {
            throw UnsafeArchiveException(
                ConversionIssue(
                    IssueCode.SOURCE_BITMAP_TOO_LARGE,
                    IssueSeverity.ERROR,
                    archivePath,
                    "Referenced bitmap exceeds the configured pixel limit.",
                ),
            )
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "Referenced raster drawable could not be decoded."
        }
        try {
            canvas.drawBitmap(bitmap, null, destination, null)
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawAdaptive(root: Element, canvas: Canvas, destination: RectF) {
        val clip = Path().apply {
            addOval(destination, Path.Direction.CW)
        }
        val save = canvas.save()
        canvas.clipPath(clip)
        try {
            childElements(root).forEach { layer ->
                if (layer.localName in setOf("background", "foreground", "monochrome")) {
                    val reference = layer.androidAttribute("drawable")
                    if (reference.isNotBlank()) {
                        drawReference(reference, canvas, destination)
                    }
                }
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun drawVector(root: Element, canvas: Canvas, destination: RectF) {
        val viewportWidth = root.androidFloat("viewportWidth", destination.width())
        val viewportHeight = root.androidFloat("viewportHeight", destination.height())
        if (viewportWidth <= 0f || viewportHeight <= 0f) {
            throw IOException("Vector drawable has an invalid viewport.")
        }
        val alpha = (root.androidFloat("alpha", 1f).coerceIn(0f, 1f) * 255).roundToInt()
        val layer = if (alpha < 255) canvas.saveLayerAlpha(destination, alpha) else canvas.save()
        canvas.translate(destination.left, destination.top)
        canvas.scale(destination.width() / viewportWidth, destination.height() / viewportHeight)
        try {
            drawVectorChildren(root, canvas)
        } finally {
            canvas.restoreToCount(layer)
        }
    }

    private fun drawVectorChildren(parent: Element, canvas: Canvas) {
        childElements(parent).forEach { element ->
            when (element.localName ?: element.tagName.substringAfter(':')) {
                "path" -> drawPath(element, canvas)
                "clip-path" -> {
                    val pathData = element.androidAttribute("pathData")
                    val path = PathParser.createPathFromPathData(pathData)
                        ?: throw IOException("Invalid vector clip path.")
                    canvas.clipPath(path)
                }
                "group" -> drawGroup(element, canvas)
                "aapt:attr", "attr", "gradient" ->
                    throw IOException("Gradient vector colors are not supported.")
            }
        }
    }

    private fun drawGroup(group: Element, canvas: Canvas) {
        val pivotX = group.androidFloat("pivotX", 0f)
        val pivotY = group.androidFloat("pivotY", 0f)
        val save = canvas.save()
        canvas.translate(
            pivotX + group.androidFloat("translateX", 0f),
            pivotY + group.androidFloat("translateY", 0f),
        )
        canvas.rotate(group.androidFloat("rotation", 0f))
        canvas.scale(group.androidFloat("scaleX", 1f), group.androidFloat("scaleY", 1f))
        canvas.translate(-pivotX, -pivotY)
        try {
            drawVectorChildren(group, canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun drawPath(element: Element, canvas: Canvas) {
        val data = element.androidAttribute("pathData")
        var path = PathParser.createPathFromPathData(data)
            ?: throw IOException("Invalid vector path data.")
        val trimStart = element.androidFloat("trimPathStart", 0f)
        val trimEnd = element.androidFloat("trimPathEnd", 1f)
        val trimOffset = element.androidFloat("trimPathOffset", 0f)
        if (trimStart != 0f || trimEnd != 1f) {
            path = trim(path, trimStart, trimEnd, trimOffset)
        }
        path.fillType = when (element.androidAttribute("fillType").lowercase()) {
            "evenodd", "1" -> Path.FillType.EVEN_ODD
            else -> Path.FillType.WINDING
        }

        val fill = resolveColor(element.androidAttribute("fillColor"))
        if (fill != null) {
            canvas.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = withAlpha(fill, element.androidFloat("fillAlpha", 1f))
                },
            )
        }
        val stroke = resolveColor(element.androidAttribute("strokeColor"))
        val strokeWidth = element.androidFloat("strokeWidth", 0f)
        if (stroke != null && strokeWidth > 0f) {
            canvas.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = withAlpha(stroke, element.androidFloat("strokeAlpha", 1f))
                    this.strokeWidth = strokeWidth
                    strokeCap = when (element.androidAttribute("strokeLineCap").lowercase()) {
                        "round", "1" -> Paint.Cap.ROUND
                        "square", "2" -> Paint.Cap.SQUARE
                        else -> Paint.Cap.BUTT
                    }
                    strokeJoin = when (element.androidAttribute("strokeLineJoin").lowercase()) {
                        "round", "1" -> Paint.Join.ROUND
                        "bevel", "2" -> Paint.Join.BEVEL
                        else -> Paint.Join.MITER
                    }
                    strokeMiter = element.androidFloat("strokeMiterLimit", 4f)
                },
            )
        }
    }

    private fun trim(path: Path, start: Float, end: Float, offset: Float): Path {
        val measure = PathMeasure(path, false)
        val length = measure.length
        if (length <= 0f) return path
        val normalizedStart = ((start + offset) % 1f + 1f) % 1f
        val normalizedEnd = ((end + offset) % 1f + 1f) % 1f
        val result = Path()
        if (normalizedStart > normalizedEnd) {
            measure.getSegment(normalizedStart * length, length, result, true)
            measure.getSegment(0f, normalizedEnd * length, result, true)
        } else {
            measure.getSegment(normalizedStart * length, normalizedEnd * length, result, true)
        }
        result.rLineTo(0f, 0f)
        return result
    }

    private fun drawReference(reference: String, canvas: Canvas, destination: RectF) {
        parseLiteralColor(reference)?.let {
            canvas.drawRect(destination, Paint().apply { color = it })
            return
        }
        val resolved = resolver.resolve(reference)
            ?: throw IOException("Drawable reference could not be resolved.")
        resolved.colorArgb?.let {
            canvas.drawRect(destination, Paint().apply { color = it })
            return
        }
        drawResource(
            requireNotNull(resolved.archivePath) { "Resolved resource has no value." },
            canvas,
            destination,
        )
    }

    private fun resolveColor(value: String): Int? {
        if (value.isBlank() || value == "@null" || value.equals("none", true)) return null
        parseLiteralColor(value)?.let { return it }
        return resolver.resolve(value)?.colorArgb
            ?: throw IOException("Vector color reference could not be resolved.")
    }

    private fun parseLiteralColor(value: String): Int? =
        runCatching {
            when (value.length) {
                4 -> {
                    val r = value[1].digitToInt(16) * 17
                    val g = value[2].digitToInt(16) * 17
                    val b = value[3].digitToInt(16) * 17
                    Color.rgb(r, g, b)
                }
                5 -> {
                    val a = value[1].digitToInt(16) * 17
                    val r = value[2].digitToInt(16) * 17
                    val g = value[3].digitToInt(16) * 17
                    val b = value[4].digitToInt(16) * 17
                    Color.argb(a, r, g, b)
                }
                else -> Color.parseColor(value)
            }
        }.getOrNull()

    private fun withAlpha(color: Int, multiplier: Float): Int =
        Color.argb(
            (Color.alpha(color) * multiplier.coerceIn(0f, 1f)).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External XML entities are forbidden.") }
        }
        val root = builder.parse(InputSource(StringReader(xml))).documentElement
        requireDepth(root, 1)
        return root
    }

    private fun requireDepth(element: Element, depth: Int) {
        if (depth > limits.maxXmlDepth) throw IOException("Drawable XML depth limit exceeded.")
        childElements(element).forEach { requireDepth(it, depth + 1) }
    }

    private fun childElements(element: Element): Sequence<Element> = sequence {
        var node: Node? = element.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE) yield(node as Element)
            node = node.nextSibling
        }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name).ifBlank { getAttribute("android:$name") }

    private fun Element.androidFloat(name: String, default: Float): Float =
        androidAttribute(name)
            .removeSuffix("dp")
            .removeSuffix("px")
            .toFloatOrNull()
            ?: default

    private fun unsafeLoop(subject: String, detail: String) =
        UnsafeArchiveException(
            ConversionIssue(
                IssueCode.DRAWABLE_REFERENCE_LOOP,
                IssueSeverity.ERROR,
                subject,
                detail,
            ),
        )

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
