package io.github.adiker.iconpacktomtz.feature.converter

import io.github.adiker.iconpacktomtz.core.archive.Sha256
import io.github.adiker.iconpacktomtz.core.model.ConversionEngine
import io.github.adiker.iconpacktomtz.core.model.ConversionIssue
import io.github.adiker.iconpacktomtz.core.model.ConversionProgress
import io.github.adiker.iconpacktomtz.core.model.ConversionReportV1
import io.github.adiker.iconpacktomtz.core.model.ConversionResult
import io.github.adiker.iconpacktomtz.core.model.ConversionStage
import io.github.adiker.iconpacktomtz.core.model.ConversionStatus
import io.github.adiker.iconpacktomtz.core.model.ConversionWorkRequest
import io.github.adiker.iconpacktomtz.core.model.DeduplicationStats
import io.github.adiker.iconpacktomtz.core.model.GeneratedArtifacts
import io.github.adiker.iconpacktomtz.core.model.HyperOsNamingPlanner
import io.github.adiker.iconpacktomtz.core.model.IconPackAnalyzer
import io.github.adiker.iconpacktomtz.core.model.IconRenderer
import io.github.adiker.iconpacktomtz.core.model.IssueCode
import io.github.adiker.iconpacktomtz.core.model.IssueSeverity
import io.github.adiker.iconpacktomtz.core.model.MtzBuildRequest
import io.github.adiker.iconpacktomtz.core.model.MtzBuilder
import io.github.adiker.iconpacktomtz.core.model.RenderCache
import io.github.adiker.iconpacktomtz.core.model.RenderedIcon
import io.github.adiker.iconpacktomtz.core.model.ReportWriter
import io.github.adiker.iconpacktomtz.core.model.StageTiming
import io.github.adiker.iconpacktomtz.core.mtz.IconsModuleBuilder
import io.github.adiker.iconpacktomtz.core.mtz.PreviewGenerator
import io.github.adiker.iconpacktomtz.core.renderer.RendererCacheKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.job

class DefaultConversionEngine(
    private val analyzer: IconPackAnalyzer,
    private val renderer: IconRenderer,
    private val renderCache: RenderCache,
    private val mtzBuilder: MtzBuilder,
    private val reportWriter: ReportWriter,
    private val iconsModuleBuilder: IconsModuleBuilder = IconsModuleBuilder(),
    private val namingPlanner: HyperOsNamingPlanner = HyperOsNamingPlanner(),
) : ConversionEngine {
    private val mutableProgress = MutableStateFlow(ConversionProgress(ConversionStage.PREPARING))
    override val progress: Flow<ConversionProgress> = mutableProgress.asStateFlow()
    private val cancellationRequested = AtomicBoolean(false)
    private val activeJob = AtomicReference<Job?>()

    override fun cancel() {
        cancellationRequested.set(true)
        activeJob.get()?.cancel(CancellationException("Cancellation requested."))
    }

    override suspend fun convert(request: ConversionWorkRequest): ConversionResult {
        check(request.workspaceDirectory.exists() || request.workspaceDirectory.mkdirs()) {
            "Cannot create conversion workspace."
        }
        val conversionJob = coroutineContext.job
        check(activeJob.compareAndSet(null, conversionJob)) { "A conversion is already running." }
        cancellationRequested.set(false)
        val stageTimings = mutableListOf<StageTiming>()
        val issues = Collections.synchronizedList(mutableListOf<ConversionIssue>())
        val startedAt = System.nanoTime()

        return try {
            update(ConversionStage.VALIDATING_INPUT, message = "Validating source archive")
            checkCancellation()
            val analysis = timed(ConversionStage.READING_APPFILTER, stageTimings) {
                analyzer.analyze(
                    apkFile = request.apkFile,
                    limits = request.settings.limits,
                    installedPackages = request.installedPackages,
                    sampleConfig = request.settings.render,
                )
            }
            issues += analysis.issues
            checkCancellation()

            val namingPlan = timed(ConversionStage.PLANNING_NAMES, stageTimings) {
                namingPlanner.plan(analysis.entries, request.settings.namingStrategy)
            }
            issues += namingPlan.issues
            val aliasesWithSources = namingPlan.aliases.filter { it.drawableName in analysis.drawables }
            val missingAliases = namingPlan.aliases.size - aliasesWithSources.size
            if (aliasesWithSources.size > request.settings.limits.maxOutputEntries) {
                throw IllegalArgumentException("Output would exceed the configured entry limit.")
            }
            check(aliasesWithSources.isNotEmpty()) { "No supported icons remain after analysis." }

            val requiredDrawables = aliasesWithSources.mapTo(linkedSetOf()) { it.drawableName }
            val sourceGroups = requiredDrawables.groupBy { name ->
                analysis.drawables.getValue(name).sourceSha256 ?: "name:$name"
            }
            val sourceDuplicates = requiredDrawables.size - sourceGroups.size
            val renderedByDrawable = ConcurrentHashMap<String, RenderedIcon>()
            val cacheHits = AtomicInteger(0)
            val cacheMisses = AtomicInteger(0)
            val renderedCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)
            val completedCount = AtomicInteger(0)
            val pngCanonicalFiles = ConcurrentHashMap<String, File>()
            val pngDuplicates = AtomicInteger(0)

            timed(ConversionStage.RENDERING, stageTimings) {
                update(
                    ConversionStage.RENDERING,
                    total = sourceGroups.size,
                    message = "Rendering unique icons",
                )
                val semaphore = Semaphore(request.settings.workerCount)
                coroutineScope {
                    sourceGroups.values.map { drawableNames ->
                        async(Dispatchers.Default) {
                            semaphore.withPermit {
                                checkCancellation()
                                val canonicalName = drawableNames.first()
                                val source = analysis.drawables.getValue(canonicalName)
                                val cacheKey = RendererCacheKey.create(source, request.settings.render)
                                try {
                                    val cached = renderCache.get(cacheKey)
                                    val rendered = if (cached != null) {
                                        cacheHits.incrementAndGet()
                                        RenderedIcon(
                                            canonicalName,
                                            cacheKey,
                                            cached,
                                            Sha256.file(cached),
                                            cached.length(),
                                            true,
                                        )
                                    } else {
                                        cacheMisses.incrementAndGet()
                                        val bytes = renderer.render(
                                            request.apkFile,
                                            source,
                                            request.settings.render,
                                            request.settings.limits,
                                        )
                                        checkCancellation()
                                        val file = renderCache.put(cacheKey, bytes)
                                        renderedCount.incrementAndGet()
                                        RenderedIcon(
                                            canonicalName,
                                            cacheKey,
                                            file,
                                            Sha256.bytes(bytes),
                                            bytes.size.toLong(),
                                            false,
                                        )
                                    }
                                    val previousFile = pngCanonicalFiles.putIfAbsent(
                                        rendered.pngSha256,
                                        rendered.pngFile,
                                    )
                                    val canonicalFile = previousFile ?: rendered.pngFile
                                    if (previousFile != null && previousFile != rendered.pngFile) {
                                        pngDuplicates.incrementAndGet()
                                    }
                                    drawableNames.forEach { name ->
                                        renderedByDrawable[name] = rendered.copy(
                                            drawableName = name,
                                            pngFile = canonicalFile,
                                        )
                                    }
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: Exception) {
                                    failedCount.incrementAndGet()
                                    issues += ConversionIssue(
                                        IssueCode.RENDER_FAILED,
                                        IssueSeverity.ERROR,
                                        canonicalName,
                                        buildString {
                                            append("Rendering failed: ")
                                            append(exception.javaClass.simpleName)
                                            exception.message?.let { message ->
                                                append(": ")
                                                append(
                                                    message
                                                        .replace(
                                                            request.apkFile.absolutePath,
                                                            "<source>",
                                                        )
                                                        .replace(
                                                            request.workspaceDirectory.absolutePath,
                                                            "<workspace>",
                                                        )
                                                        .take(200),
                                                )
                                            }
                                        },
                                    )
                                } finally {
                                    val complete = completedCount.incrementAndGet()
                                    update(
                                        stage = ConversionStage.RENDERING,
                                        completed = complete,
                                        total = sourceGroups.size,
                                        rendered = renderedCount.get() + cacheHits.get(),
                                        errors = failedCount.get(),
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            checkCancellation()

            val buildableAliases = aliasesWithSources.filter {
                renderedByDrawable.containsKey(it.drawableName)
            }
            check(buildableAliases.isNotEmpty()) { "All icon renders failed." }
            val iconsModule = File(request.workspaceDirectory, "icons")
            timed(ConversionStage.BUILDING_ICONS_MODULE, stageTimings) {
                update(
                    ConversionStage.BUILDING_ICONS_MODULE,
                    total = buildableAliases.size,
                    message = "Building icons module",
                )
                iconsModuleBuilder.build(
                    destination = iconsModule,
                    aliases = buildableAliases,
                    rendered = renderedByDrawable,
                    limits = request.settings.limits,
                ) { complete ->
                    update(
                        ConversionStage.BUILDING_ICONS_MODULE,
                        completed = complete,
                        total = buildableAliases.size,
                        aliases = complete,
                        errors = failedCount.get(),
                    )
                }
            }
            checkCancellation()

            val preview = withContext(Dispatchers.Default) {
                PreviewGenerator.create(
                    renderedByDrawable.values
                        .distinctBy { it.pngSha256 }
                        .sortedBy { it.drawableName }
                        .map { it.pngFile },
                    request.metadata.title,
                )
            }
            val mtzFile = File(request.workspaceDirectory, "theme.mtz")
            val mtzResult = timed(ConversionStage.BUILDING_MTZ, stageTimings) {
                update(ConversionStage.BUILDING_MTZ, message = "Building MTZ archive")
                mtzBuilder.build(
                    MtzBuildRequest(
                        destination = mtzFile,
                        iconsModule = iconsModule,
                        metadata = request.metadata,
                        previewJpeg = preview,
                        baseMtz = request.baseMtzFile,
                        limits = request.settings.limits,
                    ),
                )
            }
            if (mtzResult.replacedBaseIcons) {
                issues += ConversionIssue(
                    IssueCode.BASE_MTZ_ICONS_REPLACED,
                    IssueSeverity.INFO,
                    detail = "The exact root icons module from the base MTZ was replaced.",
                )
            } else if (request.baseMtzFile != null) {
                issues += ConversionIssue(
                    IssueCode.BASE_MTZ_WITHOUT_ICONS,
                    IssueSeverity.WARNING,
                    detail = "The base MTZ had no exact root icons module; a new one was added.",
                )
            }

            val failedAliases = aliasesWithSources.size - buildableAliases.size
            val deduplication = DeduplicationStats(
                requestedDrawables = requiredDrawables.size,
                renderedDrawables = renderedCount.get(),
                sourceHashDuplicates = sourceDuplicates,
                pngHashDuplicates = pngDuplicates.get(),
                cacheHits = cacheHits.get(),
                cacheMisses = cacheMisses.get(),
                aliasCount = buildableAliases.size,
            )
            val report = ConversionReportV1(
                operationId = request.operationId,
                sourceDisplayName = request.sourceDisplayName,
                mode = request.settings.mode,
                namingStrategy = request.settings.namingStrategy,
                status = ConversionStatus.SUCCEEDED,
                mappingEntries = analysis.entries.size,
                uniquePackages = analysis.packageCount,
                uniqueDrawables = analysis.uniqueDrawableCount,
                generatedFiles = buildableAliases.size,
                skippedEntries = missingAliases + failedAliases,
                outputBytes = mtzResult.byteSize,
                outputSha256 = mtzResult.sha256,
                deduplication = deduplication,
                stageTimings = stageTimings,
                issues = issues.toList(),
            )
            val jsonReport = File(request.workspaceDirectory, "conversion-report.json")
            val textReport = File(request.workspaceDirectory, "conversion-report.txt")
            timed(ConversionStage.WRITING_REPORTS, stageTimings) {
                update(ConversionStage.WRITING_REPORTS, message = "Writing reports")
                reportWriter.writeJson(report.copy(stageTimings = stageTimings), jsonReport)
                reportWriter.writeText(report.copy(stageTimings = stageTimings), textReport)
            }
            val finalReport = report.copy(stageTimings = stageTimings.toList())
            // Rewrite after measuring so exported reports include their own stage duration.
            reportWriter.writeJson(finalReport, jsonReport)
            reportWriter.writeText(finalReport, textReport)
            renderCache.trimToSize(request.settings.cacheLimitBytes)
            update(
                ConversionStage.FINISHED,
                completed = buildableAliases.size,
                total = buildableAliases.size,
                rendered = renderedCount.get() + cacheHits.get(),
                aliases = buildableAliases.size,
                errors = failedCount.get(),
                message = "Conversion complete",
            )
            ConversionResult(
                artifacts = GeneratedArtifacts(
                    mtzFile,
                    jsonReport,
                    textReport,
                    finalReport.copy(
                        outputBytes = mtzFile.length(),
                        outputSha256 = Sha256.file(mtzFile),
                    ),
                ),
                analysis = analysis,
            )
        } catch (exception: CancellationException) {
            update(ConversionStage.CANCELLED, message = "Conversion cancelled")
            throw exception
        } catch (exception: Exception) {
            update(
                ConversionStage.FAILED,
                errors = mutableProgress.value.errors + 1,
                message = exception.message?.take(256),
            )
            throw exception
        } finally {
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            if (stageTimings.none { it.stage == mutableProgress.value.stage }) {
                stageTimings += StageTiming(mutableProgress.value.stage, elapsed)
            }
            activeJob.compareAndSet(conversionJob, null)
        }
    }

    private suspend fun <T> timed(
        stage: ConversionStage,
        timings: MutableList<StageTiming>,
        block: suspend () -> T,
    ): T {
        val started = System.nanoTime()
        return try {
            block()
        } finally {
            timings += StageTiming(stage, (System.nanoTime() - started) / 1_000_000)
        }
    }

    private suspend fun checkCancellation() {
        coroutineContext.ensureActive()
        if (cancellationRequested.get()) throw CancellationException("Cancellation requested.")
    }

    private fun update(
        stage: ConversionStage,
        completed: Int = 0,
        total: Int = 0,
        rendered: Int = mutableProgress.value.renderedIcons,
        aliases: Int = mutableProgress.value.aliasesWritten,
        errors: Int = mutableProgress.value.errors,
        message: String? = null,
    ) {
        mutableProgress.value = ConversionProgress(
            stage = stage,
            completed = completed,
            total = total,
            renderedIcons = rendered,
            aliasesWritten = aliases,
            errors = errors,
            message = message,
        )
    }
}
