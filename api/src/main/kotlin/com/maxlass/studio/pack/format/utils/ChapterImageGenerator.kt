package com.maxlass.studio.pack.format.utils

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Generates the fallback image for a chapter node: the white chapter number centered
 * on a black background (Lunii format: PNG 320x240).
 */
object ChapterImageGenerator {

    const val DEFAULT_WIDTH = 320
    const val DEFAULT_HEIGHT = 240

    /** Renders [chapterNumber] (white, bold, centered) onto a black [width]x[height] canvas, as PNG. */
    fun generate(
        chapterNumber: Int,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
    ): ByteArray {
        require(chapterNumber >= 1) { "chapterNumber must be >= 1, got $chapterNumber" }
        require(width > 0 && height > 0) { "width and height must be positive, got ${width}x${height}" }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()
        try {
            g2d.color = Color.BLACK
            g2d.fillRect(0, 0, width, height)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val text = chapterNumber.toString()
            val fontSize = (height * 0.55).toInt().coerceAtLeast(12)
            g2d.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize)

            val bounds = g2d.fontMetrics.getStringBounds(text, g2d)
            val x = ((width - bounds.width) / 2.0 - bounds.x).toInt()
            val y = ((height - bounds.height) / 2.0 - bounds.y).toInt()

            g2d.color = Color.WHITE
            g2d.drawString(text, x, y)
        } finally {
            g2d.dispose()
        }

        val output = ByteArrayOutputStream()
        if (!ImageIO.write(image, "PNG", output)) throw IllegalStateException("Failed to write PNG")
        return output.toByteArray()
    }
}