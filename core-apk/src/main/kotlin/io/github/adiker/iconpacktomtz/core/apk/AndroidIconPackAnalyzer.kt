package io.github.adiker.iconpacktomtz.core.apk

import android.content.Context
import android.graphics.BitmapFactory
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlPullParser
import io.github.adiker.iconpacktomtz.core.archive.ArchiveValidator
import io.github.adiker.iconpacktomtz.core.archive.Sha256
import io.github.adiker.iconpacktomtz.core.archive.UnsafeArchiveException
import io.github.adiker.iconpacktomtz.core.archive.copyLimitedTo
import io.github.adiker.iconpacktomtz.core.model.AppFilterEntry
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.ConversionIssue
import io.github.adiker.iconpacktomtz.core.model.DrawableKind
import io.github.adiker.iconpacktomtz.core.model.DrawableSource
import io.github.adiker.iconpacktomtz.core.model.HyperOsNamingPlanner
import io.github.adiker.iconpacktomtz.core.model.IconPackAnalysis
import io.github.adiker.iconpacktomtz.core.model.IconPackAnalyzer
import io.github.adiker.iconpacktomtz.core.model.IconPackMetadata
import io.github.adiker.iconpacktomtz.core.model.IconRenderer
import io.github.adiker.iconpacktomtz.core.model.IssueCode
import io.github.adiker.iconpacktomtz.core.model.IssueSeverity
import io.github.adiker.iconpacktomtz.core.model.NamingStrategy
import io.github.adiker.iconpacktomtz.core.model.RenderCache
import io.github.adiker.iconpacktomtz.core.model.RenderCacheKey
import io.github.adiker.iconpacktomtz.core.model.RenderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

class AndroidIconPackAnalyzer(
    private val context: Context,
    private val archiveValidator: ArchiveValidator = ArchiveValidator(),
    private val appFilterParser: AppFilterParser = AppFilterParser(),
    private val namingPlanner: HyperOsNamingPlanner = HyperOsNamingPlanner(),
    private val sampleRenderer: IconRenderer? = null,
    private val sampleCache: RenderCache? = null,
) : IconPackAnalyzer {
    override suspend fun analyze(
        apkFile: File,
        limits: ArchiveLimits,
        installedPackages: Set<String>?,
        sampleConfig: RenderConfig?,
    ): IconPackAnalysis = withContext(Dispatchers.IO) {
        archiveValidator.requireValid(apkFile, limits)
        ZipFile(apkFile).use { zip ->
            // Snapshot central-directory metadata before loading Android resources. This also
            // avoids depending on provider-specific ZIP enumeration behaviour.
            val zipEntries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            ApkResourceSession.open(context, apkFile).use { resourceSession ->
                val mapping = findMapping(zip, zipEntries, resourceSession, limits)
                val filteredEntries = if (installedPackages == null) {
                    mapping.entries
                } else {
                    mapping.entries.filter { it.component.packageName in installedPackages }
                }
                val issues = mapping.issues.toMutableList()
                if (installedPackages != null) {
                    issues += ConversionIssue(
                        IssueCode.PACKAGE_VISIBILITY_LIMITED,
                        IssueSeverity.INFO,
                        detail = "Installed-only mode uses the package list visible to its provider.",
                    )
                }

                val drawables = linkedMapOf<String, DrawableSource>()
                filteredEntries.map { it.drawableName }.distinct().forEach { drawableName ->
                    coroutineContext.ensureActive()
                    val source = findDrawable(zip, zipEntries, resourceSession, drawableName, limits)
                    if (source == null) {
                        issues += ConversionIssue(
                            IssueCode.MISSING_DRAWABLE,
                            IssueSeverity.WARNING,
                            drawableName,
                            "No supported drawable source was found in the APK.",
                        )
                    } else {
                        drawables[drawableName] = source
                    }
                }

                val plan = namingPlanner.plan(filteredEntries, NamingStrategy.OPTIMIZED)
                issues += plan.issues
                val metadata = readMetadata(resourceSession, apkFile)
                val estimate = estimateOutputSize(
                    aliasCount = plan.aliases.size,
                    sources = drawables.values,
                    config = sampleConfig,
                    apkFile = apkFile,
                    limits = limits,
                )
                issues += estimate.issues
                IconPackAnalysis(
                    metadata = metadata,
                    mappingLocation = mapping.location,
                    entries = filteredEntries,
                    drawables = drawables,
                    packageCount = filteredEntries.map { it.component.packageName }.distinct().size,
                    uniqueDrawableCount = filteredEntries.map { it.drawableName }.distinct().size,
                    predictedOutputFiles = plan.aliases.size,
                    estimatedMtzBytes = estimate.bytes,
                    issues = issues,
                )
            }
        }
    }

    private fun findMapping(
        zip: ZipFile,
        entries: List<ZipEntry>,
        resources: ApkResourceSession,
        limits: ArchiveLimits,
    ): LocatedMapping {
        val candidates = mappingCandidates(zip, entries)
        val attemptedIssues = mutableListOf<ConversionIssue>()
        candidates.forEach { candidate ->
            val parsed = try {
                when {
                    candidate.assetOrText -> {
                        val bytes = zip.readEntryLimited(candidate.entry, limits.maxAppFilterBytes)
                        appFilterParser.parse(bytes, limits)
                    }
                    else -> {
                        parseCompiledMapping(zip, candidate, resources, limits)
                    }
                }
            } catch (exception: Exception) {
                AppFilterParseResult(
                    emptyList(),
                    listOf(
                        ConversionIssue(
                            IssueCode.XML_PARSE_ERROR,
                            IssueSeverity.WARNING,
                            candidate.entry.name,
                            "Candidate mapping could not be parsed: ${exception.javaClass.simpleName}",
                        ),
                    ),
                )
            }
            if (parsed.entries.isNotEmpty() && parsed.issues.none { it.severity == IssueSeverity.ERROR }) {
                if (attemptedIssues.isNotEmpty()) {
                    attemptedIssues += ConversionIssue(
                        IssueCode.FALLBACK_USED,
                        IssueSeverity.INFO,
                        candidate.entry.name,
                        "A lower-priority valid mapping file was selected.",
                    )
                }
                return LocatedMapping(candidate.entry.name, parsed.entries, attemptedIssues + parsed.issues)
            }
            attemptedIssues += parsed.issues
            if (parsed.entries.isEmpty() && parsed.issues.isEmpty()) {
                attemptedIssues += ConversionIssue(
                    IssueCode.XML_PARSE_ERROR,
                    IssueSeverity.WARNING,
                    candidate.entry.name,
                    "Candidate mapping contained no valid item elements.",
                )
            }
        }
        throw UnsafeArchiveException(
            ConversionIssue(
                IssueCode.APPFILTER_NOT_FOUND,
                IssueSeverity.ERROR,
                detail = buildString {
                    append("No supported, valid appfilter mapping was found.")
                    append(" Archive entries=")
                    append(entries.size)
                    append(", candidates=")
                    append(candidates.size)
                    append(' ')
                    append(candidates.joinToString { "${it.entry.name}:${it.assetOrText}" })
                    append('.')
                    attemptedIssues.take(3).forEach { issue ->
                        append(' ')
                        append(issue.code)
                        issue.detail?.let {
                            append(": ")
                            append(it.take(200))
                        }
                    }
                },
            ),
        )
    }

    private fun parseCompiledMapping(
        zip: ZipFile,
        candidate: MappingCandidate,
        resources: ApkResourceSession,
        limits: ArchiveLimits,
    ): AppFilterParseResult {
        val resourceId = resources.resourceId(candidate.resourceName, candidate.resourceType)
        if (resourceId != 0) {
            val nativeResult = runCatching {
                resources.resources.getXml(resourceId).use { parser ->
                    appFilterParser.parse(parser, limits)
                }
            }.getOrNull()
            if (nativeResult != null &&
                nativeResult.entries.isNotEmpty() &&
                nativeResult.issues.none { it.severity == IssueSeverity.ERROR }
            ) {
                return nativeResult
            }
        }

        val bytes = zip.readEntryLimited(candidate.entry, limits.maxAppFilterBytes)
        val document = ResXmlDocument().apply {
            readBytes(ByteArrayInputStream(bytes))
        }
        return ResXmlPullParser(document).use { parser ->
            appFilterParser.parse(parser, limits)
        }
    }

    private fun mappingCandidates(
        zip: ZipFile,
        entries: List<ZipEntry>,
    ): List<MappingCandidate> {
        val byName = entries.associateBy { it.name }
        val result = mutableListOf<MappingCandidate>()

        byName["assets/appfilter.xml"]?.let {
            result += MappingCandidate(it, true, "xml", "appfilter")
        }
        listOf("res/xml/appfilter.xml", "res/raw/appfilter.xml").forEach { path ->
            byName[path]?.let {
                val type = path.substringAfter("res/").substringBefore('/')
                result += MappingCandidate(it, looksLikePlainXml(zip, it), type, "appfilter")
            }
        }
        entries.asSequence()
            .filter {
                val fileName = it.name.substringAfterLast('/')
                val directory = it.name.substringBeforeLast('/', "")
                directory in setOf("res/xml", "res/raw", "assets") &&
                    fileName.endsWith(".xml") &&
                    (fileName.startsWith("appfilter_") || fileName == "app_filter.xml")
            }
            .sortedBy { it.name }
            .forEach {
                val type = when {
                    it.name.startsWith("res/raw/") -> "raw"
                    else -> "xml"
                }
                result += MappingCandidate(
                    it,
                    it.name.startsWith("assets/") || looksLikePlainXml(zip, it),
                    type,
                    it.name.substringAfterLast('/').substringBeforeLast('.'),
                )
            }
        byName["assets/icon_config.xml"]?.let {
            result += MappingCandidate(it, true, "xml", "icon_config")
        }
        return result.distinctBy { it.entry.name }
    }

    private fun looksLikePlainXml(zip: ZipFile, entry: ZipEntry): Boolean =
        runCatching {
            zip.getInputStream(entry).use { input ->
                val prefix = ByteArray(32)
                val count = input.read(prefix)
                prefix.take(count.coerceAtLeast(0))
                    .map { it.toInt().toChar() }
                    .joinToString("")
                    .trimStart()
                    .startsWith("<")
            }
        }.getOrDefault(false)

    private fun findDrawable(
        zip: ZipFile,
        entries: List<ZipEntry>,
        resources: ApkResourceSession,
        drawableName: String,
        limits: ArchiveLimits,
    ): DrawableSource? {
        val assetCandidates = entries.asSequence()
            .filter { it.name.startsWith("assets/") }
            .filter { it.name.substringAfterLast('/').substringBeforeLast('.') == drawableName }
            .toList()

        assetCandidates
            .filter { it.name.endsWith(".svg", ignoreCase = true) }
            .sortedBy { it.name.length }
            .firstOrNull()
            ?.let { entry ->
                val bytes = zip.readEntryLimited(
                    entry,
                    minOf(limits.maxEntryBytes, MAX_SVG_BYTES),
                )
                return DrawableSource(
                    drawableName = drawableName,
                    kind = DrawableKind.SVG_ASSET,
                    assetPath = entry.name.removePrefix("assets/"),
                    archivePath = entry.name,
                    sourceSha256 = Sha256.bytes(bytes),
                )
            }

        val resourceId = resources.resourceId(drawableName, "drawable")
            .takeIf { it != 0 }
            ?: resources.resourceId(drawableName, "mipmap").takeIf { it != 0 }
        if (resourceId != null) {
            val bestValue = DENSITIES.firstNotNullOfOrNull { density ->
                resources.valueForDensity(resourceId, density)?.let { density to it }
            }
            val density = bestValue?.second?.density?.coerceAtLeast(0) ?: 0
            val path = bestValue?.second?.string?.toString()
            val extension = path?.substringAfterLast('.', "")?.lowercase()
            val kind = if (extension == "xml") {
                when (xmlRoot(resources, resourceId)) {
                    "vector" -> DrawableKind.VECTOR_RESOURCE
                    "adaptive-icon" -> DrawableKind.ADAPTIVE_RESOURCE
                    else -> DrawableKind.VECTOR_RESOURCE
                }
            } else {
                DrawableKind.RASTER_RESOURCE
            }
            val dimensions = if (kind == DrawableKind.RASTER_RESOURCE) {
                decodeResourceBounds(resources, resourceId)
            } else {
                null
            }
            val hash = runCatching {
                resources.resources.openRawResource(resourceId).use(Sha256::stream)
            }.getOrNull()
            return DrawableSource(
                drawableName = drawableName,
                kind = kind,
                resourceId = resourceId,
                archivePath = path,
                densityDpi = density,
                width = dimensions?.first,
                height = dimensions?.second,
                sourceSha256 = hash,
            )
        }

        val indexedResources = resources.resourceFiles(drawableName)
            .mapNotNull { indexed ->
                val entry = entries.firstOrNull { it.name == indexed.path } ?: return@mapNotNull null
                IndexedArchiveResource(entry, indexed.densityDpi)
            }
        indexedResources
            .filter { it.entry.name.endsWith(".xml", ignoreCase = true) }
            .sortedByDescending { it.densityDpi }
            .firstOrNull()
            ?.let { indexed ->
                val bytes = zip.readEntryLimited(indexed.entry, limits.maxEntryBytes)
                val root = compiledXmlRoot(bytes)
                val kind = when (root) {
                    "adaptive-icon" -> DrawableKind.ADAPTIVE_RESOURCE
                    "vector" -> DrawableKind.VECTOR_RESOURCE
                    else -> DrawableKind.UNKNOWN
                }
                if (kind != DrawableKind.UNKNOWN) {
                    return DrawableSource(
                        drawableName = drawableName,
                        kind = kind,
                        archivePath = indexed.entry.name,
                        densityDpi = indexed.densityDpi,
                        sourceSha256 = Sha256.bytes(bytes),
                    )
                }
            }
        indexedResources
            .filter {
                it.entry.name.substringAfterLast('.', "").lowercase() in RASTER_EXTENSIONS
            }
            .mapNotNull { indexed ->
                val bytes = zip.readEntryLimited(indexed.entry, limits.maxEntryBytes)
                val bounds = decodeBounds(bytes) ?: return@mapNotNull null
                IndexedRaster(indexed, bytes, bounds)
            }
            .maxWithOrNull(
                compareBy<IndexedRaster> { it.bounds.first.toLong() * it.bounds.second }
                    .thenBy { it.resource.densityDpi },
            )
            ?.let { raster ->
                return DrawableSource(
                    drawableName = drawableName,
                    kind = DrawableKind.RASTER_RESOURCE,
                    archivePath = raster.resource.entry.name,
                    densityDpi = raster.resource.densityDpi,
                    width = raster.bounds.first,
                    height = raster.bounds.second,
                    sourceSha256 = Sha256.bytes(raster.bytes),
                )
            }

        assetCandidates
            .filter { entry ->
                entry.name.substringAfterLast('.', "").lowercase() in RASTER_EXTENSIONS
            }
            .mapNotNull { entry ->
                val bytes = zip.readEntryLimited(entry, limits.maxEntryBytes)
                val bounds = decodeBounds(bytes) ?: return@mapNotNull null
                IndexedAssetRaster(entry, bytes, bounds)
            }
            .maxByOrNull { it.bounds.first.toLong() * it.bounds.second }
            ?.let { raster ->
                return DrawableSource(
                    drawableName = drawableName,
                    kind = DrawableKind.RASTER_ASSET,
                    assetPath = raster.entry.name.removePrefix("assets/"),
                    archivePath = raster.entry.name,
                    width = raster.bounds.first,
                    height = raster.bounds.second,
                    sourceSha256 = Sha256.bytes(raster.bytes),
                )
            }
        return null
    }

    private fun compiledXmlRoot(bytes: ByteArray): String? = runCatching {
        ResXmlDocument().apply {
            readBytes(ByteArrayInputStream(bytes))
        }.documentElement?.name
    }.getOrNull()

    private fun xmlRoot(session: ApkResourceSession, resourceId: Int): String? =
        runCatching {
            session.resources.getXml(resourceId).use { parser ->
                while (parser.eventType != XmlPullParser.START_TAG &&
                    parser.eventType != XmlPullParser.END_DOCUMENT
                ) {
                    parser.next()
                }
                parser.name
            }
        }.getOrNull()

    private fun decodeResourceBounds(
        session: ApkResourceSession,
        resourceId: Int,
    ): Pair<Int, Int>? = runCatching {
        session.resources.openRawResource(resourceId).use { input ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                options.outWidth to options.outHeight
            } else {
                null
            }
        }
    }.getOrNull()

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    private fun readMetadata(session: ApkResourceSession, apkFile: File): IconPackMetadata {
        val info = session.packageInfo
        val applicationInfo = info.applicationInfo
        val label = runCatching {
            applicationInfo?.loadLabel(context.packageManager)?.toString()
        }.getOrNull().orEmpty().ifBlank { apkFile.nameWithoutExtension }
        return IconPackMetadata(
            packageName = info.packageName,
            label = label,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
        )
    }

    private suspend fun estimateOutputSize(
        aliasCount: Int,
        sources: Collection<DrawableSource>,
        config: RenderConfig?,
        apkFile: File,
        limits: ArchiveLimits,
    ): AnalysisEstimate {
        if (sources.isEmpty()) return AnalysisEstimate(null)
        val renderConfig = config ?: RenderConfig()
        val renderer = sampleRenderer
        val cache = sampleCache
        if (config != null && renderer != null && cache != null) {
            val sample = representativeSample(sources, renderConfig)
            val renderedSizes = mutableListOf<Long>()
            var failures = 0
            sample.forEach { source ->
                coroutineContext.ensureActive()
                try {
                    val cacheKey = RenderCacheKey.create(source, renderConfig)
                    val cached = cache.get(cacheKey)
                    val size = if (cached != null) {
                        cache.touch(cacheKey)
                        cached.length()
                    } else {
                        val png = renderer.render(apkFile, source, renderConfig, limits)
                        cache.put(cacheKey, png)
                        png.size.toLong()
                    }
                    if (size > 0) renderedSizes += size else failures++
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    failures++
                }
            }
            if (renderedSizes.isNotEmpty()) {
                val issues = if (failures == 0) {
                    emptyList()
                } else {
                    listOf(
                        ConversionIssue(
                            IssueCode.FALLBACK_USED,
                            IssueSeverity.INFO,
                            subject = "analysis-sample",
                            detail = "$failures of ${sample.size} sample icons could not be rendered; " +
                                "the estimate uses the successful sample.",
                        ),
                    )
                }
                return AnalysisEstimate(
                    bytes = 16_384L + renderedSizes.average().toLong() * aliasCount,
                    issues = issues,
                )
            }
            return AnalysisEstimate(
                bytes = heuristicEstimate(aliasCount, sources, renderConfig),
                issues = listOf(
                    ConversionIssue(
                        IssueCode.FALLBACK_USED,
                        IssueSeverity.INFO,
                        subject = "analysis-sample",
                        detail = "The representative sample could not be rendered; " +
                            "the size estimate uses resource metadata.",
                    ),
                ),
            )
        }
        return AnalysisEstimate(heuristicEstimate(aliasCount, sources, renderConfig))
    }

    private fun representativeSample(
        sources: Collection<DrawableSource>,
        config: RenderConfig,
    ): List<DrawableSource> {
        val groups = sources
            .distinctBy { RenderCacheKey.create(it, config) }
            .groupBy { it.kind }
            .toSortedMap(compareBy { it.name })
            .values
            .map { it.iterator() }
        val selected = mutableListOf<DrawableSource>()
        while (selected.size < MAX_ANALYSIS_SAMPLE) {
            var advanced = false
            groups.forEach { iterator ->
                if (selected.size < MAX_ANALYSIS_SAMPLE && iterator.hasNext()) {
                    selected += iterator.next()
                    advanced = true
                }
            }
            if (!advanced) break
        }
        return selected
    }

    private fun heuristicEstimate(
        aliasCount: Int,
        sources: Collection<DrawableSource>,
        config: RenderConfig,
    ): Long {
        val size = config.sizePx
        val representativeBytes = sources.take(MAX_ANALYSIS_SAMPLE).map { source ->
            when (source.kind) {
                DrawableKind.VECTOR_RESOURCE,
                DrawableKind.ADAPTIVE_RESOURCE,
                DrawableKind.SVG_ASSET,
                -> (size * size * 0.12).toLong()
                else -> {
                    val pixels = (source.width ?: size) * (source.height ?: size)
                    (pixels.coerceAtMost(size * size) * 0.45).toLong()
                }
            }
        }.average().toLong()
        return 16_384L + representativeBytes * aliasCount
    }

    private data class AnalysisEstimate(
        val bytes: Long?,
        val issues: List<ConversionIssue> = emptyList(),
    )

    private data class MappingCandidate(
        val entry: ZipEntry,
        val assetOrText: Boolean,
        val resourceType: String,
        val resourceName: String,
    )

    private data class LocatedMapping(
        val location: String,
        val entries: List<AppFilterEntry>,
        val issues: List<ConversionIssue>,
    )

    private data class IndexedArchiveResource(
        val entry: ZipEntry,
        val densityDpi: Int,
    )

    private data class IndexedRaster(
        val resource: IndexedArchiveResource,
        val bytes: ByteArray,
        val bounds: Pair<Int, Int>,
    )

    private data class IndexedAssetRaster(
        val entry: ZipEntry,
        val bytes: ByteArray,
        val bounds: Pair<Int, Int>,
    )

    private companion object {
        val DENSITIES = listOf(640, 560, 480, 420, 400, 360, 320, 280, 240, 213, 160, 120, 0)
        val RASTER_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg")
        const val MAX_SVG_BYTES = 32L * 1024 * 1024
        const val MAX_ANALYSIS_SAMPLE = 64
    }
}

private fun ZipFile.readEntryLimited(entry: ZipEntry, maxBytes: Long): ByteArray {
    if (entry.size < 0 || entry.size > maxBytes) {
        throw IllegalArgumentException("ZIP entry exceeds its configured limit.")
    }
    return getInputStream(entry).use { input ->
        ByteArrayOutputStream(entry.size.coerceAtMost(64 * 1024).toInt()).use { output ->
            input.copyLimitedTo(output, maxBytes)
            output.toByteArray()
        }
    }
}
