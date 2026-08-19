package com.maxlass.studio.pack.domain.dto

import kotlinx.serialization.Serializable

/** A bundled Lucide icon available as chapter image fallback. */
@Serializable
data class ChapterIconDto(
    val id: String,
    val name: String,
)

/** List of bundled chapter icons. */
@Serializable
data class ChapterIconsResponse(
    val icons: List<ChapterIconDto>,
)