package com.maxlass.studio.device.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class DevicePack(
    val uuid: String,
    val version: Short = 0,
    val sizeInBytes: Long = 0L,
    val title: String? = null,
    val thumbnail: String? = null,
    val locale: String? = null,
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    val durationMs: Int? = null,
    val storyCount: Int? = null,
)
