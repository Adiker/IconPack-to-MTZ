package io.github.adiker.iconpacktomtz.conversion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.adiker.iconpacktomtz.MainActivity
import io.github.adiker.iconpacktomtz.R
import io.github.adiker.iconpacktomtz.core.data.ConversionHistoryEntity
import io.github.adiker.iconpacktomtz.core.data.HistoryRepository
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.ConversionEngine
import io.github.adiker.iconpacktomtz.core.model.ConversionMode
import io.github.adiker.iconpacktomtz.core.model.ConversionProgress
import io.github.adiker.iconpacktomtz.core.model.ConversionIssue
import io.github.adiker.iconpacktomtz.core.model.ConversionReportV1
import io.github.adiker.iconpacktomtz.core.model.ConversionStage
import io.github.adiker.iconpacktomtz.core.model.ConversionStatus
import io.github.adiker.iconpacktomtz.core.model.ConversionWorkRequest
import io.github.adiker.iconpacktomtz.core.model.ConverterSettings
import io.github.adiker.iconpacktomtz.core.model.InstalledAppsProvider
import io.github.adiker.iconpacktomtz.core.model.NamingStrategy
import io.github.adiker.iconpacktomtz.core.model.RenderConfig
import io.github.adiker.iconpacktomtz.core.model.ReportWriter
import io.github.adiker.iconpacktomtz.core.model.DeduplicationStats
import io.github.adiker.iconpacktomtz.core.model.IssueCode
import io.github.adiker.iconpacktomtz.core.model.IssueSeverity
import io.github.adiker.iconpacktomtz.core.model.StageTiming
import io.github.adiker.iconpacktomtz.core.model.ThemeMetadata
import io.github.adiker.iconpacktomtz.saf.SafFileAccess
import io.github.adiker.iconpacktomtz.integration.shizuku.ShizukuInstalledAppsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ConversionForegroundService : Service() {
    @Inject lateinit var engine: ConversionEngine
    @Inject lateinit var saf: SafFileAccess
    @Inject lateinit var installedAppsProvider: InstalledAppsProvider
    @Inject lateinit var shizukuInstalledAppsProvider: ShizukuInstalledAppsProvider
    @Inject lateinit var history: HistoryRepository
    @Inject lateinit var sessionStore: ConversionSessionStore
    @Inject lateinit var reportWriter: ReportWriter

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var conversionJob: Job? = null
    private var progressJob: Job? = null
    private var activeOperationId: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            engine.cancel()
            conversionJob?.cancel(CancellationException("Cancelled by the user."))
            return START_NOT_STICKY
        }
        if (conversionJob?.isActive == true) return START_NOT_STICKY
        val launch = ConversionLaunch.fromIntent(intent) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeOperationId = launch.operationId
        sessionStore.start(launch.operationId)
        startForegroundNow(launch.operationId, ConversionProgress(ConversionStage.PREPARING))
        conversionJob = serviceScope.launch { runConversion(launch, startId) }
        return START_NOT_STICKY
    }

    private suspend fun runConversion(launch: ConversionLaunch, startId: Int) {
        val startedAt = System.currentTimeMillis()
        val workspace = File(cacheDir, "conversions/${launch.operationId}")
        check(workspace.exists() || workspace.mkdirs())
        val settings = launch.settings()
        history.upsert(
            ConversionHistoryEntity(
                operationId = launch.operationId,
                sourceDisplayName = launch.sourceDisplayName,
                startedAtEpochMillis = startedAt,
                completedAtEpochMillis = null,
                mode = settings.mode,
                namingStrategy = settings.namingStrategy,
                iconSizePx = settings.render.sizePx,
                marginFraction = settings.render.marginFraction,
                workerCount = settings.workerCount,
                iconCount = 0,
                durationMillis = 0,
                outputBytes = 0,
                status = ConversionStatus.RUNNING,
                outputUri = null,
                jsonReportUri = null,
                textReportUri = null,
                errorSummary = null,
            ),
        )
        progressJob = serviceScope.launch {
            var lastNotificationBucket = -1
            engine.progress.collectLatest { progress ->
                sessionStore.progress(launch.operationId, progress)
                val bucket = if (progress.total > 0) progress.completed * 20 / progress.total else 0
                if (bucket != lastNotificationBucket || progress.completed == progress.total) {
                    lastNotificationBucket = bucket
                    notifyProgress(launch.operationId, progress)
                }
            }
        }

        try {
            saf.prepareInput(
                Uri.parse(launch.apkUri),
                workspace,
                "source.apk",
                settings.limits,
                requireReadableApkManifest = true,
            ).use { apk ->
                val base = launch.baseMtzUri?.let { baseUri ->
                    saf.prepareInput(
                        Uri.parse(baseUri),
                        workspace,
                        "base.mtz",
                        settings.limits,
                    )
                }
                base.use {
                    val installedPackages = if (settings.mode == ConversionMode.INSTALLED_ONLY) {
                        if (launch.useShizuku && shizukuInstalledAppsProvider.hasPermission) {
                            shizukuInstalledAppsProvider.installedPackages()
                        } else {
                            installedAppsProvider.installedPackages()
                        }
                    } else {
                        null
                    }
                    val result = engine.convert(
                        ConversionWorkRequest(
                            operationId = launch.operationId,
                            sourceDisplayName = launch.sourceDisplayName,
                            apkFile = apk.file,
                            baseMtzFile = base?.file,
                            workspaceDirectory = workspace,
                            settings = settings,
                            metadata = launch.metadata(),
                            installedPackages = installedPackages,
                        ),
                    )
                    sessionStore.progress(
                        launch.operationId,
                        ConversionProgress(
                            ConversionStage.COPYING_OUTPUT,
                            message = getString(R.string.copying_output),
                        ),
                    )
                    val published = saf.publish(
                        treeUri = Uri.parse(launch.outputTreeUri),
                        baseName = launch.metadata().title,
                        mtzFile = result.artifacts.mtzFile,
                        jsonReportFile = result.artifacts.jsonReportFile,
                        textReportFile = result.artifacts.textReportFile,
                    )
                    val completedAt = System.currentTimeMillis()
                    val errorCount = result.artifacts.report.issues.count {
                        it.severity == io.github.adiker.iconpacktomtz.core.model.IssueSeverity.ERROR
                    }
                    history.upsert(
                        ConversionHistoryEntity(
                            operationId = launch.operationId,
                            sourceDisplayName = launch.sourceDisplayName,
                            startedAtEpochMillis = startedAt,
                            completedAtEpochMillis = completedAt,
                            mode = settings.mode,
                            namingStrategy = settings.namingStrategy,
                            iconSizePx = settings.render.sizePx,
                            marginFraction = settings.render.marginFraction,
                            workerCount = settings.workerCount,
                            iconCount = result.artifacts.report.generatedFiles,
                            durationMillis = completedAt - startedAt,
                            outputBytes = result.artifacts.mtzFile.length(),
                            status = ConversionStatus.SUCCEEDED,
                            outputUri = published.mtzUri.toString(),
                            jsonReportUri = published.jsonReportUri.toString(),
                            textReportUri = published.textReportUri.toString(),
                            errorSummary = null,
                        ),
                    )
                    sessionStore.completed(
                        ConversionSessionState.Completed(
                            operationId = launch.operationId,
                            outputUri = published.mtzUri.toString(),
                            jsonReportUri = published.jsonReportUri.toString(),
                            textReportUri = published.textReportUri.toString(),
                            outputBytes = result.artifacts.mtzFile.length(),
                            durationMillis = completedAt - startedAt,
                            generatedIcons = result.artifacts.report.generatedFiles,
                            skippedEntries = result.artifacts.report.skippedEntries,
                            errors = errorCount,
                        ),
                    )
                    notifyCompleted()
                }
            }
        } catch (exception: CancellationException) {
            finishFailure(launch, workspace, startedAt, exception, cancelled = true)
        } catch (exception: Exception) {
            finishFailure(launch, workspace, startedAt, exception, cancelled = false)
        } finally {
            progressJob?.cancel()
            workspace.deleteRecursively()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            activeOperationId = null
            stopSelf(startId)
        }
    }

    private suspend fun finishFailure(
        launch: ConversionLaunch,
        workspace: File,
        startedAt: Long,
        exception: Exception,
        cancelled: Boolean,
    ) {
        val completedAt = System.currentTimeMillis()
        val status = if (cancelled) ConversionStatus.CANCELLED else ConversionStatus.FAILED
        val message = if (cancelled) {
            getString(R.string.conversion_cancelled)
        } else {
            exception.message?.take(256) ?: getString(R.string.conversion_failed)
        }
        val reportStatus = if (cancelled) ConversionStatus.CANCELLED else ConversionStatus.FAILED
        val progress = (sessionStore.state.value as? ConversionSessionState.Running)?.progress
        val report = ConversionReportV1(
            operationId = launch.operationId,
            sourceDisplayName = launch.sourceDisplayName,
            mode = launch.settings().mode,
            namingStrategy = launch.settings().namingStrategy,
            status = reportStatus,
            mappingEntries = 0,
            uniquePackages = 0,
            uniqueDrawables = 0,
            generatedFiles = 0,
            skippedEntries = 0,
            outputBytes = 0,
            outputSha256 = null,
            deduplication = DeduplicationStats(),
            stageTimings = listOf(
                StageTiming(
                    progress?.stage ?: ConversionStage.FAILED,
                    completedAt - startedAt,
                ),
            ),
            issues = listOf(
                ConversionIssue(
                    if (cancelled) IssueCode.CANCELLED else IssueCode.IO_ERROR,
                    if (cancelled) IssueSeverity.INFO else IssueSeverity.ERROR,
                    detail = message,
                ),
            ),
        )
        val publishedReports = runCatching {
            val json = File(workspace, "conversion-report.json")
            val text = File(workspace, "conversion-report.txt")
            reportWriter.writeJson(report, json)
            reportWriter.writeText(report, text)
            saf.publishReports(
                Uri.parse(launch.outputTreeUri),
                launch.metadata().title,
                json,
                text,
            )
        }.getOrNull()
        history.upsert(
            ConversionHistoryEntity(
                operationId = launch.operationId,
                sourceDisplayName = launch.sourceDisplayName,
                startedAtEpochMillis = startedAt,
                completedAtEpochMillis = completedAt,
                mode = launch.settings().mode,
                namingStrategy = launch.settings().namingStrategy,
                iconSizePx = launch.sizePx,
                marginFraction = launch.marginFraction,
                workerCount = launch.workerCount,
                iconCount = 0,
                durationMillis = completedAt - startedAt,
                outputBytes = 0,
                status = status,
                outputUri = null,
                jsonReportUri = publishedReports?.jsonReportUri?.toString(),
                textReportUri = publishedReports?.textReportUri?.toString(),
                errorSummary = message,
            ),
        )
        sessionStore.failed(
            launch.operationId,
            message,
            cancelled,
            publishedReports?.jsonReportUri?.toString(),
            publishedReports?.textReportUri?.toString(),
        )
        notifyFailed(message)
    }

    private fun startForegroundNow(operationId: String, progress: ConversionProgress) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(operationId, progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun notifyProgress(operationId: String, progress: ConversionProgress) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(operationId, progress))
    }

    private fun buildNotification(
        operationId: String,
        progress: ConversionProgress,
    ): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ConversionForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(progress.message ?: progress.stage.name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setProgress(progress.total, progress.completed, progress.total <= 0)
            .addAction(0, getString(R.string.cancel), cancelIntent)
            .build()
    }

    private fun notifyCompleted() {
        getSystemService(NotificationManager::class.java).notify(
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.conversion_complete))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notifyFailed(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.conversion_failed))
                .setContentText(message)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        engine.cancel()
        conversionJob?.cancel(CancellationException("Foreground-service time limit reached."))
        stopSelf(startId)
    }

    override fun onDestroy() {
        progressJob?.cancel()
        conversionJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "io.github.adiker.iconpacktomtz.action.START_CONVERSION"
        const val ACTION_CANCEL = "io.github.adiker.iconpacktomtz.action.CANCEL_CONVERSION"
        const val CHANNEL_ID = "conversion"
        const val NOTIFICATION_ID = 1042
        const val RESULT_NOTIFICATION_ID = 1043
    }
}

data class ConversionLaunch(
    val operationId: String,
    val apkUri: String,
    val sourceDisplayName: String,
    val baseMtzUri: String?,
    val outputTreeUri: String,
    val mode: String,
    val namingStrategy: String,
    val sizePx: Int,
    val marginFraction: Float,
    val workerCount: Int,
    val title: String,
    val author: String,
    val description: String,
    val useShizuku: Boolean,
    val advancedLimits: Boolean,
    val cacheLimitBytes: Long,
) {
    fun settings() = ConverterSettings(
        mode = ConversionMode.valueOf(mode),
        namingStrategy = NamingStrategy.valueOf(namingStrategy),
        render = RenderConfig(sizePx, marginFraction),
        workerCount = workerCount,
        limits = if (advancedLimits) ArchiveLimits.advanced() else ArchiveLimits(),
        cacheLimitBytes = cacheLimitBytes,
    )

    fun metadata() = ThemeMetadata(
        title = title,
        author = author,
        designer = author,
        description = description,
    )

    fun toIntent(context: android.content.Context): Intent =
        Intent(context, ConversionForegroundService::class.java)
            .setAction(ConversionForegroundService.ACTION_START)
            .putExtras(toBundle())

    private fun toBundle() = android.os.Bundle().apply {
        putString(KEY_OPERATION, operationId)
        putString(KEY_APK, apkUri)
        putString(KEY_SOURCE_NAME, sourceDisplayName)
        putString(KEY_BASE, baseMtzUri)
        putString(KEY_OUTPUT, outputTreeUri)
        putString(KEY_MODE, mode)
        putString(KEY_NAMING, namingStrategy)
        putInt(KEY_SIZE, sizePx)
        putFloat(KEY_MARGIN, marginFraction)
        putInt(KEY_WORKERS, workerCount)
        putString(KEY_TITLE, title)
        putString(KEY_AUTHOR, author)
        putString(KEY_DESCRIPTION, description)
        putBoolean(KEY_SHIZUKU, useShizuku)
        putBoolean(KEY_ADVANCED_LIMITS, advancedLimits)
        putLong(KEY_CACHE_LIMIT, cacheLimitBytes)
    }

    companion object {
        private const val KEY_OPERATION = "operation"
        private const val KEY_APK = "apk"
        private const val KEY_SOURCE_NAME = "source_name"
        private const val KEY_BASE = "base"
        private const val KEY_OUTPUT = "output"
        private const val KEY_MODE = "mode"
        private const val KEY_NAMING = "naming"
        private const val KEY_SIZE = "size"
        private const val KEY_MARGIN = "margin"
        private const val KEY_WORKERS = "workers"
        private const val KEY_TITLE = "title"
        private const val KEY_AUTHOR = "author"
        private const val KEY_DESCRIPTION = "description"
        private const val KEY_SHIZUKU = "shizuku"
        private const val KEY_ADVANCED_LIMITS = "advanced_limits"
        private const val KEY_CACHE_LIMIT = "cache_limit"

        fun fromIntent(intent: Intent?): ConversionLaunch? {
            if (intent?.action != ConversionForegroundService.ACTION_START) return null
            return ConversionLaunch(
                operationId = intent.getStringExtra(KEY_OPERATION) ?: return null,
                apkUri = intent.getStringExtra(KEY_APK) ?: return null,
                sourceDisplayName = intent.getStringExtra(KEY_SOURCE_NAME) ?: return null,
                baseMtzUri = intent.getStringExtra(KEY_BASE),
                outputTreeUri = intent.getStringExtra(KEY_OUTPUT) ?: return null,
                mode = intent.getStringExtra(KEY_MODE) ?: return null,
                namingStrategy = intent.getStringExtra(KEY_NAMING) ?: return null,
                sizePx = intent.getIntExtra(KEY_SIZE, 168),
                marginFraction = intent.getFloatExtra(KEY_MARGIN, 0.08f),
                workerCount = intent.getIntExtra(KEY_WORKERS, ConverterSettings.defaultWorkerCount()),
                title = intent.getStringExtra(KEY_TITLE) ?: "Arcticons for HyperOS",
                author = intent.getStringExtra(KEY_AUTHOR).orEmpty(),
                description = intent.getStringExtra(KEY_DESCRIPTION).orEmpty(),
                useShizuku = intent.getBooleanExtra(KEY_SHIZUKU, false),
                advancedLimits = intent.getBooleanExtra(KEY_ADVANCED_LIMITS, false),
                cacheLimitBytes = intent.getLongExtra(KEY_CACHE_LIMIT, 512L shl 20)
                    .coerceIn(32L shl 20, 2L shl 30),
            )
        }
    }
}
