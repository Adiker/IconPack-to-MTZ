package io.github.adiker.iconpacktomtz.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.adiker.iconpacktomtz.core.model.ConversionMode
import io.github.adiker.iconpacktomtz.core.model.ConversionStatus
import io.github.adiker.iconpacktomtz.core.model.NamingStrategy

@Entity(tableName = "conversion_history")
data class ConversionHistoryEntity(
    @PrimaryKey val operationId: String,
    val sourceDisplayName: String,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val mode: ConversionMode,
    val namingStrategy: NamingStrategy,
    val iconSizePx: Int,
    val marginFraction: Float,
    val workerCount: Int,
    val iconCount: Int,
    val durationMillis: Long,
    val outputBytes: Long,
    val status: ConversionStatus,
    val outputUri: String?,
    val jsonReportUri: String?,
    val textReportUri: String?,
    val errorSummary: String?,
)
