package com.maxlass.studio.pack.format

import com.maxlass.studio.pack.format.utils.SvgIconRenderer
import com.maxlass.studio.pack.service.ChapterIconCatalogService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class BundledIconsRenderTest : StringSpec({

    val catalog = ChapterIconCatalogService()

    "all bundled icons render as 320x240 PNG with white pixels" {
        val icons = catalog.listIcons()
        icons.size shouldBe 4
        for (icon in icons) {
            val svg = kotlinx.coroutines.runBlocking { catalog.loadIcon(icon.id) }!!
            val png = SvgIconRenderer.render(svg)
            val image = ImageIO.read(ByteArrayInputStream(png)) ?: error("invalid PNG for ${icon.id}")
            image.width shouldBe 320
            image.height shouldBe 240
            var white = 0
            for (x in 0 until image.width step 8) {
                for (y in 0 until image.height step 8) {
                    if ((image.getRGB(x, y) and 0xFFFFFF) == 0xFFFFFF) white++
                }
            }
            if (white == 0) error("no white pixels for icon ${icon.id}")
        }
    }
})