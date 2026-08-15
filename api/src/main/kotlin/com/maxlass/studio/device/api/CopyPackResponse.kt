package com.maxlass.studio.device.api

import kotlinx.serialization.Serializable

@Serializable
data class CopyPackResponse(
    val ok: Boolean,
    val error: String? = null,
    val message: String? = null
)
