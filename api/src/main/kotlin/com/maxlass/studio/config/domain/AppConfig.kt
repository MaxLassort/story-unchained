package com.maxlass.studio.config.domain

import kotlinx.serialization.Serializable

/** Application configuration (e.g. version). */
@Serializable
data class AppConfig(
    val version: String = "1.0"
)
