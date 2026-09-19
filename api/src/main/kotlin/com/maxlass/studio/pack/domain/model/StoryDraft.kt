package com.maxlass.studio.pack.domain.model

import kotlinx.serialization.Serializable

/**
 * Persisted state of a story draft, saved as `draft.json` inside its temp
 * dir (`storageDir/drafts/{id}/`). Multiple drafts may coexist on disk.
 *
 * Nothing is kept in memory: every mutation reads and rewrites the JSON, and every
 * binary payload (audio, images) is a plain file on disk.
 *
 * File references are **relative paths** resolved against the draft dir, e.g.
 * `thumbnail.png`, `cover.jpg`, `chapters/{chapterId}/narration.mp3`. The whole drafts
 * directory is cleaned at startup (crash leftovers). Individual drafts are removed on
 * delete or successful finalization. Nothing is persisted in the library/DB until finalize.
 *
 * @property sourcePackId When set, finalization rewrites this existing Unchained pack
 * instead of creating a new one (edit-from-pack flow).
 * @property thumbnailFile Image for `meta/thumbnail.png` (library display).
 * @property coverFile Square-one cover image ("thumbnail Lunii").
 * @property titleAudioFile Uploaded pack title audio (played on the cover). Mutually
 * exclusive with [titleText].
 * @property titleText Text entered instead of pack title audio → TTS at step-save.
 * @property menuAudioFile Uploaded audio of the chapter-selection node (menu question).
 * @property menuText Text of the menu prompt → TTS at step-save.
 */
@Serializable
data class StoryDraftState(
    val id: String,
    val title: String? = null,
    val description: String? = null,
    val sourcePackId: String? = null,
    val thumbnailFile: String? = null,
    val coverFile: String? = null,
    val titleAudioFile: String? = null,
    val titleText: String? = null,
    val menuAudioFile: String? = null,
    val menuText: String? = null,
    val chapters: List<StoryChapterDraftState> = emptyList(),
    val createdAtEpochMs: Long,
)

/** One chapter of the draft. Binary payloads are plain files on disk, referenced relatively. */
@Serializable
data class StoryChapterDraftState(
    val id: String,
    val name: String,
    /** Uploaded title audio file (MP3/WAV/OGG…). Mutually exclusive with [titleText]. */
    val titleAudioFile: String? = null,
    /** Text entered instead of title audio → TTS at finalization. Mutually exclusive with [titleAudioFile]. */
    val titleText: String? = null,
    /** Uploaded chapter narration audio (the story itself, up to hours long). */
    val narrationAudioFile: String? = null,
    /** Uploaded chapter image (PNG/JPEG, converted from SVG before upload when needed). */
    val imageFile: String? = null,
    /** Lucide icon slug rendered as the chapter image (fallback when [imageFile] is null). */
    val iconId: String? = null,
)