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
        store.setTitleText(draft.id, "Mon histoire")
        store.addChapter(draft.id, "Chapitre un")
        val chapter = store.get(draft.id)!!.chapters[0]
        store.setTitleText(draft.id, chapter.id, "Chapitre un")
        store.setNarrationAudio(draft.id, chapter.id, tinyMp3(), "audio/mpeg")
        store.setChapterIcon(draft.id, chapter.id, "star")
        return store.get(draft.id)!!
    }

    "finalize writes an archive pack, registers it and purges the draft" {
        val root = Files.createTempDirectory("finalize-test")
        try {
            val store = StoryDraftStore(StudioProperties(storageDir = root))
            val library = Files.createTempDirectory("library-test").also { Files.createDirectories(it) }

            val tts = mockk<TtsEngine>()
            coEvery { tts.synthesize(any(), any(), any()) } returns tinyMp3()

            val catalog = mockk<ChapterIconCatalogService>()
            coEvery { catalog.loadIcon("star") } returns """<svg viewBox="0 0 24 24"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z"/></svg>"""

            val settings = mockk<com.maxlass.studio.settings.service.SettingsService>()
            coEvery { settings.getLibraryPath() } returns library.toString()

            val repo = mockk<com.maxlass.studio.pack.port.persistence.PackRepositoryPort>(relaxed = true)

            val useCase = CreateStoryUseCase(
                draftStore = store,
                ttsEngine = tts,
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

            // Round-trips through the archive reader with the linear graph.
            val pack = ArchiveStoryPackReader().read(java.io.FileInputStream(zip.toFile()))
            pack.enriched!!.title shouldBe "Mon histoire"
            pack.stageNodes shouldNotBe null
            val nodes = pack.stageNodes!!
            nodes.size shouldBe 2 // cover + 1 chapter
            nodes[0].image shouldNotBe null
            nodes[0].audio shouldNotBe null
            nodes[0].okTransition shouldNotBe null
            nodes[1].image shouldNotBe null
            nodes[1].audio shouldNotBe null
            // The last chapter must have a valid OK transition (looping back to the cover),
            // otherwise the Lunii shows an "error card" when the story reaches it.
            nodes[1].okTransition shouldNotBe null
            nodes[1].enriched!!.name shouldBe "Chapitre un"
            val endAction = nodes[1].okTransition!!.actionNode
            endAction shouldNotBe null
            endAction!!.options.orEmpty().firstOrNull() shouldBe nodes[0]

            // Draft purged.
            store.get(draft.id) shouldBe null
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "finalize throws DraftIncompleteException for a missing narration" {
        val root = Files.createTempDirectory("finalize-test-2")
        try {
            val store = StoryDraftStore(StudioProperties(storageDir = root))
            val draft = store.create()
            store.updateMetadata(draft.id, "T", "D")
            store.setThumbnail(draft.id, tinyPng(), "image/png")
            store.setCover(draft.id, tinyPng(), "image/png")
            store.addChapter(draft.id, "Chapitre")

            val useCase = CreateStoryUseCase(
                draftStore = store,
                ttsEngine = mockk(relaxed = true),
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
            val store = StoryDraftStore(StudioProperties(storageDir = root))
            val useCase = CreateStoryUseCase(
                draftStore = store,
                ttsEngine = mockk(relaxed = true),
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
})