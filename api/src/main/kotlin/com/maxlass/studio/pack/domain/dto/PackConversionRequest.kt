package com.maxlass.studio.pack.domain.dto

import com.maxlass.studio.pack.domain.model.PackFormat
import kotlinx.serialization.Serializable

@Serializable
data class PackConversionRequest(
    val sourceFormat: PackFormat,
    val targetFormat: PackFormat
)
