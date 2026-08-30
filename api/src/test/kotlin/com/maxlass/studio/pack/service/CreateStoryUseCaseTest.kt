package com.maxlass.studio.pack.service

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.pack.domain.model.StoryDraftState
import com.maxlass.studio.pack.format.reader.ArchiveStoryPackReader
import com.maxlass.studio.pack.port.external.PackFileMetadata
import com.maxlass.studio.pack.port.external.UpdatePackFileMetadataPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Tests the draft → STUdio zip finalization: validation (409), image fallbacks, linear graph,
 * archive round-trip and library/DB registration.
 */
class CreateStoryUseCaseTest : StringSpec({

    fun tinyPng(): ByteArray {
        val img = java.awt.image.BufferedImage(320, 240, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, 320, 240)
        g.dispose()
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    fun tinyMp3(): ByteArray {
        // A minimal valid WAV wrapped by anyToMp3-like conversion is complex; use the BlankMp3 fixture bytes.
        val hex = com.maxlass.studio.pack.format.writer.BlankMp3.HEX
        return ByteArray(hex.length / 2) { i -> ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte() }
    }

    fun completeDraft(root: Path, store: StoryDraftStore): StoryDraftState {
        val draft = store.create()
        store.updateMetadata(draft.id, "Mon histoire", "Une histoire")
        store.setThumbnail(draft.id, tinyPng(), "image/png")
        store.setCover(draft.id, tinyPng(), "image/png")
        // Title audios are stored as files (TTS is synthesized at step-save time, not here).
        store.setTitleAudio(draft.id, tinyMp3(), "audio/mpeg")
        store.setMenuAudio(draft.id, tinyMp3(), "audio/mpeg")
        store.addChapter(draft.id, "Chapitre un")
        val chapter = store.get(draft.id)!!.chapters[0]
        store.setTitleAudio(draft.id, chapter.id, tinyMp3(), "audio/mpeg")
        store.setNarrationAudio(draft.id, chapter.id, tinyMp3(), "audio/mpeg")
        store.setChapterIcon(draft.id, chapter.id, "star")
        return store.get(draft.id)!!
    }

    "finalize writes an archive pack, registers it and purges the draft" {
        val root = Files.createTempDirectory("finalize-test")
        try {
            val store = StoryDraftStore(StudioProperties(storageDir = root), mockk(relaxed = true))
            val library = Files.createTempDirectory("library-test").also { Files.createDirectories(it) }

            val catalog = mockk<ChapterIconCatalogService>()
            coEvery { catalog.loadIcon("star") } returns """<svg viewBox="0 0 24 24"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z"/></svg>"""

            val settings = mockk<com.maxlass.studio.settings.service.SettingsService>()
            coEvery { settings.getLibraryPath() } returns library.toString()

            val repo = mockk<com.maxlass.studio.pack.port.persistence.PackRepositoryPort>(relaxed = true)

            val useCase = CreateStoryUseCase(
                draftStore = store,
                iconCatalog = catalog,
                updatePackMetadata = mockk<UpdatePackFileMetadataPort>(relaxed = true),
                settingsService = settings,
                packRepository = repo,
            )

            val draft = completeDraft(root, store)
            val packId = useCase.finalize(draft.id)

            packId shouldNotBe null
            val zip = library.resolve("$packId.zip")
            zip.toFile().exists() shouldBe true

            // Round-trips through the archive reader with the classic Lunii menu graph:
            // cover -> menuQuestion (autoplay) -> actionOptions -> option -> story -> back to question.
            val pack = ArchiveStoryPackReader().read(java.io.FileInputStream(zip.toFile()))
            pack.enriched!!.title shouldBe "Mon histoire"
            pack.stageNodes shouldNotBe null
            val nodes = pack.stageNodes!!
            // The pack UUID must equal the first stage node (cover) UUID: the library scanner
            // identifies a pack by its first stage node UUID, otherwise a duplicate appears.
            pack.uuid shouldBe nodes[0].uuid
            pack.uuid shouldBe packId
            nodes.size shouldBe 4 // cover + menuQuestion + option + story
            nodes[0].image shouldNotBe null
            nodes[0].audio shouldNotBe null
            nodes[0].okTransition shouldNotBe null
            // Cover OK -> menu question.
            nodes[0].okTransition!!.actionNode!!.options.orEmpty().firstOrNull() shouldBe nodes[1]
            // Menu question autoplays into the options action (wheel menu).
            nodes[1].controlSettings!!.autoJumpEnabled shouldBe true
            nodes[1].okTransition!!.actionNode!!.options.orEmpty().firstOrNull() shouldBe nodes[2]
            // Option page: wheel + ok, launches its story.
            nodes[2].controlSettings!!.wheelEnabled shouldBe true
            nodes[2].okTransition!!.actionNode!!.options.orEmpty().firstOrNull() shouldBe nodes[3]
            // Story page: autoplay, returns to the menu question on OK and HOME.
            nodes[3].controlSettings!!.autoJumpEnabled shouldBe true
            nodes[3].okTransition shouldNotBe null
            nodes[3].okTransition!!.actionNode!!.options.orEmpty().firstOrNull() shouldBe nodes[1]
            nodes[3].homeTransition shouldNotBe null

            // Draft purged.
            store.get(draft.id) shouldBe null
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "finalize throws DraftIncompleteException for a missing narration" {
        val root = Files.createTempDirectory("finalize-test-2")
        try {
            val store = StoryDraftStore(StudioProperties(storageDir = root), mockk(relaxed = true))
            val draft = store.create()
            store.updateMetadata(draft.id, "T", "D")
            store.setThumbnail(draft.id, tinyPng(), "image/png")
            store.setCover(draft.id, tinyPng(), "image/png")
            store.addChapter(draft.id, "Chapitre")

            val useCase = CreateStoryUseCase(
                draftStore = store,
                iconCatalog = mockk(relaxed = true),
                updatePackMetadata = mockk(relaxed = true),
                settingsService = mockk(relaxed = true),
                packRepository = mockk(relaxed = true),
            )

            shouldThrow<DraftIncompleteException> { useCase.finalize(draft.id) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "finalize throws NoSuchElementException for an unknown draft" {
        val root = Files.createTempDirectory("finalize-test-3")
        try {
            val store = StoryDraftStore(StudioProperties(storageDir = root), mockk(relaxed = true))
            val useCase = CreateStoryUseCase(
                draftStore = store,
                iconCatalog = mockk(relaxed = true),
                updatePackMetadata = mockk(relaxed = true),
                settingsService = mockk(relaxed = true),
                packRepository = mockk(relaxed = true),
            )
            shouldThrow<NoSuchElementException> { useCase.finalize(UUID.randomUUID().toString()) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "stories chain forward: chapter 1 auto/OK advances to chapter 2, last returns to the menu" {
        val root = Files.createTempDirectory("finalize-test-4")
        try {
            val store = StoryDraftStore(StudioProperties(storageDir = root), mockk(relaxed = true))
            val library = Files.createTempDirectory("library-test-4").also { Files.createDirectories(it) }
            val settings = mockk<com.maxlass.studio.settings.service.SettingsService>(relaxed = true)
            coEvery { settings.getLibraryPath() } returns library.toString()
            val useCase = CreateStoryUseCase(
                draftStore = store,
                iconCatalog = mockk(relaxed = true),
                updatePackMetadata = mockk(relaxed = true),
                settingsService = settings,
                packRepository = mockk(relaxed = true),
            )

            val draft = store.create()
            store.updateMetadata(draft.id, "Titre", "Desc")
            store.setThumbnail(draft.id, tinyPng(), "image/png")
            store.setCover(draft.id, tinyPng(), "image/png")
            store.setTitleAudio(draft.id, tinyMp3(), "audio/mpeg")
            store.setMenuAudio(draft.id, tinyMp3(), "audio/mpeg")
            store.addChapter(draft.id, "Premier")
            store.addChapter(draft.id, "Second")
            val chapters = store.get(draft.id)!!.chapters
            store.setTitleAudio(draft.id, chapters[0].id, tinyMp3(), "audio/mpeg")
            store.setNarrationAudio(draft.id, chapters[0].id, tinyMp3(), "audio/mpeg")
            store.setTitleAudio(draft.id, chapters[1].id, tinyMp3(), "audio/mpeg")
            store.setNarrationAudio(draft.id, chapters[1].id, tinyMp3(), "audio/mpeg")

            val packId = useCase.finalize(draft.id)
            val pack = ArchiveStoryPackReader().read(java.io.FileInputStream(library.resolve("$packId.zip").toFile()))
            val nodes = pack.stageNodes!!
            // cover, menuQuestion, option1, option2, story1, story2
            nodes.size shouldBe 6
            val story1 = nodes[4]
            val story2 = nodes[5]
            // chapter 1 -> chapter 2 (auto + OK)
            story1.controlSettings!!.okEnabled shouldBe true
            story1.controlSettings!!.autoJumpEnabled shouldBe true
            story1.okTransition!!.actionNode!!.options.orEmpty().firstOrNull() shouldBe story2
            // last chapter -> back to the menu question
            story2.okTransition!!.actionNode!!.options.orEmpty().firstOrNull() shouldBe nodes[1]
            story2.homeTransition!!.actionNode!!.options.orEmpty().firstOrNull() shouldBe nodes[1]
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})