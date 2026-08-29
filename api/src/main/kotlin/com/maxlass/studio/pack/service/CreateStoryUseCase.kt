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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        /** Cover page: OK opens the menu (reference pack keeps wheel+ok on the cover). */
        private val COVER_CONTROLS = ControlSettings(
            wheelEnabled = true,
            okEnabled = true,
            homeEnabled = false,
            pauseEnabled = false,
            autoJumpEnabled = false,
        )
        /** Menu question page: plays the prompt then auto-advances to the options. */
        private val MENU_QUESTION_CONTROLS = ControlSettings(
            wheelEnabled = false,
            okEnabled = false,
            homeEnabled = false,
            pauseEnabled = false,
            autoJumpEnabled = true,
        )
        /** One selectable option: wheel navigates, OK launches the chapter, HOME exits. */
        private val OPTION_CONTROLS = ControlSettings(
            wheelEnabled = true,
            okEnabled = true,
            homeEnabled = true,
            pauseEnabled = false,
            autoJumpEnabled = false,
        )
        /** Story chapter: plays by itself (autoplay) AND OK advances to the next chapter;
         * HOME returns to the menu. */
        private val STORY_CONTROLS = ControlSettings(
            wheelEnabled = false,
            okEnabled = true,
            homeEnabled = true,
            pauseEnabled = true,
            autoJumpEnabled = true,
        )

        /**
         * Converts any supported audio format to mono 44.1 kHz MP3 (the Lunii format).
         * Falls back to the original data if the format is not decodable by Java Sound
         * (e.g. old WebM/Opus recordings from before the frontend WAV encoder).
         */
        private fun toMp3(data: ByteArray): ByteArray = try {
            AudioConversion.anyToMp3(data)
        } catch (e: Exception) {
            logger.warn("Audio to MP3 conversion failed, keeping original data: {}", e.message)
            data
        }
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
        val coverAudio = draft.titleAudioFile?.let { draftStore.readBinary(draftId, it) }?.let { toMp3(it) }
            ?: ttsEngine.synthesize(draft.titleText?.trim()?.takeIf { it.isNotEmpty() } ?: title)
        // Menu prompt: uploaded audio, else TTS of the typed text, else a default prompt.
        val menuPrompt = draft.menuAudioFile?.let { draftStore.readBinary(draftId, it) }?.let { toMp3(it) }
            ?: draft.menuText?.trim()?.takeIf { it.isNotEmpty() }?.let { ttsEngine.synthesize(it) }
            ?: ttsEngine.synthesize("Choisissez un chapitre")

        // The pack UUID is ALSO the first stage node (cover) UUID: the library scanner
        // identifies a pack by the first stage node's UUID, so they must match or a second
        // duplicate pack is registered when the library is rescanned.

        fun action(type: EnrichedNodeType, options: List<StageNode>, x: Int): ActionNode = ActionNode(
            options = options,
            enriched = EnrichedNodeMetadata(
                name = null,
                type = type,
                groupId = null,
                position = EnrichedNodePosition(x.toShort(), 64),
            ),
        )

        // Classic Lunii menu graph (reference pack):
        //   cover -> actionQ -> menuQuestion (autoplay) -> actionOptions (wheel)
        //     -> option k -> storyAction_k -> story k
        //   story k --ok/home--> actionQ (back to the menu question)
        val chapterPairs = draft.chapters.mapIndexed { index, chapter ->
            buildChapterPair(draftId, chapter, chaptersIndex = index + 1)
        }
        val options = chapterPairs.map { it.first }
        val stories = chapterPairs.map { it.second }

        // actionQ: the single "question" action (cover -> question, stories -> question).
        val actionOptions = action(EnrichedNodeType.MENU_OPTIONS_ACTION, options, 0)
        val menuQuestion = StageNode(
            uuid = UUID.randomUUID().toString(),
            image = null,
            audio = AudioAsset("audio/mpeg", menuPrompt, null),
            okTransition = Transition(actionOptions, 0),
            homeTransition = null,
            controlSettings = MENU_QUESTION_CONTROLS,
            enriched = EnrichedNodeMetadata(
                name = "Menu node",
                type = EnrichedNodeType.MENU_QUESTION_STAGE,
                groupId = null,
                position = EnrichedNodePosition(0, 0),
            ),
        )
        val actionQ = action(EnrichedNodeType.MENU_QUESTION_ACTION, listOf(menuQuestion), 0)

        val coverNode = StageNode(
            uuid = packUuid,
            image = ImageAsset(mimeTypeOf(draft.coverFile!!), coverImage, null),
            audio = AudioAsset("audio/mpeg", coverAudio, null),
            okTransition = Transition(actionQ, 0),
            homeTransition = null,
            controlSettings = COVER_CONTROLS,
            enriched = EnrichedNodeMetadata(
                name = title,
                type = EnrichedNodeType.COVER,
                groupId = null,
                position = EnrichedNodePosition(0, 0),
            ),
        )

        // Each option launches its story; stories flow forward (auto + OK -> next chapter),
// the last story returns to the menu question; HOME always returns to the menu.
        val storyActions = options.mapIndexed { index, option ->
            val storyAction = action(EnrichedNodeType.STORY_ACTION, listOf(stories[index]), index + 2)
            option.okTransition = Transition(storyAction, 0)
            storyAction
        }
        val nextActions = stories.dropLast(1).mapIndexed { index, _ ->
            action(EnrichedNodeType.STORY_ACTION, listOf(stories[index + 1]), index + 2 + options.size)
        }
        stories.forEachIndexed { index, story ->
            // Chapter k -> next chapter (autoplay finish or OK); last chapter -> back to menu.
            story.okTransition = if (index < stories.size - 1) {
                Transition(nextActions[index], 0)
            } else {
                Transition(actionQ, 0)
            }
            story.homeTransition = Transition(actionQ, 0)
        }

        val stageNodes = listOf(coverNode, menuQuestion) + options + stories
        val actionNodes = listOf(actionQ, actionOptions) + storyActions + nextActions

        val pack = StoryPack(
            uuid = packUuid,
            factoryDisabled = false,
            version = 1,
            stageNodes = stageNodes,
            enriched = EnrichedPackMetadata(title, description),
            nightModeAvailable = true,
        )

        val thumbnailPng = draft.thumbnailFile
            ?.let { draftStore.readBinary(draftId, it) }
            ?.let(::toPng)

        val libraryDir = Path.of(settingsService.getLibraryPath()).also { Files.createDirectories(it) }
        val destination = libraryDir.resolve("$packUuid.$PACK_EXT_ZIP")

        val tmp = withContext(Dispatchers.IO) {
            Files.createTempFile("studio_kmp_finalize_", ".$PACK_EXT_ZIP")
        }
        try {
            withContext(Dispatchers.IO) {
                FileOutputStream(tmp.toFile()).use { archiveWriter.write(pack, it) }
            }
            if (thumbnailPng != null) {
                updatePackMetadata.updateArchiveMetadata(tmp, PackFileMetadata(thumbnailPngBytes = thumbnailPng))
            }
            withContext(Dispatchers.IO) {
                Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(tmp)
            }
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

    /**
     * Builds the two pages of a chapter: the **option** page (shown in the menu wheel, with the
     * chapter image + title audio) and the **story** page (plays the narration audio only).
     *
     * During the menu selection each option reads its chapter title; once OK is pressed, the
     * story plays the narration WITHOUT re-reading the title (it was already announced).
     */
    private suspend fun buildChapterPair(
        draftId: String,
        chapter: com.maxlass.studio.pack.domain.model.StoryChapterDraftState,
        chaptersIndex: Int,
    ): Pair<StageNode, StageNode> {
        val imageBytes = chapter.imageFile?.let { readImageBytes(draftId, it) }
            ?: chapter.iconId?.let { renderIcon(it) }
            ?: ChapterImageGenerator.generate(chaptersIndex)

        val titleAudio = chapter.titleAudioFile?.let { draftStore.readBinary(draftId, it) }?.let { toMp3(it) }
            ?: ttsEngine.synthesize(chapter.titleText?.trim()?.takeIf { it.isNotEmpty() } ?: chapter.name)
        val narration = chapter.narrationAudioFile?.let { draftStore.readBinary(draftId, it) }
            ?: throw DraftIncompleteException("Chapter '${chapter.name}' has no narration audio")
        val storyAudio = toMp3(narration)

        val groupId = UUID.randomUUID().toString()
        val image = chapter.imageFile?.let { mimeTypeOf(it) } ?: "image/png"

        val option = StageNode(
            uuid = UUID.randomUUID().toString(),
            image = ImageAsset(mimeType = image, rawData = imageBytes, name = null),
            // The menu reads each option's chapter title while selecting.
            audio = AudioAsset("audio/mpeg", titleAudio, null),
            okTransition = null,
            homeTransition = null,
            controlSettings = OPTION_CONTROLS,
            enriched = EnrichedNodeMetadata(
                name = "Option #$chaptersIndex",
                type = EnrichedNodeType.MENU_OPTION_STAGE,
                groupId = groupId,
                position = EnrichedNodePosition(0, (chaptersIndex * 8).toShort()),
            ),
        )

        val story = StageNode(
            uuid = UUID.randomUUID().toString(),
            image = null,
            audio = AudioAsset("audio/mpeg", storyAudio, null),
            okTransition = null,
            homeTransition = null,
            controlSettings = STORY_CONTROLS,
            enriched = EnrichedNodeMetadata(
                name = chapter.name,
                type = EnrichedNodeType.STORY,
                groupId = groupId,
                position = EnrichedNodePosition(0, (chaptersIndex * 8).toShort()),
            ),
        )
        return option to story
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
}