package io.github.adiker.iconpacktomtz.core.model

import kotlinx.coroutines.flow.Flow
import java.io.File

interface IconPackAnalyzer {
    suspend fun analyze(
        apkFile: File,
        limits: ArchiveLimits,
        installedPackages: Set<String>? = null,
        sampleConfig: RenderConfig? = null,
    ): IconPackAnalysis
}

interface ConversionEngine {
    val progress: Flow<ConversionProgress>
    suspend fun convert(request: ConversionWorkRequest): ConversionResult
    fun cancel()
}

interface InstalledAppsProvider {
    val completeness: PackageListCompleteness
    suspend fun installedPackages(): Set<String>
}

enum class PackageListCompleteness {
    FILTERED_BY_ANDROID,
    COMPLETE_VIA_SHIZUKU,
}

interface RenderCache {
    suspend fun get(cacheKey: String): File?
    suspend fun put(cacheKey: String, pngBytes: ByteArray): File
    suspend fun touch(cacheKey: String)
    suspend fun clear()
    suspend fun trimToSize(maxBytes: Long)
}

interface IconRenderer {
    suspend fun render(
        apkFile: File,
        source: DrawableSource,
        config: RenderConfig,
        limits: ArchiveLimits,
    ): ByteArray
}

data class MtzBuildRequest(
    val destination: File,
    val iconsModule: File,
    val metadata: ThemeMetadata,
    val previewJpeg: ByteArray,
    val baseMtz: File?,
    val limits: ArchiveLimits,
)

data class MtzBuildResult(
    val file: File,
    val sha256: String,
    val byteSize: Long,
    val replacedBaseIcons: Boolean,
)

interface MtzBuilder {
    suspend fun build(request: MtzBuildRequest): MtzBuildResult
}

interface ReportWriter {
    suspend fun writeJson(report: ConversionReportV1, destination: File)
    suspend fun writeText(report: ConversionReportV1, destination: File)
}
