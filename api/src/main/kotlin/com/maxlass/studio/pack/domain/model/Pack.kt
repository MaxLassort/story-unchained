package com.maxlass.studio.pack.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain entity representing a story pack registered in the library.
 *
 * @property id Unique pack identifier (UUID).
 * @property metadata Display and version metadata (title, description, etc.).
 * @property variants Available physical variants of the same pack UUID.
 */
@Serializable
data class Pack(
    val id: String,
    val metadata: PackMetadata,
    val variants: List<PackVariant>
)

/**
 * One physical representation of a pack on disk.
 *
 * @property format Physical format (archive, raw binary, or filesystem).
 * @property storagePath Absolute path to the corresponding file or directory.
 */
@Serializable
data class PackVariant(
    val format: PackFormat,
    val storagePath: String
)

/**
 * Display and version metadata for a [Pack].
 *
 * @property title Optional display title.
 * @property description Optional description.
 * @property thumbnail Optional thumbnail image (URL or data:image/...;base64,...).
 * @property version Pack format version.
 * @property factoryDisabled Whether factory content is disabled.
 * @property nightModeAvailable Whether night mode is available.
 * @property official True if the pack is in the official Lunii catalog (official.json).
 * @property linkedOfficialPackId If set, this pack is a fork of the official pack with this UUID. Must be null when official is true.
 */
@Serializable
data class PackMetadata(
    val title: String?,
    val description: String?,
    val thumbnail: String? = null,
    val version: Short,
    val factoryDisabled: Boolean,
    val nightModeAvailable: Boolean,
    val official: Boolean = false,
    val linkedOfficialPackId: String? = null,
    val locale: String? = null,
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    val durationMs: Int? = null,
    val storyCount: Int? = null,
)

/**
 * Physical storage format of a pack.
 */
@Serializable
enum class PackFormat {
    /** ZIP archive or .pack file. */
    ARCHIVE,
    /** Raw binary format. */
    RAW,
    /** Extracted directory (filesystem). */
    FS,
    /** Unknown or unsupported format. */
    UNKNOWN
}
