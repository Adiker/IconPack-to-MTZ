package io.github.adiker.iconpacktomtz.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ConversionHistoryEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(HistoryTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): ConversionHistoryDao
}
