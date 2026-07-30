package io.github.adiker.iconpacktomtz.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.adiker.iconpacktomtz.conversion.ConversionSessionStore
import io.github.adiker.iconpacktomtz.core.apk.AndroidIconPackAnalyzer
import io.github.adiker.iconpacktomtz.core.data.AppDatabase
import io.github.adiker.iconpacktomtz.core.data.ConversionHistoryDao
import io.github.adiker.iconpacktomtz.core.data.HistoryRepository
import io.github.adiker.iconpacktomtz.core.model.ConversionEngine
import io.github.adiker.iconpacktomtz.core.model.IconPackAnalyzer
import io.github.adiker.iconpacktomtz.core.model.IconRenderer
import io.github.adiker.iconpacktomtz.core.model.InstalledAppsProvider
import io.github.adiker.iconpacktomtz.core.model.MtzBuilder
import io.github.adiker.iconpacktomtz.core.model.RenderCache
import io.github.adiker.iconpacktomtz.core.model.ReportWriter
import io.github.adiker.iconpacktomtz.core.mtz.DefaultMtzBuilder
import io.github.adiker.iconpacktomtz.core.renderer.AndroidIconRenderer
import io.github.adiker.iconpacktomtz.core.renderer.DiskLruRenderCache
import io.github.adiker.iconpacktomtz.core.report.DefaultReportWriter
import io.github.adiker.iconpacktomtz.feature.converter.DefaultConversionEngine
import io.github.adiker.iconpacktomtz.integration.shizuku.ShizukuInstalledAppsProvider
import io.github.adiker.iconpacktomtz.feature.settings.ConverterSettingsRepository
import io.github.adiker.iconpacktomtz.platform.StandardInstalledAppsProvider
import io.github.adiker.iconpacktomtz.saf.SafFileAccess
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "iconpack-to-mtz.db").build()

    @Provides
    fun historyDao(database: AppDatabase): ConversionHistoryDao = database.historyDao()

    @Provides
    @Singleton
    fun historyRepository(dao: ConversionHistoryDao) = HistoryRepository(dao)

    @Provides
    @Singleton
    fun analyzer(
        @ApplicationContext context: Context,
        renderer: IconRenderer,
        renderCache: RenderCache,
    ): IconPackAnalyzer = AndroidIconPackAnalyzer(
        context = context,
        sampleRenderer = renderer,
        sampleCache = renderCache,
    )

    @Provides
    @Singleton
    fun renderer(@ApplicationContext context: Context): IconRenderer =
        AndroidIconRenderer(context)

    @Provides
    @Singleton
    fun renderCache(@ApplicationContext context: Context): RenderCache =
        DiskLruRenderCache(context.cacheDir.resolve("render-cache"))

    @Provides
    @Singleton
    fun mtzBuilder(): MtzBuilder = DefaultMtzBuilder()

    @Provides
    @Singleton
    fun reportWriter(): ReportWriter = DefaultReportWriter()

    @Provides
    @Singleton
    fun conversionEngine(
        analyzer: IconPackAnalyzer,
        renderer: IconRenderer,
        renderCache: RenderCache,
        mtzBuilder: MtzBuilder,
        reportWriter: ReportWriter,
    ): ConversionEngine = DefaultConversionEngine(
        analyzer,
        renderer,
        renderCache,
        mtzBuilder,
        reportWriter,
    )

    @Provides
    @Singleton
    fun installedApps(@ApplicationContext context: Context): InstalledAppsProvider =
        StandardInstalledAppsProvider(context)

    @Provides
    @Singleton
    fun shizukuInstalledApps(@ApplicationContext context: Context) =
        ShizukuInstalledAppsProvider(context)

    @Provides
    @Singleton
    fun saf(@ApplicationContext context: Context) = SafFileAccess(context)

    @Provides
    @Singleton
    fun settingsRepository(@ApplicationContext context: Context) =
        ConverterSettingsRepository(context)

    @Provides
    @Singleton
    fun sessionStore() = ConversionSessionStore()
}
