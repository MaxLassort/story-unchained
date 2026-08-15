package com.maxlass.studio.pack.domain.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncJobStatusResponse(
    val jobId: Long,
    val status: String,
    val totalEntries: Int,
    val processedEntries: Int,
    val synchronizedCount: Int,
    val invalidQueuedCount: Int,
    val failedCount: Int,
    val message: String?,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val batchSize: Int,
    val parallelism: Int
)

