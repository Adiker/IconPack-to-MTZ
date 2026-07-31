package io.github.adiker.iconpacktomtz

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.adiker.iconpacktomtz.conversion.ConversionForegroundService
import io.github.adiker.iconpacktomtz.conversion.ConversionLaunch
import io.github.adiker.iconpacktomtz.conversion.ConversionSessionState
import io.github.adiker.iconpacktomtz.conversion.ConversionSessionStore
import io.github.adiker.iconpacktomtz.core.data.ConversionHistoryEntity
import io.github.adiker.iconpacktomtz.core.data.HistoryRepository
import io.github.adiker.iconpacktomtz.core.model.ConversionIssue
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.ConversionMode
import io.github.adiker.iconpacktomtz.core.model.ConverterSettings
import io.github.adiker.iconpacktomtz.core.model.HyperOsNamingPlanner
import io.github.adiker.iconpacktomtz.core.model.IconPackAnalysis
import io.github.adiker.iconpacktomtz.core.model.IconPackAnalyzer
import io.github.adiker.iconpacktomtz.core.model.IconRenderer
import io.github.adiker.iconpacktomtz.core.model.InstalledAppsProvider
import io.github.adiker.iconpacktomtz.core.model.IssueCode
import io.github.adiker.iconpacktomtz.core.model.IssueSeverity
import io.github.adiker.iconpacktomtz.core.model.NamingStrategy
import io.github.adiker.iconpacktomtz.core.model.RenderCache
import io.github.adiker.iconpacktomtz.core.model.RenderConfig
import io.github.adiker.iconpacktomtz.core.renderer.RendererCacheKey
import io.github.adiker.iconpacktomtz.saf.SafFileAccess
import io.github.adiker.iconpacktomtz.integration.shizuku.ShizukuInstalledAppsProvider
import io.github.adiker.iconpacktomtz.feature.settings.ConverterSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class ConverterFormState(
    val apkUri: String? = null,
    val apkName: String? = null,
    val baseMtzUri: String? = null,
    val baseMtzName: String? = null,
    val outputTreeUri: String? = null,
    val outputTreeName: String? = null,
    val mode: ConversionMode = ConversionMode.FULL,
    val namingStrategy: NamingStrategy = NamingStrategy.OPTIMIZED,
    val sizePx: Int = 168,
    val marginFraction: Float = 0.08f,
    val workerCount: Int = ConverterSettings.defaultWorkerCount(),
    val title: String = "Arcticons for HyperOS",
    val author: String = "",
    val description: String = "",
    val useShizuku: Boolean = false,
    val advancedLimits: Boolean = false,
    val cacheLimitMiB: Int = 512,
    val isAnalyzing: Boolean = false,
    val analysis: IconPackAnalysis? = null,
    val analysisError: String? = null,
)

data class ShizukuState(
    val available: Boolean = false,
    val permissionGranted: Boolean = false,
)

@HiltViewModel
class ConverterViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val analyzer: IconPackAnalyzer,
    private val renderer: IconRenderer,
    private val renderCache: RenderCache,
    private val saf: SafFileAccess,
    private val sessionStore: ConversionSessionStore,
    private val historyRepository: HistoryRepository,
    private val shizukuInstalledAppsProvider: ShizukuInstalledAppsProvider,
    private val settingsRepository: ConverterSettingsRepository,
    private val installedAppsProvider: InstalledAppsProvider,
) : ViewModel() {
    private val mutableForm = MutableStateFlow(ConverterFormState())
    val form: StateFlow<ConverterFormState> = mutableForm.asStateFlow()
    private val mutableShizukuState = MutableStateFlow(ShizukuState())
    val shizukuState: StateFlow<ShizukuState> = mutableShizukuState.asStateFlow()
    val session: StateFlow<ConversionSessionState> = sessionStore.state
    val history: StateFlow<List<ConversionHistoryEntity>> =
        historyRepository.observeRecent().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )
    private var analysisJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.markRunningInterrupted()
        }
        viewModelScope.launch {
            val persisted = settingsRepository.preferences.first()
            mutableForm.value = mutableForm.value.copy(
                mode = persisted.settings.mode,
                namingStrategy = persisted.settings.namingStrategy,
                sizePx = persisted.settings.render.sizePx,
                marginFraction = persisted.settings.render.marginFraction,
                workerCount = persisted.settings.workerCount,
                advancedLimits = persisted.settings.limits != ArchiveLimits(),
                cacheLimitMiB = (persisted.settings.cacheLimitBytes shr 20).toInt(),
                title = persisted.metadata.title,
                author = persisted.metadata.author,
                description = persisted.metadata.description,
            )
        }
        refreshShizukuState()
    }

    fun selectApk(uri: Uri) {
        saf.persistReadPermission(uri)
        mutableForm.value = mutableForm.value.copy(
            apkUri = uri.toString(),
            apkName = saf.displayName(uri),
            analysis = null,
            analysisError = null,
        )
    }

    fun selectBaseMtz(uri: Uri?) {
        uri?.let(saf::persistReadPermission)
        mutableForm.value = mutableForm.value.copy(
            baseMtzUri = uri?.toString(),
            baseMtzName = uri?.let(saf::displayName),
        )
    }

    fun selectOutputTree(uri: Uri) {
        saf.persistTreePermission(uri)
        val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
            ?: context.getString(R.string.selected_folder)
        mutableForm.value = mutableForm.value.copy(
            outputTreeUri = uri.toString(),
            outputTreeName = name,
        )
    }

    fun updateMode(value: ConversionMode) = update { copy(mode = value, analysis = null) }
    fun updateNaming(value: NamingStrategy) = update { copy(namingStrategy = value, analysis = null) }
    fun updateSize(value: Int) = update { copy(sizePx = value.coerceIn(48, 512), analysis = null) }
    fun updateMargin(value: Float) =
        update { copy(marginFraction = value.coerceIn(0f, 0.4f), analysis = null) }
    fun updateWorkers(value: Int) = update { copy(workerCount = value.coerceIn(1, 4)) }
    fun updateTitle(value: String) = update { copy(title = value.take(100)) }
    fun updateAuthor(value: String) = update { copy(author = value.take(100)) }
    fun updateDescription(value: String) = update { copy(description = value.take(500)) }
    fun updateUseShizuku(value: Boolean) {
        if (!value) {
            update { copy(useShizuku = false) }
            return
        }

        refreshShizukuState()
        if (!mutableShizukuState.value.available) return
        if (mutableShizukuState.value.permissionGranted) {
            update { copy(useShizuku = true) }
        } else {
            shizukuInstalledAppsProvider.requestPermission(
                ShizukuInstalledAppsProvider.PERMISSION_REQUEST_CODE,
            )
        }
    }
    fun updateAdvancedLimits(value: Boolean) = update { copy(advancedLimits = value) }
    fun updateCacheLimitMiB(value: Int) =
        update { copy(cacheLimitMiB = value.coerceIn(32, 2_048)) }

    fun refreshShizukuState() {
        val available = shizukuInstalledAppsProvider.isAvailable
        val permissionGranted = available && shizukuInstalledAppsProvider.hasPermission
        mutableShizukuState.value = ShizukuState(available, permissionGranted)
        if (mutableForm.value.useShizuku && !permissionGranted) {
            update { copy(useShizuku = false) }
        }
    }

    fun onShizukuPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode != ShizukuInstalledAppsProvider.PERMISSION_REQUEST_CODE) return
        refreshShizukuState()
        update {
            copy(
                useShizuku = grantResult == PackageManager.PERMISSION_GRANTED &&
                    mutableShizukuState.value.permissionGranted,
            )
        }
    }

    fun analyze() {
        val state = mutableForm.value
        val uri = state.apkUri?.let(Uri::parse) ?: return
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            mutableForm.value = state.copy(isAnalyzing = true, analysisError = null)
            val workspace = File(context.cacheDir, "analysis/${UUID.randomUUID()}")
            try {
                val settings = state.settings()
                val analysis = saf.prepareInput(
                    uri,
                    workspace,
                    "source.apk",
                    settings.limits,
                    requireReadableApkManifest = true,
                ).use { input ->
                    val installedPackages = if (state.mode == ConversionMode.INSTALLED_ONLY) {
                        if (state.useShizuku && shizukuInstalledAppsProvider.hasPermission) {
                            shizukuInstalledAppsProvider.installedPackages()
                        } else {
                            installedAppsProvider.installedPackages()
                        }
                    } else {
                        null
                    }
                    val baseAnalysis = analyzer.analyze(
                        input.file,
                        settings.limits,
                        installedPackages = installedPackages,
                        sampleConfig = settings.render,
                    )
                    val aliases = HyperOsNamingPlanner()
                        .plan(baseAnalysis.entries, state.namingStrategy)
                        .aliases
                    val estimate = renderSample(input.file, baseAnalysis, settings)
                        ?.let { average -> 16_384L + average * aliases.size }
                    baseAnalysis.copy(
                        predictedOutputFiles = aliases.size,
                        estimatedMtzBytes = estimate ?: baseAnalysis.estimatedMtzBytes,
                    )
                }
                mutableForm.value = mutableForm.value.copy(
                    isAnalyzing = false,
                    analysis = analysis,
                    analysisError = null,
                )
            } catch (exception: Exception) {
                mutableForm.value = mutableForm.value.copy(
                    isAnalyzing = false,
                    analysis = null,
                    analysisError = exception.message
                        ?: context.getString(R.string.analysis_failed),
                )
            } finally {
                withContext(Dispatchers.IO) { workspace.deleteRecursively() }
            }
        }
    }

    private suspend fun renderSample(
        apkFile: File,
        analysis: IconPackAnalysis,
        settings: ConverterSettings,
    ): Long? = coroutineScope {
        val sources = analysis.drawables.values
            .distinctBy { it.sourceSha256 ?: it.drawableName }
            .take(64)
        if (sources.isEmpty()) return@coroutineScope null
        val semaphore = Semaphore(settings.workerCount)
        val sizes = sources.map { source ->
            async(Dispatchers.Default) {
                semaphore.withPermit {
                    runCatching {
                        val key = RendererCacheKey.create(source, settings.render)
                        val cached = renderCache.get(key)
                        if (cached != null) {
                            cached.length()
                        } else {
                            val bytes = renderer.render(apkFile, source, settings.render, settings.limits)
                            renderCache.put(key, bytes)
                            bytes.size.toLong()
                        }
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
        sizes.takeIf { it.isNotEmpty() }?.average()?.toLong()
    }

    fun startConversion() {
        val state = mutableForm.value
        val apkUri = state.apkUri ?: return
        val outputTree = state.outputTreeUri ?: return
        val operationId = UUID.randomUUID().toString()
        val launch = ConversionLaunch(
            operationId = operationId,
            apkUri = apkUri,
            sourceDisplayName = state.apkName ?: "selected.apk",
            baseMtzUri = state.baseMtzUri,
            outputTreeUri = outputTree,
            mode = state.mode.name,
            namingStrategy = state.namingStrategy.name,
            sizePx = state.sizePx,
            marginFraction = state.marginFraction,
            workerCount = state.workerCount,
            title = state.title.ifBlank { "Arcticons for HyperOS" },
            author = state.author,
            description = state.description,
            useShizuku = state.useShizuku && shizukuInstalledAppsProvider.hasPermission,
            advancedLimits = state.advancedLimits,
            cacheLimitBytes = state.cacheLimitMiB.toLong() shl 20,
        )
        ContextCompat.startForegroundService(context, launch.toIntent(context))
    }

    fun cancelConversion() {
        context.startService(
            Intent(context, ConversionForegroundService::class.java)
                .setAction(ConversionForegroundService.ACTION_CANCEL),
        )
    }

    fun clearResult() = sessionStore.clear()
    fun clearHistory() = viewModelScope.launch { historyRepository.clear() }
    fun clearCache() = viewModelScope.launch { renderCache.clear() }

    private fun ConverterFormState.settings() = ConverterSettings(
        mode = mode,
        namingStrategy = namingStrategy,
        render = RenderConfig(sizePx, marginFraction),
        workerCount = workerCount,
        limits = if (advancedLimits) ArchiveLimits.advanced() else ArchiveLimits(),
        cacheLimitBytes = cacheLimitMiB.toLong() shl 20,
    )

    private inline fun update(block: ConverterFormState.() -> ConverterFormState) {
        mutableForm.value = mutableForm.value.block()
        val state = mutableForm.value
        viewModelScope.launch {
            settingsRepository.save(
                state.settings(),
                io.github.adiker.iconpacktomtz.core.model.ThemeMetadata(
                    title = state.title,
                    author = state.author,
                    designer = state.author,
                    description = state.description,
                ),
            )
        }
    }
}
