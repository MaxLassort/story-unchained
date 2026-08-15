package com.maxlass.studio.pack.format.model

/**
 * Lightweight metadata extracted from a story pack without loading its assets.
 *
 * @property format Pack format ("archive", "raw", "fs").
 * @property uuid Pack UUID.
 * @property version Pack format version.
 * @property title Optional title.
 * @property description Optional description.
 * @property thumbnail Optional thumbnail bytes (archive packs).
 * @property sectorSize Optional sector size (raw packs on device).
 * @property nightModeAvailable Whether night mode is available.
 */
class StoryPackMetadata(
    var format: String?,
    var uuid: String? = null,
    var version: Short = 0,
    var title: String? = null,
    var description: String? = null,
    var thumbnail: ByteArray? = null,
    var sectorSize: Int? = null,
    var nightModeAvailable: Boolean = false,
)
