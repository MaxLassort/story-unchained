package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.PACK_EXT_ZIP
import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.domain.model.PackMetadata
import com.maxlass.studio.pack.domain.model.PackVariant
import com.maxlass.studio.pack.domain.model.StoryDraftState
import com.maxlass.studio.pack.format.model.ActionNode
import com.maxlass.studio.pack.format.model.AudioAsset
import com.maxlass.studio.pack.format.model.ControlSettings
import com.maxlass.studio.pack.format.model.EnrichedNodeMetadata
import com.maxlass.studio.pack.format.model.EnrichedNodePosition
import com.maxlass.studio.pack.format.model.EnrichedNodeType
import com.maxlass.studio.pack.format.model.EnrichedPackMetadata
import com.maxlass.studio.pack.format.model.ImageAsset
import com.maxlass.studio.pack.format.model.StageNode
import com.maxlass.studio.pack.format.model.StoryPack
import com.maxlass.studio.pack.format.model.Transition
import com.maxlass.studio.pack.format.utils.AudioConversion
import com.maxlass.studio.pack.format.utils.ChapterImageGenerator
import com.maxlass.studio.pack.format.utils.SvgIconRenderer
import com.maxlass.studio.pack.format.writer.ArchiveStoryPackWriter
import com.maxlass.studio.pack.port.external.PackFileMetadata
import com.maxlass.studio.pack.port.external.UpdatePackFileMetadataPort
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import com.maxlass.studio.settings.service.SettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import java.io.ByteArrayInputStream

/** Thrown when a draft is missing required fields at finalization (mapped to HTTP 409). */
class DraftIncompleteException(message: String) : RuntimeException(message)

/**
 * Finalizes a story draft into a playable Lunii STUdio zip: validates the draft, synthesizes
 * missing title audio with TTS, builds the linear graph (cover squareOne → action nodes →
 * story chapters), writes the archive with the thumbnail injected, stores it in the library
 * and indexes it in the DB. The draft is purged on success.
 */
@Service
class CreateStoryUseCase(
    private val draftStore: StoryDraftStore,
    private val ttsEngine: TtsEngine,
    private val iconCatalog: ChapterIconCatalogService,
    private val updatePackMetadata: UpdatePackFileMetadataPort,
    private val settingsService: SettingsService,
    private val packRepository: PackRepositoryPort,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(CreateStoryUseCase::class.java)
        private val DEFAULT_CONTROLS = ControlSettings(
            wheelEnabled = false,
            okEnabled = true,
            homeEnabled = true,
            pauseEnabled = true,
            autoJumpEnabled = false,
        )
    }

    private val archiveWriter = ArchiveStoryPackWriter()

    /** Validates the draft and builds the pack, then writes + indexes it. Returns the pack UUID. */
    suspend fun finalize(draftId: String): String {
        val draft = draftStore.get(draftId)
            ?: throw NoSuchElementException("Draft not found: $draftId")
        validate(draft)

        val packUuid = UUID.randomUUID().toString()
        val title = draft.title!!.trim()
        val description = draft.description

        val coverImage = readImageBytes(draftId, draft.coverFile!!)
        val coverAudio = draft.titleAudioFile?.let { draftStore.readBinary(draftId, it) }
            ?: ttsEngine.synthesize(draft.titleText?.trim()?.takeIf { it.isNotEmpty() } ?: title)

        val chapters = draft.chapters.mapIndexed { index, chapter ->
            buildChapterNode(draftId, chapter, chaptersIndex = index + 1)
        }

        // Linear graph: cover (squareOne) -> action #1 -> chapter 1 -> action #2 -> ... -> last chapter.
        // The last chapter loops back to the cover: every stage node MUST have a valid OK
        // transition, otherwise the Lunii shows an "error card" when the story reaches it.
        val actionNodes = chapters.indices.map { index ->
            ActionNode(
                options = listOf(chapters[index]),
                enriched = EnrichedNodeMetadata(
                    name = null,
                    type = EnrichedNodeType.ACTION,
                    groupId = null,
                    position = EnrichedNodePosition((index * 8).toShort(), 64),
                ),
            )
        }.toMutableList()

        val coverNode = StageNode(
            uuid = UUID.randomUUID().toString(),
            image = ImageAsset(mimeTypeOf(draft.coverFile!!), coverImage, null),
            audio = AudioAsset("audio/mpeg", coverAudio, null),
            okTransition = null,
            homeTransition = null,
            controlSettings = DEFAULT_CONTROLS,
            enriched = EnrichedNodeMetadata(
                name = title,
                type = EnrichedNodeType.COVER,
                groupId = null,
                position = EnrichedNodePosition(0, 0),
            ),
        )

        // cover -> action #1 ; chapter k -> action #(k+1) ; last chapter loops back to the cover
        // through a dedicated end action so its OK transition is never undefined.
        chapters.forEachIndexed { index, node ->
            if (index == 0) {
                coverNode.okTransition = Transition(actionNodes[0], 0)
            } else {
                chapters[index - 1].okTransition = Transition(actionNodes[index], 0)
            }
        }
        if (chapters.isNotEmpty()) {
            val endAction = ActionNode(
                options = listOf(coverNode),
                enriched = EnrichedNodeMetadata(
                    name = null,
                    type = EnrichedNodeType.ACTION,
                    groupId = null,
                    position = EnrichedNodePosition((chapters.size * 8).toShort(), 64),
                ),
            )
            chapters.last().okTransition = Transition(endAction, 0)
            actionNodes.add(endAction)
        }

        val pack = StoryPack(
            uuid = packUuid,
            factoryDisabled = false,
            version = 1,
            stageNodes = listOf(coverNode) + chapters,
            enriched = EnrichedPackMetadata(title, description),
            nightModeAvailable = true,
        )

        val thumbnailPng = draft.thumbnailFile
            ?.let { draftStore.readBinary(draftId, it) }
            ?.let(::toPng)

        val libraryDir = Path.of(settingsService.getLibraryPath()).also { Files.createDirectories(it) }
        val destination = libraryDir.resolve("$packUuid.$PACK_EXT_ZIP")

        val tmp = Files.createTempFile("studio_kmp_finalize_", ".$PACK_EXT_ZIP")
        try {
            FileOutputStream(tmp.toFile()).use { archiveWriter.write(pack, it) }
            if (thumbnailPng != null) {
                updatePackMetadata.updateArchiveMetadata(tmp, PackFileMetadata(thumbnailPngBytes = thumbnailPng))
            }
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }

        packRepository.savePack(
            Pack(
                id = packUuid,
                metadata = PackMetadata(
                    title = title,
                    description = description,
                    thumbnail = thumbnailPng?.let { "data:image/png;base64,${Base64.getEncoder().encodeToString(it)}" },
                    version = 1,
                    factoryDisabled = false,
                    nightModeAvailable = true,
                    official = false,
                ),
                variants = listOf(PackVariant(format = PackFormat.ARCHIVE, storagePath = destination.toString())),
            )
        )

        draftStore.clear(draftId)
        logger.info("Finalized story draft {} -> pack {} at {}", draftId, packUuid, destination)
        return packUuid
    }

    /** Validates that the draft is complete enough to be packaged (409 otherwise). */
    private fun validate(draft: StoryDraftState) {
        val problems = mutableListOf<String>()
        if (draft.title.isNullOrBlank()) problems += "title"
        if (draft.thumbnailFile == null) problems += "thumbnail"
        if (draft.coverFile == null) problems += "cover"
        if (draft.chapters.isEmpty()) {
            problems += "chapters"
        } else {
            draft.chapters.forEachIndexed { i, ch ->
                if (ch.name.isBlank()) problems += "chapters[$i].name"
                if (ch.narrationAudioFile == null) problems += "chapters[$i].narration"
            }
        }
        if (problems.isNotEmpty()) {
            throw DraftIncompleteException("Draft is incomplete: missing ${problems.joinToString(", ")}")
        }
    }

    /** Builds one story chapter stage node, with its image and concatenated (title + narration) audio. */
    private suspend fun buildChapterNode(
        draftId: String,
        chapter: com.maxlass.studio.pack.domain.model.StoryChapterDraftState,
        chaptersIndex: Int,
    ): StageNode {
        val imageBytes = chapter.imageFile?.let { readImageBytes(draftId, it) }
            ?: chapter.iconId?.let { renderIcon(it) }
            ?: ChapterImageGenerator.generate(chaptersIndex)

        val titleAudio = chapter.titleAudioFile?.let { draftStore.readBinary(draftId, it) }
            ?: ttsEngine.synthesize(chapter.titleText?.trim()?.takeIf { it.isNotEmpty() } ?: chapter.name)
        val narration = chapter.narrationAudioFile?.let { draftStore.readBinary(draftId, it) }
            ?: throw DraftIncompleteException("Chapter '${chapter.name}' has no narration audio")
        val audio = concatMp3(titleAudio, narration)

        return StageNode(
            uuid = UUID.randomUUID().toString(),
            image = ImageAsset(
                mimeType = chapter.imageFile?.let { mimeTypeOf(it) } ?: "image/png",
                rawData = imageBytes,
                name = null,
            ),
            audio = AudioAsset("audio/mpeg", audio, null),
            okTransition = null,
            homeTransition = null,
            controlSettings = DEFAULT_CONTROLS,
            enriched = EnrichedNodeMetadata(
                name = chapter.name,
                type = EnrichedNodeType.STORY,
                groupId = null,
                position = EnrichedNodePosition(0, (chaptersIndex * 8).toShort()),
            ),
        )
    }

    private suspend fun renderIcon(iconId: String): ByteArray {
        val svg = iconCatalog.loadIcon(iconId)
            ?: throw DraftIncompleteException("Unknown icon: $iconId")
        return SvgIconRenderer.render(svg)
    }

    private fun readImageBytes(draftId: String, relativePath: String): ByteArray =
        draftStore.readBinary(draftId, relativePath)
            ?: throw DraftIncompleteException("Image file missing: $relativePath")

    private fun mimeTypeOf(relativePath: String): String = when {
        relativePath.endsWith(".png") -> "image/png"
        relativePath.endsWith(".jpg") || relativePath.endsWith(".jpeg") -> "image/jpeg"
        else -> "image/png"
    }

    /** Re-encodes any image bytes to PNG (the archive thumbnail is always `meta/thumbnail.png`). */
    private fun toPng(bytes: ByteArray): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
        val output = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", output)) return bytes
        return output.toByteArray()
    }

    /** Concatenates multiple audio payloads (any format) into a single mono 44.1 kHz MP3. */
    private fun concatMp3(vararg audios: ByteArray): ByteArray {
        if (audios.size == 1) return AudioConversion.anyToMp3(audios[0])
        val waves = audios.map { AudioConversion.anyToWave(it) }
        val pcmLength = waves.sumOf { it.size - 44 }
        val pcm = ByteArray(pcmLength)
        var offset = 0
        for (wave in waves) {
            wave.copyInto(pcm, offset, 44, wave.size)
            offset += wave.size - 44
        }
        val format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            AudioConversion.WAVE_SAMPLE_RATE,
            AudioConversion.BITSIZE,
            AudioConversion.CHANNELS,
            AudioConversion.CHANNELS * 2,
            AudioConversion.WAVE_SAMPLE_RATE,
            false,
        )
        val input = AudioInputStream(ByteArrayInputStream(pcm), format, (pcm.size / 2).toLong())
        val output = ByteArrayOutputStream()
        AudioSystem.write(input, AudioFileFormat.Type.WAVE, output)
        return AudioConversion.anyToMp3(output.toByteArray())
    }
}