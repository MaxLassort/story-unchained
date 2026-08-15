package com.maxlass.studio.pack.domain.dto

data class OfficialMetadataDto(
    val title: String?,
    val description: String?,
    val thumbnailUrl: String?,
    val locale: String? = null,
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    val durationMs: Int? = null,
    val storyCount: Int? = null,
)
