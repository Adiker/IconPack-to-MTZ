package io.github.adiker.iconpacktomtz.core.model

import java.io.File
import java.time.Instant

enum class ConversionMode {
    FULL,
    INSTALLED_ONLY,
}

enum class NamingStrategy {
    OPTIMIZED,
    FULL_COMPATIBILITY,
    PACKAGES_ONLY,
}

enum class ConversionStage {
    PREPARING,
    VALIDATING_INPUT,
    READING_APPFILTER,
    INVENTORYING_RESOURCES,
    FILTERING_APPLICATIONS,
    PLANNING_NAMES,
    RENDERING,
    BUILDING_ICONS_MODULE,
    BUILDING_MTZ,
    VALIDATING_OUTPUT,
    WRITING_REPORTS,
    COPYING_OUTPUT,
    FINISHED,
    CANCELLED,
    FAILED,
}

enum class ConversionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

enum class DrawableKind {
    SVG_ASSET,
    VECTOR_RESOURCE,
    ADAPTIVE_RESOURCE,
    RASTER_RESOURCE,
    RASTER_ASSET,
    UNKNOWN,
}

enum class IssueSeverity {
    INFO,
    WARNING,
    ERROR,
}

enum class IssueCode {
    INVALID_ARCHIVE,
    ARCHIVE_TOO_MANY_ENTRIES,
    ARCHIVE_COMPRESSED_LIMIT,
    ARCHIVE_EXPANDED_LIMIT,
    ARCHIVE_ENTRY_LIMIT,
    ARCHIVE_COMPRESSION_RATIO,
    ZIP_SLIP,
    APPFILTER_NOT_FOUND,
    APPFILTER_TOO_LARGE,
    APPFILTER_TOO_MANY_ITEMS,
    XML_FORBIDDEN_DECLARATION,
    XML_DEPTH_LIMIT,
    XML_PARSE_ERROR,
    INVALID_COMPONENT,
    DUPLICATE_COMPONENT,
    MISSING_DRAWABLE,
    UNSUPPORTED_FORMAT,
    CORRUPT_RESOURCE,
    DRAWABLE_REFERENCE_LOOP,
    SOURCE_BITMAP_TOO_LARGE,
    RENDER_FAILED,
    INVALID_OUTPUT_NAME,
    OUTPUT_NAME_COLLISION,
    OUTPUT_ENTRY_LIMIT,
    FALLBACK_USED,
    PACKAGE_VISIBILITY_LIMITED,
    SHIZUKU_UNAVAILABLE,
    BASE_MTZ_ICONS_REPLACED,
    BASE_MTZ_WITHOUT_ICONS,
    OUTPUT_VALIDATION_FAILED,
    IO_ERROR,
    CANCELLED,
}

data class ConversionIssue(
    val code: IssueCode,
    val severity: IssueSeverity,
    val subject: String? = null,
    val detail: String? = null,
)

data class ArchiveLimits(
    val maxEntries: Int = 100_000,
    val maxCompressedBytes: Long = 1L shl 30,
    val maxExpandedBytes: Long = 4L shl 30,
    val maxEntryBytes: Long = 256L shl 20,
    val maxCompressionRatio: Int = 200,
    val maxAppFilterBytes: Long = 32L shl 20,
    val maxAppFilterItems: Int = 200_000,
    val maxXmlDepth: Int = 64,
    val maxDrawableReferences: Int = 32,
    val maxBitmapPixels: Long = 16_000_000,
    val maxOutputEntries: Int = 60_000,
) {
    init {
        require(maxEntries in 1..HARD_MAX_ENTRIES)
        require(maxCompressedBytes in 1..HARD_MAX_COMPRESSED_BYTES)
        require(maxExpandedBytes in 1..HARD_MAX_EXPANDED_BYTES)
        require(maxEntryBytes in 1..HARD_MAX_ENTRY_BYTES)
        require(maxCompressionRatio in 1..HARD_MAX_COMPRESSION_RATIO)
        require(maxAppFilterBytes in 1..HARD_MAX_APPFILTER_BYTES)
        require(maxAppFilterItems in 1..HARD_MAX_APPFILTER_ITEMS)
        require(maxXmlDepth in 1..HARD_MAX_XML_DEPTH)
        require(maxDrawableReferences in 1..HARD_MAX_DRAWABLE_REFERENCES)
        require(maxBitmapPixels in 1..HARD_MAX_BITMAP_PIXELS)
        require(maxOutputEntries in 1..HARD_MAX_OUTPUT_ENTRIES)
    }

    companion object {
        const val HARD_MAX_ENTRIES = 250_000
        const val HARD_MAX_COMPRESSED_BYTES = 4L shl 30
        const val HARD_MAX_EXPANDED_BYTES = 8L shl 30
        const val HARD_MAX_ENTRY_BYTES = 512L shl 20
        const val HARD_MAX_COMPRESSION_RATIO = 500
        const val HARD_MAX_APPFILTER_BYTES = 64L shl 20
        const val HARD_MAX_APPFILTER_ITEMS = 400_000
        const val HARD_MAX_XML_DEPTH = 128
        const val HARD_MAX_DRAWABLE_REFERENCES = 64
        const val HARD_MAX_BITMAP_PIXELS = 36_000_000L
        const val HARD_MAX_OUTPUT_ENTRIES = 65_000

        fun advanced(): ArchiveLimits = ArchiveLimits(
            maxEntries = HARD_MAX_ENTRIES,
            maxCompressedBytes = HARD_MAX_COMPRESSED_BYTES,
            maxExpandedBytes = HARD_MAX_EXPANDED_BYTES,
            maxEntryBytes = HARD_MAX_ENTRY_BYTES,
            maxCompressionRatio = HARD_MAX_COMPRESSION_RATIO,
            maxAppFilterBytes = HARD_MAX_APPFILTER_BYTES,
            maxAppFilterItems = HARD_MAX_APPFILTER_ITEMS,
            maxXmlDepth = HARD_MAX_XML_DEPTH,
            maxDrawableReferences = HARD_MAX_DRAWABLE_REFERENCES,
            maxBitmapPixels = HARD_MAX_BITMAP_PIXELS,
            maxOutputEntries = HARD_MAX_OUTPUT_ENTRIES,
        )
    }
}

data class RenderConfig(
    val sizePx: Int = 168,
    val marginFraction: Float = 0.08f,
) {
    init {
        require(sizePx in 48..512)
        require(marginFraction in 0f..0.4f)
    }
}

data class ConverterSettings(
    val mode: ConversionMode = ConversionMode.FULL,
    val namingStrategy: NamingStrategy = NamingStrategy.OPTIMIZED,
    val render: RenderConfig = RenderConfig(),
    val workerCount: Int = defaultWorkerCount(),
    val limits: ArchiveLimits = ArchiveLimits(),
    val cacheLimitBytes: Long = 512L shl 20,
) {
    init {
        require(workerCount in 1..4)
        require(cacheLimitBytes in 32L * 1024 * 1024..2L * 1024 * 1024 * 1024)
    }

    companion object {
        fun defaultWorkerCount(): Int =
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    }
}

data class ThemeMetadata(
    val version: String = "1.0",
    val uiVersion: Int = 15,
    val author: String = "",
    val designer: String = "",
    val title: String = "Arcticons for HyperOS",
    val description: String = "",
)

data class AppComponent(
    val packageName: String,
    val activityName: String,
    val shortActivityName: String,
)

data class AppFilterEntry(
    val component: AppComponent,
    val drawableName: String,
    val sourceOrder: Int,
)

data class DrawableSource(
    val drawableName: String,
    val kind: DrawableKind,
    val resourceId: Int? = null,
    val assetPath: String? = null,
    val archivePath: String? = null,
    val densityDpi: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val sourceSha256: String? = null,
)

data class IconPackMetadata(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long?,
)

data class IconPackAnalysis(
    val metadata: IconPackMetadata,
    val mappingLocation: String,
    val entries: List<AppFilterEntry>,
    val drawables: Map<String, DrawableSource>,
    val packageCount: Int,
    val uniqueDrawableCount: Int,
    val predictedOutputFiles: Int,
    val estimatedMtzBytes: Long?,
    val issues: List<ConversionIssue>,
)

data class OutputAlias(
    val fileName: String,
    val drawableName: String,
    val packageName: String,
    val activityName: String? = null,
)

data class NamingPlan(
    val aliases: List<OutputAlias>,
    val issues: List<ConversionIssue>,
) {
    val uniqueDrawables: Set<String> get() = aliases.mapTo(linkedSetOf()) { it.drawableName }
}

data class RenderedIcon(
    val drawableName: String,
    val cacheKey: String,
    val pngFile: File,
    val pngSha256: String,
    val byteSize: Long,
    val fromCache: Boolean,
)

data class ConversionProgress(
    val stage: ConversionStage,
    val completed: Int = 0,
    val total: Int = 0,
    val renderedIcons: Int = 0,
    val aliasesWritten: Int = 0,
    val errors: Int = 0,
    val message: String? = null,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
}

data class ConversionWorkRequest(
    val operationId: String,
    val sourceDisplayName: String,
    val apkFile: File,
    val baseMtzFile: File?,
    val workspaceDirectory: File,
    val settings: ConverterSettings,
    val metadata: ThemeMetadata,
    val installedPackages: Set<String>? = null,
)

data class GeneratedArtifacts(
    val mtzFile: File,
    val jsonReportFile: File,
    val textReportFile: File,
    val report: ConversionReportV1,
)

data class ConversionResult(
    val artifacts: GeneratedArtifacts,
    val analysis: IconPackAnalysis,
)

data class StageTiming(
    val stage: ConversionStage,
    val durationMillis: Long,
)

data class DeduplicationStats(
    val requestedDrawables: Int = 0,
    val renderedDrawables: Int = 0,
    val sourceHashDuplicates: Int = 0,
    val pngHashDuplicates: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val aliasCount: Int = 0,
)

data class ConversionReportV1(
    val schemaVersion: Int = 1,
    val operationId: String,
    val createdAt: String = Instant.now().toString(),
    val sourceDisplayName: String,
    val mode: ConversionMode,
    val namingStrategy: NamingStrategy,
    val status: ConversionStatus,
    val mappingEntries: Int,
    val uniquePackages: Int,
    val uniqueDrawables: Int,
    val generatedFiles: Int,
    val skippedEntries: Int,
    val outputBytes: Long,
    val outputSha256: String?,
    val deduplication: DeduplicationStats,
    val stageTimings: List<StageTiming>,
    val issues: List<ConversionIssue>,
    val compatibilityNote: String =
        "Archive structure was validated, but import on a physical Xiaomi/HyperOS device was not verified.",
)
