package io.github.adiker.iconpacktomtz.core.data

import kotlinx.coroutines.flow.Flow

class HistoryRepository(
    private val dao: ConversionHistoryDao,
) {
    fun observeRecent(limit: Int = 50): Flow<List<ConversionHistoryEntity>> =
        dao.observeRecent(limit)

    suspend fun upsert(entity: ConversionHistoryEntity) = dao.upsert(entity)

    suspend fun markRunningInterrupted(now: Long = System.currentTimeMillis()) =
        dao.markRunningInterrupted(now)

    suspend fun clear() = dao.clear()
}
