package io.github.adiker.iconpacktomtz.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.adiker.iconpacktomtz.core.model.ConversionMode
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.ConverterSettings
import io.github.adiker.iconpacktomtz.core.model.NamingStrategy
import io.github.adiker.iconpacktomtz.core.model.RenderConfig
import io.github.adiker.iconpacktomtz.core.model.ThemeMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.converterDataStore by preferencesDataStore("converter_settings")

data class PersistedConverterPreferences(
    val settings: ConverterSettings = ConverterSettings(),
    val metadata: ThemeMetadata = ThemeMetadata(),
)

class ConverterSettingsRepository(
    context: Context,
) {
    private val dataStore = context.applicationContext.converterDataStore

    val preferences: Flow<PersistedConverterPreferences> = dataStore.data.map { values ->
        val mode = values[MODE]
            ?.let { runCatching { ConversionMode.valueOf(it) }.getOrNull() }
            ?: ConversionMode.FULL
        val naming = values[NAMING]
            ?.let { runCatching { NamingStrategy.valueOf(it) }.getOrNull() }
            ?: NamingStrategy.OPTIMIZED
        PersistedConverterPreferences(
            settings = ConverterSettings(
                mode = mode,
                namingStrategy = naming,
                render = RenderConfig(
                    sizePx = (values[SIZE] ?: 168).coerceIn(48, 512),
                    marginFraction = (values[MARGIN] ?: 0.08f).coerceIn(0f, 0.4f),
                ),
                workerCount = (values[WORKERS] ?: ConverterSettings.defaultWorkerCount())
                    .coerceIn(1, 4),
                limits = if (values[ADVANCED_LIMITS] == true) {
                    ArchiveLimits.advanced()
                } else {
                    ArchiveLimits()
                },
                cacheLimitBytes = (values[CACHE_LIMIT_BYTES] ?: (512L shl 20))
                    .coerceIn(32L shl 20, 2L shl 30),
            ),
            metadata = ThemeMetadata(
                title = values[TITLE] ?: "Arcticons for HyperOS",
                author = values[AUTHOR].orEmpty(),
                designer = values[AUTHOR].orEmpty(),
                description = values[DESCRIPTION].orEmpty(),
            ),
        )
    }

    suspend fun save(settings: ConverterSettings, metadata: ThemeMetadata) {
        dataStore.edit { values ->
            values[MODE] = settings.mode.name
            values[NAMING] = settings.namingStrategy.name
            values[SIZE] = settings.render.sizePx
            values[MARGIN] = settings.render.marginFraction
            values[WORKERS] = settings.workerCount
            values[ADVANCED_LIMITS] = settings.limits != ArchiveLimits()
            values[CACHE_LIMIT_BYTES] = settings.cacheLimitBytes
            values[TITLE] = metadata.title
            values[AUTHOR] = metadata.author
            values[DESCRIPTION] = metadata.description
        }
    }

    private companion object {
        val MODE = stringPreferencesKey("mode")
        val NAMING = stringPreferencesKey("naming")
        val SIZE = intPreferencesKey("size")
        val MARGIN = floatPreferencesKey("margin")
        val WORKERS = intPreferencesKey("workers")
        val ADVANCED_LIMITS = booleanPreferencesKey("advanced_limits")
        val CACHE_LIMIT_BYTES = longPreferencesKey("cache_limit_bytes")
        val TITLE = stringPreferencesKey("title")
        val AUTHOR = stringPreferencesKey("author")
        val DESCRIPTION = stringPreferencesKey("description")
    }
}
