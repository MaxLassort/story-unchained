package com.maxlass.studio.pack.domain.dto

import kotlinx.serialization.Serializable

/** State of the draft, without the binary payloads (only their sizes). */
@Serializable
data class StoryDraftSummary(
    val id: String,
    val title: String? = null,
    val description: String? = null,
    val hasThumbnail: Boolean = false,
    val thumbnailBytes: Int = 0,
    val hasCover: Boolean = false,
    val coverBytes: Int = 0,
    val chapters: List<StoryChapterDraftSummary> = emptyList(),
)

/** State of one draft chapter, without the binary payloads. */
@Serializable
data class StoryChapterDraftSummary(
    val id: String,
    val name: String,
    val hasTitleAudio: Boolean = false,
    val titleAudioBytes: Int = 0,
    val titleText: String? = null,
    val hasNarrationAudio: Boolean = false,
    val narrationAudioBytes: Int = 0,
    val hasImage: Boolean = false,
    val imageBytes: Int = 0,
    val iconId: String? = null,
)

@Serializable
data class DraftCreatedResponse(val draftId: String)

@Serializable
data class ChapterCreatedResponse(val draftId: String, val chapterId: String)

@Serializable
data class UpdateDraftRequest(
    val title: String? = null,
    val description: String? = null,
)

@Serializable
data class CreateChapterRequest(val name: String)

@Serializable
data class SetTitleTextRequest(val text: String)

@Serializable
data class SetChapterIconRequest(val iconId: String)