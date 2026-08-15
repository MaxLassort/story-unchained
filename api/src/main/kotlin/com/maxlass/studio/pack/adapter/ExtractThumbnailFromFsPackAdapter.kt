package com.maxlass.studio.pack.adapter

import com.maxlass.studio.pack.format.reader.FsStoryPackReader
import com.maxlass.studio.pack.port.external.ExtractThumbnailFromFsPackPort
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Extracts the first image from an FS pack (BMP), converts to PNG, returns bytes.
 */
class ExtractThumbnailFromFsPackAdapter : ExtractThumbnailFromFsPackPort {

    companion object {
        private val log = LoggerFactory.getLogger(ExtractThumbnailFromFsPackAdapter::class.java)
    }

    private val fsReader = FsStoryPackReader()

    override fun extractThumbnail(packPath: Path): ByteArray? {
        return runCatching {
            val storyPack = fsReader.read(packPath)
            val firstImageBytes = storyPack.stageNodes
                ?.asSequence()
                ?.mapNotNull { it.image?.rawData }
                ?.firstOrNull()
                ?: return@runCatching null
            bmpToPng(firstImageBytes)
        }.getOrElse {
            log.debug("Could not extract thumbnail for pack at {}: {}", packPath, it.message)
            null
        }
    }

    private fun bmpToPng(bmpBytes: ByteArray): ByteArray? {
        return runCatching {
            ByteArrayInputStream(bmpBytes).use { input ->
                val image: BufferedImage = ImageIO.read(input) ?: return@runCatching null
                ByteArrayOutputStream().use { output ->
                    if (!ImageIO.write(image, "png", output)) return@runCatching null
                    output.toByteArray()
                }
            }
        }.getOrNull()
    }
}
