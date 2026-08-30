package com.maxlass.studio.pack.web

import com.maxlass.studio.pack.domain.dto.ChapterIconDto
import com.maxlass.studio.pack.service.ChapterIconCatalogService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.server.ResponseStatusException

class ChapterImageControllerTest : StringSpec({

    val iconCatalog = mockk<ChapterIconCatalogService>()
    val controller = ChapterImageController(iconCatalog)

    "lists the default icon set" {
        coEvery { iconCatalog.defaultIcons() } returns listOf(ChapterIconDto("star", "Star"))
        val response = runBlocking { controller.icons() }
        response.icons.map { it.id } shouldBe listOf("star")
    }

    "renders a known icon as PNG" {
        coEvery { iconCatalog.loadIcon("star") } returns """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="m12 2 3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z"/>
            </svg>
        """.trimIndent()
        val response = runBlocking { controller.preview(iconId = "star", chapterNumber = null) }
        response.headers.contentType?.subtype shouldBe "png"
        response.body shouldNotBe null
    }

    "renders a chapter number as PNG" {
        val response = runBlocking { controller.preview(iconId = null, chapterNumber = 3) }
        response.headers.contentType?.subtype shouldBe "png"
        response.body shouldNotBe null
    }

    "rejects preview without iconId or chapterNumber" {
        val e = io.kotest.assertions.throwables.shouldThrow<ResponseStatusException> {
            runBlocking { controller.preview(iconId = null, chapterNumber = null) }
        }
        e.statusCode.value() shouldBe 400
    }

    "rejects unknown icon" {
        coEvery { iconCatalog.loadIcon("nope") } returns null
        val e = io.kotest.assertions.throwables.shouldThrow<ResponseStatusException> {
            runBlocking { controller.preview(iconId = "nope", chapterNumber = null) }
        }
        e.statusCode.value() shouldBe 404
    }

    "searches icons with at least 2 characters" {
        coEvery { iconCatalog.searchIcons("mo") } returns listOf(ChapterIconDto("moon", "Moon"))
        val response = runBlocking { controller.searchIcons("mo") }
        response.icons.map { it.id } shouldBe listOf("moon")
    }

    "rejects icon search with a short query" {
        val e = io.kotest.assertions.throwables.shouldThrow<ResponseStatusException> {
            runBlocking { controller.searchIcons("m") }
        }
        e.statusCode.value() shouldBe 400
    }

    "renders an uploaded svg as PNG" {
        val file = MockMultipartFile(
            "file",
            "dragon.svg",
            "image/svg+xml",
            """<svg viewBox="0 0 24 24"><path d="M12 2v20"/></svg>""".toByteArray(),
        )
        val response = controller.render(file)
        response.headers.contentType?.subtype shouldBe "png"
        response.body shouldNotBe null
    }

    "rejects a non-svg upload" {
        val file = MockMultipartFile("file", "pic.png", "image/png", byteArrayOf(1, 2, 3))
        val e = io.kotest.assertions.throwables.shouldThrow<ResponseStatusException> {
            controller.render(file)
        }
        e.statusCode.value() shouldBe 400
    }

    "rejects invalid svg content" {
        val file = MockMultipartFile("file", "bad.svg", "image/svg+xml", "<svg></svg>".toByteArray())
        val e = io.kotest.assertions.throwables.shouldThrow<ResponseStatusException> {
            controller.render(file)
        }
        e.statusCode.value() shouldBe 400
    }
})