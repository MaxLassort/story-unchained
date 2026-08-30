package com.maxlass.studio.pack.domain.dto

import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable

/** State of the draft, without the binary payloads (only their sizes). */
@Serializable
data class StoryDraftSummary(
    val id: String,
    val title: String? = null,
    val description: String? = null,
    val hasThumbnail: Boolean = false,
    val thumbnailBytes: Long = 0,
    val hasCover: Boolean = false,
    val coverBytes: Long = 0,
    val hasTitleAudio: Boolean = false,
    val titleAudioBytes: Long = 0,
    val titleText: String? = null,
    val hasMenuAudio: Boolean = false,
    val menuAudioBytes: Long = 0,
    val menuText: String? = null,
    val chapters: List<StoryChapterDraftSummary> = emptyList(),
)

/** State of one draft chapter, without the binary payloads. */
@Serializable
data class StoryChapterDraftSummary(
    val id: String,
    val name: String,
    val hasTitleAudio: Boolean = false,
    val titleAudioBytes: Long = 0,
    val titleText: String? = null,
    val hasNarrationAudio: Boolean = false,
    val narrationAudioBytes: Long = 0,
    val hasImage: Boolean = false,
    val imageBytes: Long = 0,
    val iconId: String? = null,
)

@Serializable
data class DraftCreatedResponse(val draftId: String)

@Serializable
data class FinalizedPackResponse(val packId: String)

@Serializable
data class ChapterCreatedResponse(val draftId: String, val chapterId: String)

@Serializable
data class UpdateDraftRequest(
    val title: String? = null,
    val description: String? = null,
)

@Serializable
data class CreateChapterRequest(
    @field:NotBlank(message = "Chapter name must not be blank")
    val name: String,
)

/** Scope of a draft node targeted by consolidated endpoints: the pack root or a chapter. */
@Serializable
data class DraftNodeTarget(
    /** `pack` targets the pack root; `chapter` targets a chapter (requires [chapterId]). */
    val scope: String,
    val chapterId: String? = null,
)

/** Common patch model for editing any draft node (pack root or chapter). Only provided fields are applied. */
@Serializable
data class PatchNodeRequest(
    val name: String? = null,
    /** Pack root only: title text → TTS synthesized immediately into the draft. */
    val titleText: String? = null,
    /** Pack root only: menu prompt text → TTS synthesized immediately into the draft. */
    val menuText: String? = null,
    /** Chapter only: Lucide icon slug. */
    val iconId: String? = null,
)