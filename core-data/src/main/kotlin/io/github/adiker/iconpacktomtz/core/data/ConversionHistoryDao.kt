package io.github.adiker.iconpacktomtz.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversionHistoryDao {
    @Query("SELECT * FROM conversion_history ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ConversionHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversionHistoryEntity)

    @Query(
        """
        UPDATE conversion_history
        SET status = 'INTERRUPTED',
            completedAtEpochMillis = :now,
            errorSummary = 'Conversion was interrupted before completion.'
        WHERE status = 'RUNNING'
        """,
    )
    suspend fun markRunningInterrupted(now: Long)

    @Query("DELETE FROM conversion_history")
    suspend fun clear()
}
