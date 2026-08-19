package com.maxlass.studio.pack.format

import com.maxlass.studio.pack.format.utils.ChapterImageGenerator
import com.maxlass.studio.pack.format.utils.SvgIconRenderer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class ChapterImageGeneratorTest : StringSpec({

    fun readPng(data: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(data)) ?: error("invalid PNG")

    "generates a valid 320x240 PNG" {
        val png = ChapterImageGenerator.generate(1)
        val image = readPng(png)
        image.width shouldBe 320
        image.height shouldBe 240
    }

    "renders white pixels on a black background" {
        val image = readPng(ChapterImageGenerator.generate(1))
        var whiteCount = 0
        var blackCount = 0
        for (x in 0 until image.width step 4) {
            for (y in 0 until image.height step 4) {
                val rgb = image.getRGB(x, y) and 0xFFFFFF
                when (rgb) {
                    0xFFFFFF -> whiteCount++
                    0x000000 -> blackCount++
                }
            }
        }
        whiteCount shouldBeGreaterThan 0
        blackCount shouldBeGreaterThan 0
    }

    "centers the digit" {
        val image = readPng(ChapterImageGenerator.generate(1))
        val white = (0 until image.width).flatMap { x ->
            (0 until image.height).mapNotNull { y ->
                if ((image.getRGB(x, y) and 0xFFFFFF) == 0xFFFFFF) x to y else null
            }
        }
        white shouldNotBe emptyList<Pair<Int, Int>>()
        val avgX = white.map { it.first }.average()
        val avgY = white.map { it.second }.average()
        kotlin.math.abs(avgX - 160.0) shouldBeLessThanOrEqualTo 40.0
        kotlin.math.abs(avgY - 120.0) shouldBeLessThanOrEqualTo 40.0
    }

    "supports custom dimensions" {
        val image = readPng(ChapterImageGenerator.generate(3, width = 100, height = 80))
        image.width shouldBe 100
        image.height shouldBe 80
    }

    "rejects invalid chapter numbers and sizes" {
        shouldThrow<IllegalArgumentException> { ChapterImageGenerator.generate(0) }
        shouldThrow<IllegalArgumentException> { ChapterImageGenerator.generate(-2) }
        shouldThrow<IllegalArgumentException> { ChapterImageGenerator.generate(1, width = 0) }
    }

    "renders a lucide icon as white on black" {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>
            </svg>
        """.trimIndent()
        val png = SvgIconRenderer.render(svg)
        val image = readPng(png)
        image.width shouldBe 320
        image.height shouldBe 240
        var whiteCount = 0
        for (x in 0 until image.width step 4) {
            for (y in 0 until image.height step 4) {
                if ((image.getRGB(x, y) and 0xFFFFFF) == 0xFFFFFF) whiteCount++
            }
        }
        (whiteCount > 0).shouldBeTrue()
    }

    "parses complex paths with arcs, curves and relative commands" {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm0 4a6 6 0 1 1-6 6 6 6 0 0 1 6-6z"/>
            </svg>
        """.trimIndent()
        val png = SvgIconRenderer.render(svg)
        readPng(png).width shouldBe 320
    }

    "throws on invalid svg" {
        shouldThrow<IllegalArgumentException> { SvgIconRenderer.render("<svg></svg>") }
        shouldThrow<IllegalArgumentException> {
            SvgIconRenderer.render("""<svg><path d="M12 2 ZX3"/></svg>""")
        }
    }
})