package io.github.adiker.iconpacktomtz.core.data

import androidx.room.TypeConverter
import io.github.adiker.iconpacktomtz.core.model.ConversionMode
import io.github.adiker.iconpacktomtz.core.model.ConversionStatus
import io.github.adiker.iconpacktomtz.core.model.NamingStrategy

class HistoryTypeConverters {
    @TypeConverter
    fun conversionMode(value: ConversionMode): String = value.name

    @TypeConverter
    fun conversionMode(value: String): ConversionMode = ConversionMode.valueOf(value)

    @TypeConverter
    fun namingStrategy(value: NamingStrategy): String = value.name

    @TypeConverter
    fun namingStrategy(value: String): NamingStrategy = NamingStrategy.valueOf(value)

    @TypeConverter
    fun conversionStatus(value: ConversionStatus): String = value.name

    @TypeConverter
    fun conversionStatus(value: String): ConversionStatus = ConversionStatus.valueOf(value)
}
