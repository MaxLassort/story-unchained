package com.maxlass.studio.pack.format.utils

import com.maxlass.studio.pack.format.model.StageNode
import com.maxlass.studio.pack.format.model.StoryPack
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem

/**
 * Asset preparation (image/audio conversion + compression) for the different pack targets,
 * mirroring `studio.core.v1.utils.PackAssetsCompression`.
 */
object PackAssetsCompression {

    private val log = LoggerFactory.getLogger(PackAssetsCompression::class.java)

    /** True when any asset is not in its "raw device" representation (BMP image / WAV audio). */
    fun hasCompressedAssets(pack: StoryPack): Boolean {
        for (node in pack.stageNodes.orEmpty()) {
            val image = node.image
            if (image != null && image.mimeType != "image/bmp") return true
            val audio = node.audio
            if (audio != null && audio.mimeType != "audio/x-wav") return true
        }
        return false
    }

    /** RAW -> ARCHIVE: converts BMP->PNG images and WAV->OGG audio. */
    fun withCompressedAssets(pack: StoryPack): StoryPack {
        val assets = sortedMapOf<String, ByteArray>()
        for (node in pack.stageNodes.orEmpty()) {
            node.image?.let { image ->
                val imageData = image.rawData ?: return@let
                val assetHash = BytesUtils.sha1Hex(imageData)
                if (assets[assetHash] == null) {
                    val converted = if (image.mimeType == "image/bmp") {
                        ImageConversion.bitmapToPng(imageData)
                    } else {
                        imageData
                    }
                    assets[assetHash] = converted
                }
                image.rawData = assets.getValue(assetHash)
                if (image.mimeType == "image/bmp") image.mimeType = "image/png"
            }
            node.audio?.let { audio ->
                val audioData = audio.rawData ?: return@let
                val assetHash = BytesUtils.sha1Hex(audioData)
                if (assets[assetHash] == null) {
                    val converted = if (audio.mimeType == "audio/x-wav") {
                        try {
                            AudioConversion.waveToOgg(audioData)
                        } catch (e: UnsupportedOperationException) {
                            // OGG encoding unavailable (vorbis-java not resolvable): keep WAV.
                            log.warn("OGG encoding not available, keeping WAV asset: {}", e.message)
                            audioData
                        }
                    } else {
                        audioData
                    }
                    assets[assetHash] = converted
                }
                audio.rawData = assets.getValue(assetHash)
                if (audio.mimeType == "audio/x-wav") audio.mimeType = "audio/ogg"
            }
        }
        return pack
    }

    /** ARCHIVE -> RAW: converts PNG/JPG/4-bpp RLE BMP images and OGG/MP3 audio to WAV/BMP. */
    fun withUncompressedAssets(pack: StoryPack): StoryPack {
        val assets = sortedMapOf<String, ByteArray>()
        for (node in pack.stageNodes.orEmpty()) {
            node.image?.let { image ->
                val imageData = image.rawData ?: return@let
                val assetHash = BytesUtils.sha1Hex(imageData)
                if (assets[assetHash] == null) {
                    val converted = when (image.mimeType) {
                        "image/png", "image/jpeg" -> ImageConversion.anyToBitmap(imageData)
                        "image/bmp" -> {
                            if (imageData.size <= 30 || imageData[28].toInt() != 4 || imageData[30].toInt() != 2) {
                                imageData
                            } else {
                                ImageConversion.anyToBitmap(imageData)
                            }
                        }
                        else -> imageData
                    }
                    assets[assetHash] = converted
                }
                image.rawData = assets.getValue(assetHash)
                image.mimeType = "image/bmp"
            }
            node.audio?.let { audio ->
                val audioData = audio.rawData ?: return@let
                val assetHash = BytesUtils.sha1Hex(audioData)
                if (assets[assetHash] == null) {
                    val converted = when (audio.mimeType) {
                        "audio/ogg" -> AudioConversion.oggToWave(audioData)
                        "audio/mpeg" -> AudioConversion.mp3ToWave(audioData)
                        else -> audioData
                    }
                    assets[assetHash] = converted
                }
                audio.rawData = assets.getValue(assetHash)
                audio.mimeType = "audio/x-wav"
            }
        }
        return pack
    }

    /** ANY -> FS (firmware 2.4): converts images to 4-bpp RLE BMP and audio to mono 44.1 kHz MP3. */
    fun withPreparedAssetsFirmware2dot4(pack: StoryPack): StoryPack {
        val assets = sortedMapOf<String, ByteArray>()
        for (node in pack.stageNodes.orEmpty()) {
            node.image?.let { image ->
                val imageData = image.rawData ?: return@let
                val assetHash = BytesUtils.sha1Hex(imageData)
                if (assets[assetHash] == null) {
                    val isRle4Bmp = image.mimeType == "image/bmp" &&
                        imageData.size > 30 && imageData[28].toInt() == 4 && imageData[30].toInt() == 2
                    val converted = if (isRle4Bmp) imageData else ImageConversion.anyToRLECompressedBitmap(imageData)
                    assets[assetHash] = converted
                }
                image.rawData = assets.getValue(assetHash)
                image.mimeType = "image/bmp"
            }
            node.audio?.let { audio ->
                val audioData = audio.rawData ?: return@let
                val assetHash = BytesUtils.sha1Hex(audioData)
                if (assets[assetHash] == null) {
                    val converted = if (audio.mimeType == "audio/mp3" || audio.mimeType == "audio/mpeg") {
                        prepareMp3(audioData)
                    } else {
                        AudioConversion.anyToMp3(audioData)
                    }
                    assets[assetHash] = converted
                }
                audio.rawData = assets.getValue(assetHash)
                audio.mimeType = "audio/mpeg"
            }
        }
        return pack
    }

    /** Strips ID3 tags and re-encodes the MP3 when it is not mono / 44.1 kHz. */
    private fun prepareMp3(audioData: ByteArray): ByteArray {
        var mp3 = Id3Tags.removeId3v1Tag(audioData)
        mp3 = Id3Tags.removeId3v2Tag(mp3)
        val format = AudioSystem.getAudioFileFormat(ByteArrayInputStream(mp3)).format
        return if (format.channels != 1 || format.sampleRate != 44100f) {
            AudioConversion.anyToMp3(mp3)
        } else {
            mp3
        }
    }
}
