package com.maxlass.studio.pack.format

import com.maxlass.studio.pack.format.utils.LuniiImagePrepare
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class LuniiImagePrepareTest : StringSpec({

    fun readPng(data: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(data)) ?: error("invalid PNG")

    fun solidRgbPng(width: Int, height: Int, rgb: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                img.setRGB(x, y, rgb)
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }

    fun cutoutPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                img.setRGB(x, y, 0x00000000)
            }
        }
        for (y in height / 4 until 3 * height / 4) {
            for (x in width / 4 until 3 * width / 4) {
                img.setRGB(x, y, 0xFFFFFFFF.toInt())
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }

    "prepare returns 320x240 PNG from arbitrary size color image" {
        val png = LuniiImagePrepare.prepare(solidRgbPng(800, 600, 0xFF4488))
        val image = readPng(png)
        image.width shouldBe 320
        image.height shouldBe 240
    }

    "prepare quantizes to a short grayscale palette" {
        val png = LuniiImagePrepare.prepare(solidRgbPng(100, 80, 0x3366CC))
        val image = readPng(png)
        val colors = mutableSetOf<Int>()
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                colors.add(image.getRGB(x, y) and 0xFFFFFF)
            }
        }
        colors.size shouldBeGreaterThan 0
        (colors.size <= 5) shouldBe true
        colors.all { c ->
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            r == g && g == b
        } shouldBe true
    }

    "prepare letterboxes with black margins for wide images" {
        val png = LuniiImagePrepare.prepare(solidRgbPng(640, 100, 0xFFFFFF))
        val image = readPng(png)
        var blackTop = 0
        for (x in 0 until image.width) {
            if ((image.getRGB(x, 0) and 0xFFFFFF) == 0) blackTop++
        }
        blackTop shouldBeGreaterThan image.width / 2
    }

    "prepare adds white stroke around alpha cutouts" {
        val png = LuniiImagePrepare.prepare(cutoutPng(200, 200))
        val image = readPng(png)
        var white = 0
        var black = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                when (image.getRGB(x, y) and 0xFFFFFF) {
                    0xFFFFFF -> white++
                    0x000000 -> black++
                }
            }
        }
        white shouldBeGreaterThan 0
        black shouldBeGreaterThan 0
    }

    "prepare removes solid white background around a subject" {
        val img = BufferedImage(200, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) {
            for (x in 0 until 200) {
                img.setRGB(x, y, 0xFFFFFF)
            }
        }
        for (y in 60 until 180) {
            for (x in 50 until 150) {
                img.setRGB(x, y, 0xCC2244)
            }
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", baos)
        val prepared = readPng(LuniiImagePrepare.prepare(baos.toByteArray()))

        var black = 0
        var nonBlack = 0
        for (y in 0 until prepared.height step 2) {
            for (x in 0 until prepared.width step 2) {
                val c = prepared.getRGB(x, y) and 0xFFFFFF
                if (c == 0) black++ else nonBlack++
            }
        }
        black shouldBeGreaterThan nonBlack
        nonBlack shouldBeGreaterThan 0

        var leftBlack = 0
        for (y in 0 until prepared.height) {
            if ((prepared.getRGB(0, y) and 0xFFFFFF) == 0) leftBlack++
        }
        leftBlack shouldBeGreaterThan prepared.height * 3 / 4
    }

    "removeSolidBackground clears white border while keeping the subject opaque" {
        val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                img.setRGB(x, y, 0xF8F8F8)
            }
        }
        for (y in 30 until 70) {
            for (x in 30 until 70) {
                img.setRGB(x, y, 0x2266AA)
            }
        }
        val cut = LuniiImagePrepare.removeSolidBackground(img)
        ((cut.getRGB(0, 0) ushr 24) and 0xFF) shouldBe 0
        ((cut.getRGB(50, 50) ushr 24) and 0xFF) shouldBeGreaterThan 200
    }

    "palette tones stay within the fixed set" {
        val allowed = setOf(0x000000, 0x404040, 0x808080, 0xC0C0C0, 0xFFFFFF)
        val img = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            for (x in 0 until 320) {
                val v = (x * 255 / 319)
                g.color = Color(v, v, v)
                g.drawLine(x, 0, x, 239)
            }
        } finally {
            g.dispose()
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", baos)
        val prepared = readPng(LuniiImagePrepare.prepare(baos.toByteArray()))
        val found = mutableSetOf<Int>()
        for (y in 0 until 240 step 4) {
            for (x in 0 until 320 step 4) {
                found.add(prepared.getRGB(x, y) and 0xFFFFFF)
            }
        }
        found.all { it in allowed } shouldBe true
        found.size shouldBeGreaterThan 1
    }
})
