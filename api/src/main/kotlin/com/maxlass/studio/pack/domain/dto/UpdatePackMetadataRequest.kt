package com.maxlass.studio.pack.domain.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpdatePackMetadataRequest(
    val title: String? = null,
    val description: String? = null,
    val linkedOfficialPackId: String? = null,
    val locale: String? = null,
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    val durationMs: Int? = null,
    val storyCount: Int? = null,
)
