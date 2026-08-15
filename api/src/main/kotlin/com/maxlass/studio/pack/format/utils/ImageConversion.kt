package com.maxlass.studio.pack.format.utils

import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.awt.image.RenderedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Arrays
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter

/**
 * Image conversion helpers (BMP <-> PNG, 4-bpp RLE BMP for FS packs), mirroring
 * `studio.core.v1.utils.ImageConversion` with a pure-Kotlin quantizer.
 */
object ImageConversion {

    private const val BITMAP_FORMAT = "BMP"
    private const val PNG_FORMAT = "PNG"
    private const val BITMAP_RLE4_COMPRESSION = "BI_RLE4"

    fun anyToBitmap(data: ByteArray): ByteArray = convertImage(data, BITMAP_FORMAT)

    fun bitmapToPng(bmpData: ByteArray): ByteArray = convertImage(bmpData, PNG_FORMAT)

    fun convertImage(data: ByteArray, format: String): ByteArray {
        val inputImage = ImageIO.read(ByteArrayInputStream(data))
            ?: throw IOException("Failed to read image")
        val redrawn = redrawImage(inputImage)
        val output = ByteArrayOutputStream()
        if (!ImageIO.write(redrawn as RenderedImage, format, output)) {
            throw IOException("Failed to convert image")
        }
        if (output.size() == 0) throw IOException("Failed to convert image")
        return output.toByteArray()
    }

    /** Converts any readable image into a 4-bpp RLE-compressed BMP (Lunii FS format). */
    fun anyToRLECompressedBitmap(data: ByteArray): ByteArray {
        val inputImage = ImageIO.read(ByteArrayInputStream(data))
            ?: throw IOException("Failed to read image")
        val redrawn = redrawIndexedImage(inputImage)
        val output = ByteArrayOutputStream()
        val writer: ImageWriter = ImageIO.getImageWritersByFormatName(BITMAP_FORMAT).next()
        try {
            val writeParam = writer.defaultWriteParam
            writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
            writeParam.compressionType = BITMAP_RLE4_COMPRESSION
            writer.output = ImageIO.createImageOutputStream(output)
            writer.write(null, IIOImage(redrawn, null, null), writeParam)
        } finally {
            writer.dispose()
        }
        if (output.size() == 0) throw IOException("Failed to convert image")
        return fixRle4Padding(output.toByteArray())
    }

    private fun redrawImage(inputImage: BufferedImage): BufferedImage {
        val redrawn = BufferedImage(inputImage.width, inputImage.height, BufferedImage.TYPE_INT_RGB)
        val g2d = redrawn.createGraphics()
        try {
            g2d.color = Color.BLACK
            g2d.fillRect(0, 0, redrawn.width, redrawn.height)
            g2d.drawImage(inputImage, 0, 0, null)
        } finally {
            g2d.dispose()
        }
        return redrawn
    }

    /** Quantizes to 16 colors then redraws onto a 4-bpp indexed image. */
    private fun redrawIndexedImage(inputImage: BufferedImage): BufferedImage {
        val quantized = ColorQuantizer.quantizeToIndexed(inputImage, 16)
        val rgb = quantized.getRGB(0, 0, quantized.width, quantized.height, null, 0, quantized.width)
        val cmap = Arrays.stream(rgb).distinct().toArray()
        val cmap16 = Arrays.copyOf(cmap, 16)
        val redrawn = BufferedImage(
            quantized.width,
            quantized.height,
            BufferedImage.TYPE_BYTE_INDEXED,
            IndexColorModel(4, cmap16.size, cmap16, 0, false, -1, 0),
        )
        val g2d = redrawn.createGraphics()
        try {
            g2d.fillRect(0, 0, redrawn.width, redrawn.height)
            g2d.drawImage(quantized, 0, 0, null)
        } finally {
            g2d.dispose()
        }
        return redrawn
    }

    /** Fixes the RLE4 padding produced by the JDK BMP writer for the Lunii device. */
    private fun fixRle4Padding(image: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val bb = ByteBuffer.wrap(image)
        for (i in 0 until 118) {
            baos.write(bb.get().toInt())
        }
        while (bb.hasRemaining()) {
            val b1 = bb.get()
            val b2 = bb.get()
            baos.write(b1.toInt())
            baos.write(b2.toInt())
            if (b1.toInt() != 0 || (b2.toInt() and 0xFF) <= 2) continue
            val length = b2.toInt() and 0xFF
            val lengthInBytes = Math.ceil(length / 2.0).toInt()
            for (i in 0 until lengthInBytes) {
                baos.write(bb.get().toInt())
            }
            val wrongByteLength = length / 2
            when {
                wrongByteLength % 2 == 0 && lengthInBytes % 2 == 1 -> baos.write(0)
                wrongByteLength % 2 == 1 && lengthInBytes % 2 == 0 -> bb.get()
                lengthInBytes % 2 != 1 -> {}
                else -> baos.write(bb.get().toInt())
            }
        }
        return baos.toByteArray()
    }
}
