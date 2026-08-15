package com.maxlass.studio.pack.domain.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncJobStartResponse(
    val jobId: Long,
    val status: String
)

