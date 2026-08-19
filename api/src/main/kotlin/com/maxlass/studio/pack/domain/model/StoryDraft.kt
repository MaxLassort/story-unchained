package com.maxlass.studio.pack.domain.model

import java.nio.file.Path

/**
 * In-memory story draft (single active draft, one story at a time).
 *
 * The structured state lives in memory; the **binary payloads** (audio, images) live in a
 * temp dir (`storageDir/drafts/{id}/`) so a 2-3 h story never saturates the JVM heap.
 * The whole draft directory is removed at startup (crash leftovers), when a new story
 * replaces the current draft, and at finalization. Nothing is persisted in the library/DB.
 *
 * @property thumbnailPath Image for `meta/thumbnail.png` (library display).
 * @property coverPath Square-one cover image ("thumbnail Lunii").
 * @property titleText Text entered by the user instead of an audio title → the TTS
 * engine synthesizes the audio at finalization when [StoryChapterDraft.titleAudioPath] is absent.
 */
data class StoryDraft(
    val id: String,
    val title: String? = null,
    val description: String? = null,
    val thumbnailPath: Path? = null,
    val coverPath: Path? = null,
    val chapters: List<StoryChapterDraft> = emptyList(),
    val createdAtEpochMs: Long,
)

/** One chapter of the draft. Binary payloads are stored on disk, referenced by path. */
data class StoryChapterDraft(
    val id: String,
    val name: String,
    /** Uploaded title audio file (MP3/WAV/OGG…). Mutually exclusive with [titleText]. */
    val titleAudioPath: Path? = null,
    /** Text entered instead of title audio → TTS at finalization. Mutually exclusive with [titleAudioPath]. */
    val titleText: String? = null,
    /** Uploaded chapter narration audio (the story itself, up to hours long). */
    val narrationAudioPath: Path? = null,
    /** Uploaded chapter image (PNG/JPEG, converted from SVG before upload when needed). */
    val imagePath: Path? = null,
    /** Lucide icon slug rendered as the chapter image (fallback when [imagePath] is null). */
    val iconId: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is StoryChapterDraft &&
        other.id == id && other.name == name && other.titleText == titleText &&
        other.titleAudioPath == titleAudioPath && other.narrationAudioPath == narrationAudioPath &&
        other.imagePath == imagePath && other.iconId == iconId

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (titleAudioPath?.hashCode() ?: 0)
        result = 31 * result + (titleText?.hashCode() ?: 0)
        result = 31 * result + (narrationAudioPath?.hashCode() ?: 0)
        result = 31 * result + (imagePath?.hashCode() ?: 0)
        result = 31 * result + (iconId?.hashCode() ?: 0)
        return result
    }
}