package com.maxlass.studio.pack.domain.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncStatusEvent(
    val status: SyncStatus,
    val totalEntries: Int = 0,
    val processedEntries: Int = 0,
    val synchronizedCount: Int = 0,
    val invalidQueuedCount: Int = 0,
    val failedCount: Int = 0,
    val message: String? = null,
    val startedAtEpochMs: Long = 0L,
    val finishedAtEpochMs: Long = 0L,
    val batchSize: Int = 0,
    val parallelism: Int = 0,
)

@Serializable
enum class SyncStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
}
