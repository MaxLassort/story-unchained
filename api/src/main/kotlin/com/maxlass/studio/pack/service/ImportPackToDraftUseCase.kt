package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.domain.model.StoryDraftState
import com.maxlass.studio.pack.format.model.ActionNode
import com.maxlass.studio.pack.format.model.EnrichedNodeType
import com.maxlass.studio.pack.format.model.StageNode
import com.maxlass.studio.pack.format.model.StoryPack
import com.maxlass.studio.pack.format.reader.ArchiveStoryPackReader
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import com.maxlass.studio.pack.util.readThumbnailBytes
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Rehydrates an Unchained ARCHIVE pack into the single active story draft so the wizard
 * can edit it. Walks the classic menu graph produced by [CreateStoryUseCase].
 */
@Service
class ImportPackToDraftUseCase(
    private val packRepository: PackRepositoryPort,
    private val draftStore: StoryDraftStore,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ImportPackToDraftUseCase::class.java)
    }

    private val archiveReader = ArchiveStoryPackReader()

    /**
     * @throws NoSuchElementException if the pack is unknown
     * @throws IllegalArgumentException if the pack is not Unchained or has no ARCHIVE variant
     * @throws IllegalStateException if the pack graph cannot be mapped back to a draft
     */
    suspend fun invoke(packId: String): StoryDraftState {
        val pack = packRepository.getAllPacks().find { it.id == packId }
            ?: throw NoSuchElementException("Pack not found: $packId")
        if (!pack.metadata.unchained) {
            throw IllegalArgumentException("Pack $packId is not an Unchained story")
        }
        val archivePath = pack.variants
            .firstOrNull { it.format == PackFormat.ARCHIVE }
            ?.storagePath
            ?.let(Path::of)
            ?: throw IllegalArgumentException("Pack $packId has no ARCHIVE variant to edit")
        if (!Files.isRegularFile(archivePath)) {
            throw IllegalStateException("ARCHIVE file missing for pack $packId: $archivePath")
        }

        val storyPack = FileInputStream(archivePath.toFile()).use { archiveReader.read(it) }
        val cover = findCover(storyPack)
            ?: throw IllegalStateException("Unchained pack $packId has no cover stage")
        val menuQuestion = followToMenuQuestion(cover)
            ?: throw IllegalStateException("Unchained pack $packId has no menu question stage")
        val options = followToOptions(menuQuestion)
            ?: throw IllegalStateException("Unchained pack $packId has no chapter options")

        val draft = draftStore.create()
        val draftId = draft.id
        val title = pack.metadata.title ?: storyPack.enriched?.title
        val description = pack.metadata.description ?: storyPack.enriched?.description
        draftStore.updateMetadata(draftId, title, description)

        val thumbnail = readThumbnailBytes(archivePath)
        if (thumbnail != null) {
            draftStore.setThumbnail(draftId, thumbnail, "image/png")
        }

        val coverImage = cover.image?.rawData
        if (coverImage != null) {
            draftStore.setCover(draftId, coverImage, cover.image?.mimeType ?: "image/png")
        }
        val titleAudio = cover.audio?.rawData
        if (titleAudio != null) {
            draftStore.setTitleAudio(draftId, titleAudio, cover.audio?.mimeType ?: "audio/mpeg")
        }
        val menuAudio = menuQuestion.audio?.rawData
        if (menuAudio != null) {
            draftStore.setMenuAudio(draftId, menuAudio, menuQuestion.audio?.mimeType ?: "audio/mpeg")
        }

        options.forEachIndexed { index, option ->
            val story = followToStory(option)
            val chapterName = story?.enriched?.name?.takeIf { it.isNotBlank() }
                ?: option.enriched?.name?.takeIf { it.isNotBlank() }
                ?: "Chapter ${index + 1}"
            val chapter = draftStore.addChapter(draftId, chapterName)
                ?: throw IllegalStateException("Failed to add chapter for pack $packId")
            val chapterId = chapter.id

            val titleBytes = option.audio?.rawData
            if (titleBytes != null) {
                draftStore.setTitleAudio(draftId, chapterId, titleBytes, option.audio?.mimeType ?: "audio/mpeg")
            }
            val narration = story?.audio?.rawData
            if (narration != null) {
                draftStore.setNarrationAudio(draftId, chapterId, narration, story.audio?.mimeType ?: "audio/mpeg")
            }
            val image = option.image?.rawData
            if (image != null) {
                draftStore.setChapterImage(draftId, chapterId, image, option.image?.mimeType ?: "image/png")
            }
        }

        val result = draftStore.get(draftId)
            ?: throw IllegalStateException("Draft disappeared after import: $draftId")
        logger.info("Imported Unchained pack {} into draft {} ({} chapters)", packId, draftId, result.chapters.size)
        return result
    }

    private fun findCover(pack: StoryPack): StageNode? {
        val stages = pack.stageNodes.orEmpty()
        return stages.firstOrNull { it.enriched?.type == EnrichedNodeType.COVER }
            ?: stages.firstOrNull()
    }

    private fun followToMenuQuestion(cover: StageNode): StageNode? {
        val actionQ = cover.okTransition?.actionNode as? ActionNode ?: return null
        val menuQuestion = actionQ.options?.firstOrNull() ?: return null
        if (menuQuestion.enriched?.type != null &&
            menuQuestion.enriched?.type != EnrichedNodeType.MENU_QUESTION_STAGE
        ) {
            return menuQuestion // tolerate missing/legacy type labels
        }
        return menuQuestion
    }

    private fun followToOptions(menuQuestion: StageNode): List<StageNode>? {
        val actionOptions = menuQuestion.okTransition?.actionNode as? ActionNode ?: return null
        return actionOptions.options?.takeIf { it.isNotEmpty() }
    }

    private fun followToStory(option: StageNode): StageNode? {
        val storyAction = option.okTransition?.actionNode as? ActionNode ?: return null
        return storyAction.options?.firstOrNull()
    }
}
