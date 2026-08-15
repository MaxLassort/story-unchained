package com.maxlass.studio.pack.domain.dto

import kotlinx.serialization.Serializable

@Serializable
data class PackConversionResponse(
    val ok: Boolean,
    val packId: String,
    val sourceFormat: String,
    val targetFormat: String,
    val outputPath: String? = null,
    val error: String? = null
)
