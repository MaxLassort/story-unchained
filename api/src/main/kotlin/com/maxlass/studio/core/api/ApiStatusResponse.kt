package com.maxlass.studio.core.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiStatusResponse(
    val ok: Boolean,
    val message: String? = null,
    val error: String? = null
)

