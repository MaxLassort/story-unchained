package com.maxlass.studio.pack.web

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.pack.domain.dto.CreateChapterRequest
import com.maxlass.studio.pack.domain.dto.PatchNodeRequest
import com.maxlass.studio.pack.domain.dto.UpdateDraftRequest
import com.maxlass.studio.pack.service.CreateStoryUseCase
import com.maxlass.studio.pack.service.DraftIncompleteException
import com.maxlass.studio.pack.service.StoryDraftStore
import com.maxlass.studio.pack.service.TtsEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import jakarta.validation.Validation
import jakarta.validation.Validator
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files

class StoryDraftControllerTest : StringSpec({

    val root = Files.createTempDirectory("draft-controller-test")

    afterSpec {
        root.toFile().deleteRecursively()
    }

    val ttsStub = run {
        val tts = mockk<TtsEngine>()
        coEvery { tts.synthesize(any(), any(), any()) } returns byteArrayOf(1, 2, 3)
        tts
    }
    val store = StoryDraftStore(StudioProperties(storageDir = root), ttsStub)
    val createStory = mockk<CreateStoryUseCase>(relaxed = true)
    val controller = StoryDraftController(store, createStory)
    val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    "createDraft returns a new draft id" {
        runBlocking {
            val response = controller.createDraft()
            response.statusCode shouldBe HttpStatus.CREATED
            response.body!!.draftId shouldNotBe null
        }
    }

    "creating a new draft replaces the previous one" {
        runBlocking {
            val first = controller.createDraft().body!!.draftId
            val second = controller.createDraft().body!!.draftId

            shouldThrow<ResponseStatusException> { controller.getDraft(first) }
            controller.getDraft(second).id shouldBe second
        }
    }

    "updateDraft sets title and description" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val updated = controller.updateDraft(draftId, UpdateDraftRequest("Titre", "Description"))
            updated.title shouldBe "Titre"
            updated.description shouldBe "Description"
        }
    }

    "addChapter requires a non-blank name" {
        val violations = validator.validate(CreateChapterRequest("  "))
        violations.size shouldBe 1
    }

    "consolidated file upload stores chapter title audio" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap 1")).body!!.chapterId

            val file = MockMultipartFile("file", "titre.mp3", "audio/mpeg", byteArrayOf(1, 2, 3, 4))
            val summary = controller.setDraftFile(draftId, "chapter", chapterId, "titleAudio", file)
            summary.chapters.single().hasTitleAudio shouldBe true
            summary.chapters.single().titleAudioBytes shouldBe 4
            summary.chapters.single().titleText shouldBe null
        }
    }

    "consolidated file upload rejects non-audio files for audio fields" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId
            val file = MockMultipartFile("file", "img.png", "image/png", byteArrayOf(1))
            val e = shouldThrow<ResponseStatusException> {
                controller.setDraftFile(draftId, "chapter", chapterId, "titleAudio", file)
            }
            e.statusCode.value() shouldBe 400
        }
    }

    "consolidated file upload rejects unknown fields" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val file = MockMultipartFile("file", "a.mp3", "audio/mpeg", byteArrayOf(1))
            val e = shouldThrow<ResponseStatusException> {
                controller.setDraftFile(draftId, "pack", null, "bogus", file)
            }
            e.statusCode.value() shouldBe 400
        }
    }

    "consolidated file upload rejects image field on pack scope" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val file = MockMultipartFile("file", "img.png", "image/png", byteArrayOf(1))
            val e = shouldThrow<ResponseStatusException> {
                controller.setDraftFile(draftId, "pack", null, "image", file)
            }
            e.statusCode.value() shouldBe 404
        }
    }

    "consolidated patch sets chapter title text (TTS synthesized at save time)" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId

            controller.patchNode(draftId, chapterId, PatchNodeRequest(titleText = "Titre TTS"))
            val summary = controller.getDraft(draftId)
            summary.chapters.single().titleText shouldBe "Titre TTS"
            // TTS is synthesized at save time: audio exists in the draft.
            summary.chapters.single().hasTitleAudio shouldBe true
            summary.chapters.single().titleAudioBytes shouldNotBe 0
        }
    }

    "consolidated patch sets chapter name and icon" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId

            controller.patchNode(draftId, chapterId, PatchNodeRequest(name = "Renommé", iconId = "star"))
            val summary = controller.getDraft(draftId)
            summary.chapters.single().name shouldBe "Renommé"
            summary.chapters.single().iconId shouldBe "star"
        }
    }

    "consolidated patch on pack root sets pack name" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId

            controller.patchNode(draftId, draftId, PatchNodeRequest(name = "Nouveau titre"))
            val summary = controller.getDraft(draftId)
            summary.title shouldBe "Nouveau titre"
        }
    }

    "consolidated patch on unknown node returns 404" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val e = shouldThrow<ResponseStatusException> {
                controller.patchNode(draftId, "nope", PatchNodeRequest(name = "X"))
            }
            e.statusCode.value() shouldBe 404
        }
    }

    "consolidated file upload stores narration" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId

            val file = MockMultipartFile("file", "narration.mp3", "audio/mpeg", byteArrayOf(1, 2, 3))
            val summary = controller.setDraftFile(draftId, "chapter", chapterId, "narration", file)
            summary.chapters.single().hasNarrationAudio shouldBe true
            summary.chapters.single().narrationAudioBytes shouldBe 3
        }
    }

    "consolidated file upload stores thumbnail and cover" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId

            controller.setDraftFile(draftId, "pack", null, "thumbnail", MockMultipartFile("file", "thumb.png", "image/png", byteArrayOf(7)))
            controller.setDraftFile(draftId, "pack", null, "cover", MockMultipartFile("file", "cover.jpg", "image/jpeg", byteArrayOf(8, 9)))

            val summary = controller.getDraft(draftId)
            summary.hasThumbnail shouldBe true
            summary.thumbnailBytes shouldBe 1
            summary.hasCover shouldBe true
            summary.coverBytes shouldBe 2
        }
    }

    "consolidated pack title audio and TTS text both end up as stored audio" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId

            controller.setDraftFile(draftId, "pack", null, "titleAudio", MockMultipartFile("file", "titre.mp3", "audio/mpeg", byteArrayOf(1, 2, 3)))
            var summary = controller.getDraft(draftId)
            summary.hasTitleAudio shouldBe true
            summary.titleAudioBytes shouldBe 3
            summary.titleText shouldBe null

            coEvery { ttsStub.synthesize(any(), any(), any()) } returns byteArrayOf(4, 5, 6, 7, 8)
            controller.patchNode(draftId, draftId, PatchNodeRequest(titleText = "Ma petite histoire"))
            summary = controller.getDraft(draftId)
            summary.titleText shouldBe "Ma petite histoire"
            // TTS is synthesized at save time: a new audio file replaces the uploaded one.
            summary.hasTitleAudio shouldBe true
            summary.titleAudioBytes shouldNotBe 3
        }
    }

    "consolidated file upload rejects non-audio for pack titleAudio" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val file = MockMultipartFile("file", "img.png", "image/png", byteArrayOf(1))
            val e = shouldThrow<ResponseStatusException> {
                controller.setDraftFile(draftId, "pack", null, "titleAudio", file)
            }
            e.statusCode.value() shouldBe 400
        }
    }

    "consolidated file upload rejects non-PNG/JPEG for thumbnail" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val file = MockMultipartFile("file", "doc.pdf", "application/pdf", byteArrayOf(1))
            val e = shouldThrow<ResponseStatusException> {
                controller.setDraftFile(draftId, "pack", null, "thumbnail", file)
            }
            e.statusCode.value() shouldBe 400
        }
    }

    "consolidated file upload rejects non-PNG/JPEG for chapter image" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId
            val file = MockMultipartFile("file", "doc.pdf", "application/pdf", byteArrayOf(1))
            val e = shouldThrow<ResponseStatusException> {
                controller.setDraftFile(draftId, "chapter", chapterId, "image", file)
            }
            e.statusCode.value() shouldBe 400
        }
    }

    "deleteChapter removes the chapter" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId

            controller.deleteChapter(draftId, chapterId)

            controller.getDraft(draftId).chapters shouldBe emptyList()
        }
    }

    "unknown draft or chapter returns 404" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            val e1 = shouldThrow<ResponseStatusException> { controller.getDraft("nope") }
            e1.statusCode.value() shouldBe 404
            val e2 = shouldThrow<ResponseStatusException> {
                controller.setDraftFile(draftId, "chapter", "nope", "titleAudio", MockMultipartFile("file", "a.mp3", "audio/mpeg", byteArrayOf(1)))
            }
            e2.statusCode.value() shouldBe 404
        }
    }

    "deleteDraft clears the draft" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            controller.deleteDraft(draftId)
            shouldThrow<ResponseStatusException> { controller.getDraft(draftId) }
        }
    }

    "finalizeDraft returns the pack id from the use case" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            coEvery { createStory.finalize(draftId) } returns "pack-123"

            val response = controller.finalizeDraft(draftId)
            response.statusCode shouldBe HttpStatus.OK
            response.body!!.packId shouldBe "pack-123"
        }
    }

    "finalizeDraft throws DraftIncompleteException when the draft is incomplete" {
        runBlocking {
            val draftId = controller.createDraft().body!!.draftId
            coEvery { createStory.finalize(draftId) } throws DraftIncompleteException("Draft is incomplete")

            shouldThrow<DraftIncompleteException> { controller.finalizeDraft(draftId) }
        }
    }

    "finalizeDraft throws NoSuchElementException for an unknown draft" {
        runBlocking {
            coEvery { createStory.finalize("nope") } throws NoSuchElementException("Draft not found: nope")

            shouldThrow<NoSuchElementException> { controller.finalizeDraft("nope") }
        }
    }
})
