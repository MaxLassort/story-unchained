package com.maxlass.studio.pack.domain.dto

/**
 * Raw metadata read from a pack file or directory (ZIP, .pack, FS).
 * Conversion from external library types (e.g. [studio.core.v1.model.metadata.StoryPackMetadata])
 * is done in the adapters.
 *
 * @property uuid Pack unique identifier.
 * @property title Optional title from the pack file.
 * @property description Optional description.
 * @property version Pack format version.
 * @property isNightModeAvailable Whether night mode is available.
 * @property locale Optional locale (archive story.json only).
 * @property ageMin Optional minimum age (archive story.json only).
 * @property ageMax Optional maximum age (archive story.json only).
 * @property durationMs Optional duration in milliseconds (archive story.json only).
 * @property storyCount Optional story count (archive story.json only).
 */
data class RawPackMeta(
    val uuid: String,
    val title: String?,
    val description: String?,
    val version: Short,
    val isNightModeAvailable: Boolean,
    val locale: String? = null,
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    val durationMs: Int? = null,
    val storyCount: Int? = null,
)
