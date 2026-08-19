package com.maxlass.studio.pack.service

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.pack.domain.model.StoryChapterDraft
import com.maxlass.studio.pack.domain.model.StoryDraft
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Single in-memory story draft store (one story at a time).
 *
 * The structured state lives in memory; binary payloads (audio, images) live on disk under
 * `storageDir/drafts/{draftId}/` so a multi-hour story never saturates the JVM heap.
 * The whole drafts directory is **cleaned at every startup** (crash leftovers), and the
 * draft directory is removed when the draft is replaced or cleared. Nothing is persisted
 * in the library/DB: the draft is lost when the app quits, exactly as intended.
 */
@Service
class StoryDraftStore(
    private val studioProperties: StudioProperties,
) {

    @Volatile
    private var current: StoryDraft? = null

    private val lock = Any()

    /** Removes all leftover draft binaries from previous runs. */
    @PostConstruct
    fun cleanAtStartup() {
        studioProperties.draftsDir.toFile().deleteRecursively()
    }

    /** Replaces any existing draft (its temp dir is removed) with a new empty one. */
    fun create(): StoryDraft = synchronized(lock) {
        current?.let { deleteDraftFiles(it.id) }
        StoryDraft(
            id = UUID.randomUUID().toString(),
            createdAtEpochMs = System.currentTimeMillis(),
        ).also { current = it }
    }

    /** The draft with the given id, or null (unknown or already replaced/cleared). */
    fun get(id: String): StoryDraft? = current?.takeIf { it.id == id }

    /** Removes the current draft and its temp dir. */
    fun clear(id: String): Boolean = synchronized(lock) {
        if (current?.id == id) {
            deleteDraftFiles(id)
            current = null
            true
        } else {
            false
        }
    }

    fun updateMetadata(id: String, title: String?, description: String?): StoryDraft? =
        synchronized(lock) {
            mutateDraft(id) { it.copy(title = title, description = description) }
        }

    fun addChapter(id: String, name: String): StoryChapterDraft? = synchronized(lock) {
        val draft = current?.takeIf { it.id == id } ?: return@synchronized null
        val chapter = StoryChapterDraft(id = UUID.randomUUID().toString(), name = name)
        current = draft.copy(chapters = draft.chapters + chapter)
        chapter
    }

    fun removeChapter(id: String, chapterId: String): StoryDraft? = synchronized(lock) {
        val draft = current?.takeIf { it.id == id } ?: return@synchronized null
        draft.chapters.find { it.id == chapterId }?.let { deleteChapterFiles(id, chapterId) }
        current = draft.copy(chapters = draft.chapters.filterNot { it.id == chapterId })
        current
    }

    /** Uploaded thumbnail for `meta/thumbnail.png` (PNG/JPEG). */
    fun setThumbnail(id: String, bytes: ByteArray, contentType: String): StoryDraft? =
        synchronized(lock) {
            mutateDraft(id) {
                it.copy(thumbnailPath = writeDraftBinary(id, "thumbnail", bytes, contentType))
            }
        }

    /** Uploaded square-one cover image, "thumbnail Lunii" (PNG/JPEG). */
    fun setCover(id: String, bytes: ByteArray, contentType: String): StoryDraft? =
        synchronized(lock) {
            mutateDraft(id) {
                it.copy(coverPath = writeDraftBinary(id, "cover", bytes, contentType))
            }
        }

    /** Uploaded title audio replaces any TTS text for the chapter title. */
    fun setTitleAudio(id: String, chapterId: String, bytes: ByteArray, contentType: String): StoryDraft? =
        synchronized(lock) {
            mutateChapter(id, chapterId) { chapter ->
                chapter.copy(
                    titleAudioPath = writeChapterBinary(id, chapterId, "title-audio", bytes, contentType),
                    titleText = null,
                )
            }
        }

    /** TTS text replaces any uploaded title audio for the chapter (old file removed). */
    fun setTitleText(id: String, chapterId: String, text: String): StoryDraft? =
        synchronized(lock) {
            mutateChapter(id, chapterId) { chapter ->
                chapter.titleAudioPath?.toFile()?.delete()
                chapter.copy(titleText = text, titleAudioPath = null)
            }
        }

    /** Uploaded chapter narration audio (the story itself, up to hours long). */
    fun setNarrationAudio(id: String, chapterId: String, bytes: ByteArray, contentType: String): StoryDraft? =
        synchronized(lock) {
            mutateChapter(id, chapterId) { chapter ->
                chapter.copy(
                    narrationAudioPath = writeChapterBinary(id, chapterId, "narration", bytes, contentType),
                )
            }
        }

    /** Uploaded chapter image (PNG/JPEG); keeps the icon fallback. */
    fun setChapterImage(id: String, chapterId: String, bytes: ByteArray, contentType: String): StoryDraft? =
        synchronized(lock) {
            mutateChapter(id, chapterId) { chapter ->
                chapter.copy(
                    imagePath = writeChapterBinary(id, chapterId, "image", bytes, contentType),
                )
            }
        }

    /** Lucide icon slug as chapter image fallback (when no uploaded image). */
    fun setChapterIcon(id: String, chapterId: String, iconId: String): StoryDraft? =
        synchronized(lock) {
            mutateChapter(id, chapterId) { it.copy(iconId = iconId) }
        }

    private fun draftDir(id: String): Path = studioProperties.draftsDir.resolve(id)

    private fun chapterDir(id: String, chapterId: String): Path =
        draftDir(id).resolve("chapters").resolve(chapterId)

    private fun writeDraftBinary(id: String, name: String, bytes: ByteArray, contentType: String): Path {
        val dir = draftDir(id)
        Files.createDirectories(dir)
        return Files.write(dir.resolve("$name.${extensionOf(contentType)}"), bytes)
    }

    private fun writeChapterBinary(
        id: String,
        chapterId: String,
        name: String,
        bytes: ByteArray,
        contentType: String,
    ): Path {
        val dir = chapterDir(id, chapterId)
        Files.createDirectories(dir)
        return Files.write(dir.resolve("$name.${extensionOf(contentType)}"), bytes)
    }

    /** Keeps the file type discoverable at finalization (PNG vs JPEG, MP3 vs WAV…). */
    private fun extensionOf(contentType: String): String = when (contentType.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        else -> "bin"
    }

    private fun deleteChapterFiles(id: String, chapterId: String) {
        chapterDir(id, chapterId).toFile().deleteRecursively()
    }

    private fun deleteDraftFiles(id: String) {
        draftDir(id).toFile().deleteRecursively()
    }

    private inline fun mutateDraft(id: String, transform: (StoryDraft) -> StoryDraft): StoryDraft? {
        val draft = current?.takeIf { it.id == id } ?: return null
        val updated = transform(draft)
        current = updated
        return updated
    }

    private inline fun mutateChapter(
        id: String,
        chapterId: String,
        transform: (StoryChapterDraft) -> StoryChapterDraft,
    ): StoryDraft? {
        val draft = current?.takeIf { it.id == id } ?: return null
        val chapter = draft.chapters.find { it.id == chapterId } ?: return null
        val updated = draft.copy(
            chapters = draft.chapters.map { if (it.id == chapterId) transform(chapter) else it },
        )
        current = updated
        return updated
    }
}