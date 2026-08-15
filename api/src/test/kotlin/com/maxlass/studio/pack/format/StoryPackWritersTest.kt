package com.maxlass.studio.pack.format

import com.maxlass.studio.pack.format.model.ActionNode
import com.maxlass.studio.pack.format.model.AudioAsset
import com.maxlass.studio.pack.format.model.ControlSettings
import com.maxlass.studio.pack.format.model.EnrichedPackMetadata
import com.maxlass.studio.pack.format.model.ImageAsset
import com.maxlass.studio.pack.format.model.StageNode
import com.maxlass.studio.pack.format.model.StoryPack
import com.maxlass.studio.pack.format.model.Transition
import com.maxlass.studio.pack.format.reader.ArchiveStoryPackReader
import com.maxlass.studio.pack.format.reader.BinaryStoryPackReader
import com.maxlass.studio.pack.format.reader.FsStoryPackReader
import com.maxlass.studio.pack.format.utils.ImageConversion
import com.maxlass.studio.pack.format.writer.ArchiveStoryPackWriter
import com.maxlass.studio.pack.format.writer.BinaryStoryPackWriter
import com.maxlass.studio.pack.format.writer.BlankMp3
import com.maxlass.studio.pack.format.writer.FsStoryPackWriter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

class StoryPackWritersTest : StringSpec({

    val archiveReader = ArchiveStoryPackReader()
    val binaryReader = BinaryStoryPackReader()
    val fsReader = FsStoryPackReader()
    val archiveWriter = ArchiveStoryPackWriter()
    val binaryWriter = BinaryStoryPackWriter()
    val fsWriter = FsStoryPackWriter()

    fun bmpImage(width: Int = 64, height: Int = 64, red: Boolean = true): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) {
            for (y in 0 until height) {
                img.setRGB(x, y, if (red) 0xFF0000.toInt() else (x * 4) shl 8 or y)
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "bmp", out)
        return out.toByteArray()
    }

    fun sineWaveMono8000(): ByteArray {
        val sampleRate = 8000
        val numSamples = sampleRate
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val audioData = ByteArray(numSamples * 2)
        for (i in 0 until numSamples) {
            val sample = (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * Short.MAX_VALUE).toInt().toShort()
            audioData[i * 2] = (sample.toInt() and 0xFF).toByte()
            audioData[i * 2 + 1] = (sample.toInt() ushr 8).toByte()
        }
        val bais = AudioInputStream(ByteArrayInputStream(audioData), format, numSamples.toLong())
        val baos = ByteArrayOutputStream()
        AudioSystem.write(bais, AudioFileFormat.Type.WAVE, baos)
        return baos.toByteArray()
    }

    fun blankMp3(): ByteArray {
        val hex = BlankMp3.HEX
        return ByteArray(hex.length / 2) { i -> ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte() }
    }

    fun samplePack(image: ImageAsset?, audio: AudioAsset?): StoryPack {
        val stage2 = StageNode(
            uuid = "22222222-2222-2222-2222-222222222222",
            image = null,
            audio = null,
            okTransition = null,
            homeTransition = null,
            controlSettings = ControlSettings(true, true, true, true, false),
            enriched = null,
        )
        val actionNode = ActionNode(listOf(stage2), null)
        val stage1 = StageNode(
            uuid = "11111111-1111-1111-1111-111111111111",
            image = image,
            audio = audio,
            okTransition = Transition(actionNode, 0),
            homeTransition = null,
            controlSettings = ControlSettings(true, true, false, true, false),
            enriched = null,
        )
        return StoryPack(
            uuid = stage1.uuid,
            factoryDisabled = false,
            version = 1,
            stageNodes = listOf(stage1, stage2),
            enriched = EnrichedPackMetadata("Titre", "Description"),
            nightModeAvailable = true,
        )
    }

    "archive: writer output round-trips through the reader" {
        val pack = samplePack(
            image = ImageAsset("image/png", bmpImage(), "img.png"),
            audio = AudioAsset("audio/x-wav", sineWaveMono8000(), "a.wav"),
        )

        val bytes = ByteArrayOutputStream()
        archiveWriter.write(pack, bytes)

        val reread = archiveReader.read(ByteArrayInputStream(bytes.toByteArray()))
        reread.uuid shouldBe pack.uuid
        reread.version shouldBe 1
        reread.nightModeAvailable shouldBe true
        reread.enriched!!.title shouldBe "Titre"
        reread.enriched!!.description shouldBe "Description"
        reread.stageNodes!!.size shouldBe 2
        val s1 = reread.stageNodes!![0]
        s1.uuid shouldBe pack.uuid
        s1.image!!.mimeType shouldBe "image/png"
        s1.image!!.rawData shouldBe pack.stageNodes!![0].image!!.rawData
        s1.audio!!.mimeType shouldBe "audio/x-wav"
        s1.audio!!.rawData shouldBe pack.stageNodes!![0].audio!!.rawData
        s1.okTransition!!.actionNode!!.options!!.size shouldBe 1
        s1.okTransition!!.actionNode!!.options!![0].uuid shouldBe "22222222-2222-2222-2222-222222222222"
        s1.controlSettings!!.homeEnabled shouldBe false
    }

    "binary: writer output round-trips through the reader" {
        val pack = samplePack(
            image = ImageAsset("image/bmp", bmpImage(), "img.bmp"),
            audio = AudioAsset("audio/x-wav", sineWaveMono8000(), "a.wav"),
        )

        val bytes = ByteArrayOutputStream()
        binaryWriter.write(pack, bytes, false)

        val reread = binaryReader.read(ByteArrayInputStream(bytes.toByteArray()))
        reread.uuid shouldBe pack.uuid
        reread.version shouldBe 1
        reread.stageNodes!!.size shouldBe 2
        val s1 = reread.stageNodes!![0]
        s1.uuid shouldBe pack.uuid
        s1.image!!.mimeType shouldBe "image/bmp"
        // Binary assets are stored in whole 512-byte sectors: the reader returns them zero-padded.
        s1.image!!.rawData!!.take(pack.stageNodes!![0].image!!.rawData!!.size) shouldBe pack.stageNodes!![0].image!!.rawData
        s1.audio!!.mimeType shouldBe "audio/x-wav"
        s1.audio!!.rawData!!.take(pack.stageNodes!![0].audio!!.rawData!!.size) shouldBe pack.stageNodes!![0].audio!!.rawData
        s1.controlSettings!!.wheelEnabled shouldBe true
        s1.controlSettings!!.homeEnabled shouldBe false
        s1.okTransition!!.actionNode!!.options!!.size shouldBe 1
    }

    "fs: writer output round-trips through the reader" {
        val rleBmp = ImageConversion.anyToRLECompressedBitmap(bmpImage(320, 240))
        val pack = samplePack(
            image = ImageAsset("image/bmp", rleBmp, "img.bmp"),
            audio = AudioAsset("audio/mpeg", blankMp3(), "a.mp3"),
        )

        val tempDir = Files.createTempDirectory("fs-writer")
        val packFolder = fsWriter.write(pack, tempDir)

        File(packFolder.toFile(), ".cleartext").exists() shouldBe true

        val reread = fsReader.read(packFolder)
        reread.uuid shouldBe "11111111"
        reread.nightModeAvailable shouldBe true
        reread.stageNodes!!.size shouldBe 2
        val s1 = reread.stageNodes!![0]
        s1.image!!.mimeType shouldBe "image/bmp"
        s1.image!!.rawData shouldBe rleBmp
        s1.audio!!.mimeType shouldBe "audio/mpeg"
        s1.audio!!.rawData shouldBe blankMp3()
        s1.controlSettings!!.wheelEnabled shouldBe true

        tempDir.toFile().deleteRecursively()
    }

    "hasCompressedAssets reflects image/audio mime types" {
        val raw = samplePack(
            image = ImageAsset("image/bmp", bmpImage(), "i.bmp"),
            audio = AudioAsset("audio/x-wav", sineWaveMono8000(), "a.wav"),
        )
        com.maxlass.studio.pack.format.utils.PackAssetsCompression.hasCompressedAssets(raw) shouldBe false

        val compressed = samplePack(
            image = ImageAsset("image/png", bmpImage(), "i.png"),
            audio = AudioAsset("audio/ogg", sineWaveMono8000(), "a.ogg"),
        )
        com.maxlass.studio.pack.format.utils.PackAssetsCompression.hasCompressedAssets(compressed) shouldBe true
    }
})
