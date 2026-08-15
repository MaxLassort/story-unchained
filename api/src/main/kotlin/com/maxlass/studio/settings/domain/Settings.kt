package com.maxlass.studio.settings.domain

import kotlinx.serialization.Serializable

/** User settings (e.g. library path for packs). */
@Serializable
data class Settings(
    /** Directory where the user stores pack files (.zip, FS, RAW). */
    val libraryPath: String,
    /** Preferred Lunii target type when no device is plugged ("RAW", "FS"), or null for auto mode. */
    val targetDeviceType: String? = null
)
