package com.maxlass.studio.pack.service

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.pack.domain.model.StoryChapterDraftState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

class StoryDraftStoreTest : StringSpec({

    val root = Files.createTempDirectory("draft-store-test")

    afterSpec {
        root.toFile().deleteRecursively()
    }

    fun newStore(): StoryDraftStore = StoryDraftStore(StudioProperties(storageDir = root))

    "cleanAtStartup removes leftovers from previous runs" {
        val props = StudioProperties(storageDir = root)
        val leftovers = props.draftsDir.resolve("leftover")
        Files.createDirectories(leftovers)
        Files.write(leftovers.resolve("old.mp3"), byteArrayOf(1))

        StoryDraftStore(props).cleanAtStartup()

        props.draftsDir.toFile().exists() shouldBe false
    }

    "creating a draft replaces the previous one (single draft at a time)" {
        val store = newStore()
        val first = store.create()
        val second = store.create()

        second.id shouldNotBe first.id
        store.get(first.id).shouldBeNull()
        store.get(second.id).shouldNotBeNull()
    }

    "get returns null for an unknown draft" {
        val store = newStore()
        store.get("nope").shouldBeNull()
    }

    "updateMetadata changes title and description" {
        val store = newStore()
        val draft = store.create()

        val updated = store.updateMetadata(draft.id, "Mon histoire", "Une belle histoire")!!
        updated.title shouldBe "Mon histoire"
        updated.description shouldBe "Une belle histoire"
    }

    "the state is persisted on disk as draft.json" {
        val store = newStore()
        val draft = store.create()
        store.updateMetadata(draft.id, "Mon histoire", "Une belle histoire")

        val file = store.draftDir(draft.id).resolve("draft.json")
        file.toFile().exists() shouldBe true
        Files.readString(file).contains("Mon histoire") shouldBe true
    }

    "addChapter appends a chapter and removeChapter removes it with its files" {
        val store = newStore()
        val draft = store.create()

        val chapter = store.addChapter(draft.id, "Chapitre 1")!!
        store.setNarrationAudio(draft.id, chapter.id, byteArrayOf(1, 2), "audio/mpeg")

        val draft2 = store.get(draft.id)!!
        draft2.chapters.map { it.name } shouldBe listOf("Chapitre 1")
        val stored = draft2.chapters.single()
        stored.narrationAudioFile shouldNotBe null
        val narration = store.draftDir(draft.id).resolve(stored.narrationAudioFile!!)
        narration.toFile().exists() shouldBe true

        val draft3 = store.removeChapter(draft.id, chapter.id)!!
        draft3.chapters shouldBe emptyList<StoryChapterDraftState>()
        narration.toFile().exists() shouldBe false
    }

    "chapter title audio and text are mutually exclusive" {
        val store = newStore()
        val draft = store.create()
        val chapter = store.addChapter(draft.id, "Chap")!!

        store.setTitleAudio(draft.id, chapter.id, byteArrayOf(1, 2, 3), "audio/mpeg")
        var current = store.get(draft.id)!!.chapters.single()
        current.titleAudioFile shouldNotBe null
        current.titleText.shouldBeNull()
        val oldFile = store.draftDir(draft.id).resolve(current.titleAudioFile!!)

        store.setTitleText(draft.id, chapter.id, "Titre TTS")
        current = store.get(draft.id)!!.chapters.single()
        current.titleText shouldBe "Titre TTS"
        current.titleAudioFile.shouldBeNull()
        oldFile.toFile().exists() shouldBe false
    }

    "setTitleAudio and setTitleText are mutually exclusive" {
        val store = newStore()
        val draft = store.create()

        store.setTitleAudio(draft.id, byteArrayOf(1, 2, 3), "audio/mpeg")
        var current = store.get(draft.id)!!
        current.titleAudioFile shouldNotBe null
        current.titleText.shouldBeNull()
        val oldFile = store.draftDir(draft.id).resolve(current.titleAudioFile!!)

        store.setTitleText(draft.id, "Mon histoire")
        current = store.get(draft.id)!!
        current.titleText shouldBe "Mon histoire"
        current.titleAudioFile.shouldBeNull()
        oldFile.toFile().exists() shouldBe false
    }

    "narration, thumbnail, cover and chapter image are stored on disk with a typed extension" {
        val store = newStore()
        val draft = store.create()
        val chapter = store.addChapter(draft.id, "Chap")!!

        store.setThumbnail(draft.id, byteArrayOf(1), "image/png")
        store.setCover(draft.id, byteArrayOf(2), "image/jpeg")
        store.setChapterImage(draft.id, chapter.id, byteArrayOf(3), "image/png")
        store.setNarrationAudio(draft.id, chapter.id, byteArrayOf(4, 5, 6, 7), "audio/mpeg")

        val current = store.get(draft.id)!!
        current.thumbnailFile shouldBe "thumbnail.png"
        current.coverFile shouldBe "cover.jpg"
        current.chapters.single().imageFile shouldBe "chapters/${chapter.id}/image.png"
        current.chapters.single().narrationAudioFile shouldBe "chapters/${chapter.id}/narration.mp3"

        store.draftDir(draft.id).resolve(current.thumbnailFile!!).toFile().readBytes() shouldBe byteArrayOf(1)
        store.draftDir(draft.id).resolve(current.coverFile!!).toFile().readBytes() shouldBe byteArrayOf(2)
        store.draftDir(draft.id).resolve(current.chapters.single().imageFile!!).toFile().readBytes() shouldBe byteArrayOf(3)
        store.draftDir(draft.id).resolve(current.chapters.single().narrationAudioFile!!).toFile().readBytes() shouldBe byteArrayOf(4, 5, 6, 7)
    }

    "setChapterIcon stores the icon slug" {
        val store = newStore()
        val draft = store.create()
        val chapter = store.addChapter(draft.id, "Chap")!!

        store.setChapterIcon(draft.id, chapter.id, "star")
        store.get(draft.id)!!.chapters.single().iconId shouldBe "star"
    }

    "replacing a draft deletes the previous draft directory" {
        val store = newStore()
        val first = store.create()
        store.setThumbnail(first.id, byteArrayOf(9), "image/png")

        val dir = store.draftDir(first.id)
        store.create()

        dir.toFile().exists() shouldBe false
    }

    "operations on an unknown draft or chapter return null" {
        val store = newStore()
        val draft = store.create()

        store.addChapter("nope", "X").shouldBeNull()
        store.setTitleAudio(draft.id, "nope", byteArrayOf(1), "audio/mpeg").shouldBeNull()
        store.removeChapter("nope", "x").shouldBeNull()
        store.updateMetadata("nope", "t", "d").shouldBeNull()
        store.setThumbnail("nope", byteArrayOf(1), "image/png").shouldBeNull()
    }

    "clear removes the current draft and its temp dir" {
        val store = newStore()
        val draft = store.create()
        store.setThumbnail(draft.id, byteArrayOf(1), "image/png")
        val dir = store.draftDir(draft.id)

        store.clear("nope") shouldBe false
        store.get(draft.id).shouldNotBeNull()
        dir.toFile().exists() shouldBe true

        store.clear(draft.id) shouldBe true
        store.get(draft.id).shouldBeNull()
        dir.toFile().exists() shouldBe false
    }
})