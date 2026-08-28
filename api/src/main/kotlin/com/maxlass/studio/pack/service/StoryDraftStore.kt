package com.maxlass.studio.pack.service

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.pack.domain.model.StoryChapterDraftState
import com.maxlass.studio.pack.domain.model.StoryDraftState
import jakarta.annotation.PostConstruct
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Single story draft store backed entirely by the temp folder (one story at a time).
 *
 * Nothing is kept in memory: the structured state lives in `drafts/{id}/draft.json` and
 * every binary payload (audio, images) is a plain file under `drafts/{id}/`. Every
 * mutation reads the JSON, applies the change and rewrites it, so a multi-hour story never
 * saturates the JVM heap and survives nothing but the current run — the whole drafts
 * directory is **cleaned at every startup** (crash leftovers), and the draft directory is
 * removed when the draft is replaced or cleared.
 */
@Service
class StoryDraftStore(
    private val studioProperties: StudioProperties,
) {

    companion object {
        private val logger = LoggerFactory.getLogger(StoryDraftStore::class.java)
    }

    private val lock = Any()

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Removes all leftover draft files from previous runs. */
    @PostConstruct
    fun cleanAtStartup() {
        logger.info("Cleaning story draft temp dir {}", studioProperties.draftsDir)
        studioProperties.draftsDir.toFile().deleteRecursively()
    }

    /** Replaces any existing draft (its temp dir is removed) with a new empty one. */
    fun create(): StoryDraftState = synchronized(lock) {
        studioProperties.draftsDir.toFile().deleteRecursively()
        val draft = StoryDraftState(
            id = UUID.randomUUID().toString(),
            createdAtEpochMs = System.currentTimeMillis(),
        )
        logger.info("Creating story draft {} (replacing any previous one)", draft.id)
        writeState(draft)
        draft
    }

    /** The draft with the given id, or null (unknown or already replaced/cleared). */
    fun get(id: String): StoryDraftState? = synchronized(lock) {
        readState(id)
    }

    /** Removes the current draft and its temp dir. */
    fun clear(id: String): Boolean = synchronized(lock) {
        if (readState(id) == null) {
            false
        } else {
            draftDir(id).toFile().deleteRecursively()
            true
        }
    }

    fun updateMetadata(id: String, title: String?, description: String?): StoryDraftState? =
        synchronized(lock) {
            mutateDraft(id) { it.copy(title = title, description = description) }
        }

    fun addChapter(id: String, name: String): StoryChapterDraftState? = synchronized(lock) {
        val draft = readState(id) ?: return@synchronized null
        val chapter = StoryChapterDraftState(id = UUID.randomUUID().toString(), name = name)
        writeState(draft.copy(chapters = draft.chapters + chapter))
        chapter
    }

    fun removeChapter(id: String, chapterId: String): StoryDraftState? = synchronized(lock) {
        val draft = readState(id) ?: return@synchronized null
        if (draft.chapters.none { it.id == chapterId }) return@synchronized null
        draftDir(id).resolve("chapters").resolve(chapterId).toFile().deleteRecursively()
        val updated = draft.copy(chapters = draft.chapters.filterNot { it.id == chapterId })
        writeState(updated)
        updated
    }

    /** Uploaded thumbnail for `meta/thumbnail.png` (PNG/JPEG). */
    fun setThumbnail(id: String, bytes: ByteArray, contentType: String): StoryDraftState? =
        synchronized(lock) {
            mutateDraft(id) {
                it.copy(thumbnailFile = writeDraftBinary(id, "thumbnail", bytes, contentType))
            }
        }

    /** Uploaded square-one cover image, "thumbnail Lunii" (PNG/JPEG). */
    fun setCover(id: String, bytes: ByteArray, contentType: String): StoryDraftState? =
        synchronized(lock) {
            mutateDraft(id) {
                it.copy(coverFile = writeDraftBinary(id, "cover", bytes, contentType))
            }
        }

    /** Uploaded pack title audio (cover audio). Replaces any TTS text for the pack title. */
    fun setTitleAudio(id: String, bytes: ByteArray, contentType: String): StoryDraftState? =
        synchronized(lock) {
            mutateDraft(id) {
                it.copy(
                    titleAudioFile = writeDraftBinary(id, "title-audio", bytes, contentType),
                    titleText = null,
                )
            }
        }

    /** Pack title TTS text (cover audio at finalization). Replaces any uploaded audio. */
    fun setTitleText(id: String, text: String): StoryDraftState? =
        synchronized(lock) {
            mutateDraft(id) { draft ->
                deleteBinary(id, draft.titleAudioFile)
                draft.copy(titleText = text, titleAudioFile = null)
            }
        }

    /** Uploaded title audio replaces any TTS text for the chapter title. */
    fun setTitleAudio(id: String, chapterId: String, bytes: ByteArray, contentType: String): StoryDraftState? =
        synchronized(lock) {
            mutateChapter(id, chapterId) { chapter ->
                chapter.copy(
                    titleAudioFile = writeChapterBinary(id, chapterId, "title-audio", bytes, contentType),
                    titleText = null,
                )
            }
        }

    /** TTS text replaces any uploaded title audio for the chapter (old file removed). */
    fun setTitleText(id: String, chapterId: String, text: String): StoryDraftState? =
        synchronized(lock) {
            mutateChapter(id, chapterId) { chapter ->
                deleteBinary(id, chapter.titleAudioFile)
                chapter.copy(titleText = text, titleAudioFile = null)
            }
        }

    /** Uploaded chapter narration audio (the story itself, up to hours long). */
    fun setNarrationAudio(id: String, chapterId: String, bytes: ByteArray, contentType: String): StoryDraftState? =
        synchronized(lock) {
            mutateChapter(id, chapterId) { chapter ->
                chapter.copy(
                    narrationAudioFile = writeChapterBinary(id, chapterId, "narration", bytes, contentType),
                )
            }
        }

    /** Uploaded chapter image (PNG/JPEG); keeps the icon fallback. */
    fun setChapterImage(id: String, chapterId: String, bytes: ByteArray, contentType: String): StoryDraftState? =
        synchronized(lock) {
            mutateChapter(id, chapterId) { chapter ->
                chapter.copy(
                    imageFile = writeChapterBinary(id, chapterId, "image", bytes, contentType),
                )
            }
        }

    /** Lucide icon slug as chapter image fallback (when no uploaded image). */
    fun setChapterIcon(id: String, chapterId: String, iconId: String): StoryDraftState? =
        synchronized(lock) {
            mutateChapter(id, chapterId) { it.copy(iconId = iconId) }
        }

    /** Root folder of the draft (state JSON + binaries). */
    fun draftDir(id: String): Path = studioProperties.draftsDir.resolve(id)

    private fun draftFile(id: String): Path = draftDir(id).resolve("draft.json")

    private fun writeState(draft: StoryDraftState) {
        Files.createDirectories(draftDir(draft.id))
        Files.writeString(draftFile(draft.id), json.encodeToString(StoryDraftState.serializer(), draft))
    }

    private fun readState(id: String): StoryDraftState? {
        val file = draftFile(id)
        if (!Files.exists(file)) return null
        return try {
            json.decodeFromString(StoryDraftState.serializer(), Files.readString(file))
        } catch (e: Exception) {
            logger.warn("Ignoring corrupted draft state {}: {}", file, e.message)
            null
        }
    }

    private fun writeDraftBinary(id: String, name: String, bytes: ByteArray, contentType: String): String {
        val fileName = "$name.${extensionOf(contentType)}"
        Files.createDirectories(draftDir(id))
        Files.write(draftDir(id).resolve(fileName), bytes)
        return fileName
    }

    private fun writeChapterBinary(
        id: String,
        chapterId: String,
        name: String,
        bytes: ByteArray,
        contentType: String,
    ): String {
        val relative = "chapters/$chapterId/$name.${extensionOf(contentType)}"
        Files.createDirectories(draftDir(id).resolve("chapters").resolve(chapterId))
        Files.write(draftDir(id).resolve(relative), bytes)
        return relative
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

    private fun deleteBinary(id: String, relativeFile: String?) {
        relativeFile?.let { Files.deleteIfExists(draftDir(id).resolve(it)) }
    }

    private fun mutateDraft(id: String, transform: (StoryDraftState) -> StoryDraftState): StoryDraftState? {
        val draft = readState(id) ?: return null
        val updated = transform(draft)
        writeState(updated)
        return updated
    }

    private fun mutateChapter(
        id: String,
        chapterId: String,
        transform: (StoryChapterDraftState) -> StoryChapterDraftState,
    ): StoryDraftState? {
        val draft = readState(id) ?: return null
        val chapter = draft.chapters.find { it.id == chapterId } ?: return null
        val updated = draft.copy(
            chapters = draft.chapters.map { if (it.id == chapterId) transform(chapter) else it },
        )
        writeState(updated)
        return updated
    }
}