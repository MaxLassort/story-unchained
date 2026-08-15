package com.maxlass.studio.pack.domain.dto

data class UpdatePackMetadataCommand(
    val packId: String,
    val title: String?,
    val description: String?,
    val linkedOfficialPackId: String?,
    val locale: String? = null,
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    val durationMs: Int? = null,
    val storyCount: Int? = null,
    val thumbnailPngBytes: ByteArray? = null,
)
