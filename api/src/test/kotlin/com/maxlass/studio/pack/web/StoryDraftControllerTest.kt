package com.maxlass.studio.pack.web

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.pack.domain.dto.CreateChapterRequest
import com.maxlass.studio.pack.domain.dto.SetChapterIconRequest
import com.maxlass.studio.pack.domain.dto.SetTitleTextRequest
import com.maxlass.studio.pack.domain.dto.UpdateDraftRequest
import com.maxlass.studio.pack.service.StoryDraftStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files

class StoryDraftControllerTest : StringSpec({

    val root = Files.createTempDirectory("draft-controller-test")

    afterSpec {
        root.toFile().deleteRecursively()
    }

    val store = StoryDraftStore(StudioProperties(storageDir = root))
    val controller = StoryDraftController(store)

    "createDraft returns a new draft id" {
        val response = controller.createDraft()
        response.statusCode shouldBe HttpStatus.CREATED
        response.body!!.draftId shouldNotBe null
    }

    "creating a new draft replaces the previous one" {
        val first = controller.createDraft().body!!.draftId
        val second = controller.createDraft().body!!.draftId

        shouldThrow<ResponseStatusException> { controller.getDraft(first) }
        controller.getDraft(second).id shouldBe second
    }

    "updateDraft sets title and description" {
        val draftId = controller.createDraft().body!!.draftId
        val updated = controller.updateDraft(draftId, UpdateDraftRequest("Titre", "Description"))
        updated.title shouldBe "Titre"
        updated.description shouldBe "Description"
    }

    "addChapter requires a non-blank name" {
        val draftId = controller.createDraft().body!!.draftId
        val e = shouldThrow<ResponseStatusException> {
            controller.addChapter(draftId, CreateChapterRequest("  "))
        }
        e.statusCode.value() shouldBe 400
    }

    "chapter audio upload is stored and reported without bytes" {
        val draftId = controller.createDraft().body!!.draftId
        val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap 1")).body!!.chapterId

        val file = MockMultipartFile("file", "titre.mp3", "audio/mpeg", byteArrayOf(1, 2, 3, 4))
        val summary = controller.setChapterAudio(draftId, chapterId, file)
        summary.chapters.single().hasTitleAudio shouldBe true
        summary.chapters.single().titleAudioBytes shouldBe 4
        summary.chapters.single().titleText shouldBe null
    }

    "chapter audio upload rejects non-audio files" {
        val draftId = controller.createDraft().body!!.draftId
        val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId
        val file = MockMultipartFile("file", "img.png", "image/png", byteArrayOf(1))
        val e = shouldThrow<ResponseStatusException> { controller.setChapterAudio(draftId, chapterId, file) }
        e.statusCode.value() shouldBe 400
    }

    "title text replaces audio" {
        val draftId = controller.createDraft().body!!.draftId
        val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId

        controller.setChapterTitleText(draftId, chapterId, SetTitleTextRequest("Titre TTS"))
        val summary = controller.getDraft(draftId)
        summary.chapters.single().titleText shouldBe "Titre TTS"
        summary.chapters.single().hasTitleAudio shouldBe false
    }

    "title text rejects blank text" {
        val draftId = controller.createDraft().body!!.draftId
        val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId
        val e = shouldThrow<ResponseStatusException> {
            controller.setChapterTitleText(draftId, chapterId, SetTitleTextRequest(" "))
        }
        e.statusCode.value() shouldBe 400
    }

    "image upload is stored and icon fallback kept" {
        val draftId = controller.createDraft().body!!.draftId
        val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId

        val file = MockMultipartFile("file", "img.png", "image/png", byteArrayOf(5, 6))
        controller.setChapterImage(draftId, chapterId, file)
        controller.setChapterIcon(draftId, chapterId, SetChapterIconRequest("star"))

        val summary = controller.getDraft(draftId)
        summary.chapters.single().hasImage shouldBe true
        summary.chapters.single().imageBytes shouldBe 2
        summary.chapters.single().iconId shouldBe "star"
    }

    "narration audio upload is stored and reported without bytes" {
        val draftId = controller.createDraft().body!!.draftId
        val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId

        val file = MockMultipartFile("file", "narration.mp3", "audio/mpeg", byteArrayOf(1, 2, 3))
        val summary = controller.setChapterNarration(draftId, chapterId, file)
        summary.chapters.single().hasNarrationAudio shouldBe true
        summary.chapters.single().narrationAudioBytes shouldBe 3
        summary.chapters.single().hasTitleAudio shouldBe false
    }

    "narration audio upload rejects non-audio files" {
        val draftId = controller.createDraft().body!!.draftId
        val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId
        val file = MockMultipartFile("file", "img.png", "image/png", byteArrayOf(1))
        val e = shouldThrow<ResponseStatusException> { controller.setChapterNarration(draftId, chapterId, file) }
        e.statusCode.value() shouldBe 400
    }

    "thumbnail and cover uploads are stored and reported" {
        val draftId = controller.createDraft().body!!.draftId

        controller.setThumbnail(draftId, MockMultipartFile("file", "thumb.png", "image/png", byteArrayOf(7)))
        controller.setCover(draftId, MockMultipartFile("file", "cover.jpg", "image/jpeg", byteArrayOf(8, 9)))

        val summary = controller.getDraft(draftId)
        summary.hasThumbnail shouldBe true
        summary.thumbnailBytes shouldBe 1
        summary.hasCover shouldBe true
        summary.coverBytes shouldBe 2
    }

    "thumbnail upload rejects non-PNG/JPEG files" {
        val draftId = controller.createDraft().body!!.draftId
        val file = MockMultipartFile("file", "doc.pdf", "application/pdf", byteArrayOf(1))
        val e = shouldThrow<ResponseStatusException> { controller.setThumbnail(draftId, file) }
        e.statusCode.value() shouldBe 400
    }

    "image upload rejects non-PNG/JPEG files" {
        val draftId = controller.createDraft().body!!.draftId
        val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId
        val file = MockMultipartFile("file", "doc.pdf", "application/pdf", byteArrayOf(1))
        val e = shouldThrow<ResponseStatusException> { controller.setChapterImage(draftId, chapterId, file) }
        e.statusCode.value() shouldBe 400
    }

    "deleteChapter removes the chapter" {
        val draftId = controller.createDraft().body!!.draftId
        val chapterId = controller.addChapter(draftId, CreateChapterRequest("Chap")).body!!.chapterId

        controller.deleteChapter(draftId, chapterId)
        controller.getDraft(draftId).chapters shouldBe emptyList()
    }

    "unknown draft or chapter returns 404" {
        val draftId = controller.createDraft().body!!.draftId
        val e1 = shouldThrow<ResponseStatusException> { controller.getDraft("nope") }
        e1.statusCode.value() shouldBe 404
        val e2 = shouldThrow<ResponseStatusException> {
            controller.setChapterIcon(draftId, "nope", SetChapterIconRequest("star"))
        }
        e2.statusCode.value() shouldBe 404
    }

    "deleteDraft clears the draft" {
        val draftId = controller.createDraft().body!!.draftId
        controller.deleteDraft(draftId)
        shouldThrow<ResponseStatusException> { controller.getDraft(draftId) }
    }
})