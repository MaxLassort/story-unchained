package com.maxlass.studio.pack.adapter

import com.maxlass.studio.pack.domain.PACK_EXT_RAW
import com.maxlass.studio.pack.domain.PACK_EXT_ZIP
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.format.model.StoryPack
import com.maxlass.studio.pack.format.reader.ArchiveStoryPackReader
import com.maxlass.studio.pack.format.reader.BinaryStoryPackReader
import com.maxlass.studio.pack.format.reader.FsStoryPackReader
import com.maxlass.studio.pack.format.utils.ImageConversion
import com.maxlass.studio.pack.format.utils.PackAssetsCompression
import com.maxlass.studio.pack.format.writer.ArchiveStoryPackWriter
import com.maxlass.studio.pack.format.writer.BinaryStoryPackWriter
import com.maxlass.studio.pack.format.writer.FsStoryPackWriter
import com.maxlass.studio.pack.port.external.PackFormatConverterPort
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

/**
 * Conversion adapter based on studio-core readers/writers.
 */
class StudioCorePackFormatConverterAdapter : PackFormatConverterPort {

    companion object {
        private val logger = LoggerFactory.getLogger(StudioCorePackFormatConverterAdapter::class.java)
    }

    private val archiveReader = ArchiveStoryPackReader()
    private val binaryReader = BinaryStoryPackReader()
    private val fsReader = FsStoryPackReader()

    private val archiveWriter = ArchiveStoryPackWriter()
    private val binaryWriter = BinaryStoryPackWriter()
    private val fsWriter = FsStoryPackWriter()

    override fun convert(
        sourcePath: Path,
        sourceFormat: PackFormat,
        targetFormat: PackFormat,
        destinationDir: Path
    ): Path {
        require(sourceFormat != targetFormat) { "Source and target formats must differ: $sourceFormat" }
        val pack = readByFormat(sourcePath, sourceFormat)

        val packForTarget = when (targetFormat) {
            PackFormat.RAW -> uncompressIfNeeded(pack)
            PackFormat.FS -> {
                // RAW and ARCHIVE need firmware2.4 asset preparation when converting to FS.
                val prepared: StoryPack = if (sourceFormat == PackFormat.FS) pack
                    else PackAssetsCompression.withPreparedAssetsFirmware2dot4(pack)
                conformFsImages(prepared)
                prepared
            }
            PackFormat.ARCHIVE -> {
                // RAW is written to archive with compressed assets; FS packs are already OK for archive writer.
                if (sourceFormat == PackFormat.RAW) {
                    PackAssetsCompression.withCompressedAssets(pack)
                } else {
                    // FS images use the Lunii-specific 4bpp RLE BMP format that standard image
                    // viewers cannot open. Convert them to PNG so the produced archive is usable.
                    conformArchiveImages(pack)
                    pack
                }
            }
            PackFormat.UNKNOWN -> throw IllegalArgumentException("Unknown target format: $targetFormat")
        }

        return writeByFormat(packForTarget, targetFormat, destinationDir)
    }

    private fun readByFormat(sourcePath: Path, sourceFormat: PackFormat): StoryPack = when (sourceFormat) {
        PackFormat.ARCHIVE -> readArchive(sourcePath)
        PackFormat.RAW -> readRaw(sourcePath)
        PackFormat.FS -> readFs(sourcePath)
        PackFormat.UNKNOWN -> throw IllegalArgumentException("Unknown source format: $sourceFormat")
    }

    private fun uncompressIfNeeded(pack: StoryPack): StoryPack =
        if (PackAssetsCompression.hasCompressedAssets(pack)) {
            PackAssetsCompression.withUncompressedAssets(pack)
        } else pack

    private fun conformFsImages(pack: StoryPack) {
        val convertedCache = mutableMapOf<ByteArray, ByteArray>()
        pack.stageNodes.orEmpty().forEach { node ->
            val image = node.image ?: return@forEach
            val raw = image.rawData ?: return@forEach
            if (isFsCompliantBmp(raw)) return@forEach
            val converted = convertedCache.getOrPut(raw) {
                runCatching {
                    val scaled = scaleTo320x240(raw) ?: return@getOrPut raw
                    ImageConversion.anyToRLECompressedBitmap(scaled)
                }.getOrElse { raw }
            }
            if (converted !== raw) {
                image.rawData = converted
                image.mimeType = "image/bmp"
            }
        }
    }

    private fun isFsCompliantBmp(bytes: ByteArray): Boolean {
        if (bytes.size < 34) return false
        return readIntLE(bytes, 18) == 320 && readIntLE(bytes, 22) == 240 &&
            readShortLE(bytes, 28) == 4 && readIntLE(bytes, 30) == 2
    }

    private fun conformArchiveImages(pack: StoryPack) {
        val convertedCache = mutableMapOf<ByteArray, ByteArray>()
        pack.stageNodes.orEmpty().forEach { node ->
            val image = node.image ?: return@forEach
            if (image.mimeType != "image/bmp") return@forEach
            val raw = image.rawData ?: return@forEach
            val converted = convertedCache.getOrPut(raw) {
                runCatching { ImageConversion.bitmapToPng(raw) }.getOrElse { raw }
            }
            if (converted !== raw) {
                image.rawData = converted
                image.mimeType = "image/png"
            }
        }
    }

    private fun scaleTo320x240(bytes: ByteArray): ByteArray? = runCatching {
        val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@runCatching null
        val canvas = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = canvas.createGraphics()
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, 320, 240)
            val scale = minOf(320f / source.width, 240f / source.height)
            val w = (source.width * scale).toInt().coerceAtLeast(1)
            val h = (source.height * scale).toInt().coerceAtLeast(1)
            g.drawImage(source, (320 - w) / 2, (240 - h) / 2, w, h, null)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(canvas, "bmp", out)
        out.toByteArray()
    }.getOrNull()

    private fun readIntLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun writeByFormat(pack: StoryPack, targetFormat: PackFormat, destinationDir: Path): Path = when (targetFormat) {
        PackFormat.RAW -> writeRaw(pack, destinationDir)
        PackFormat.ARCHIVE -> writeArchive(pack, destinationDir)
        PackFormat.FS -> writeFs(pack, destinationDir)
        PackFormat.UNKNOWN -> throw IllegalArgumentException("Unknown target format: $targetFormat")
    }

    private fun readArchive(path: Path): StoryPack =
        FileInputStream(path.toFile()).use { archiveReader.read(it) }

    private fun readRaw(path: Path): StoryPack =
        FileInputStream(path.toFile()).use { binaryReader.read(it) }

    private fun readFs(path: Path): StoryPack = fsReader.read(path)

    private fun writeRaw(pack: StoryPack, destinationDir: Path): Path {
        val tmp = Files.createTempFile("studio_kmp_convert_", ".$PACK_EXT_RAW")
        FileOutputStream(tmp.toFile()).use { binaryWriter.write(pack, it, false) }
        val destination = destinationDir.resolve("${pack.uuid}.converted_${System.currentTimeMillis()}.$PACK_EXT_RAW")
        logger.info("Converted pack RAW -> {}", destination)
        return Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeArchive(pack: StoryPack, destinationDir: Path): Path {
        val tmp = Files.createTempFile("studio_kmp_convert_", ".$PACK_EXT_ZIP")
        FileOutputStream(tmp.toFile()).use { archiveWriter.write(pack, it) }
        val destination = destinationDir.resolve("${pack.uuid}.converted_${System.currentTimeMillis()}.$PACK_EXT_ZIP")
        logger.info("Converted pack ARCHIVE -> {}", destination)
        return Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeFs(pack: StoryPack, destinationDir: Path): Path {
        val tmpRoot = Files.createTempDirectory("studio_kmp_convert_fs_")
        val generatedFolder = fsWriter.write(pack, tmpRoot)
        val destination = destinationDir.resolve("${pack.uuid}.converted_${System.currentTimeMillis()}")
        logger.info("Converted pack FS -> {}", destination)
        val moved = Files.move(generatedFolder, destination, StandardCopyOption.REPLACE_EXISTING)
        runCatching { Files.deleteIfExists(tmpRoot) }
            .onFailure { logger.debug("Failed to cleanup temp directory {}", tmpRoot.fileName) }
        return moved
    }
}
