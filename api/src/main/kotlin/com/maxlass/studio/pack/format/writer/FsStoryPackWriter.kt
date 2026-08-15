package com.maxlass.studio.pack.format.writer

import com.maxlass.studio.pack.format.model.ActionNode
import com.maxlass.studio.pack.format.model.AudioAsset
import com.maxlass.studio.pack.format.model.ImageAsset
import com.maxlass.studio.pack.format.model.StageNode
import com.maxlass.studio.pack.format.model.StoryPack
import com.maxlass.studio.pack.format.utils.BytesUtils
import com.maxlass.studio.pack.format.utils.Id3Tags
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import javax.sound.sampled.AudioSystem

/**
 * Pure-Kotlin writer for FS packs (folder layout on a Lunii device), mirroring
 * `studio.core.v1.writer.fs.FsStoryPackWriter`.
 */
class FsStoryPackWriter {

    companion object {
        const val NODE_INDEX_FILENAME = "ni"
        const val LIST_INDEX_FILENAME = "li"
        const val IMAGE_INDEX_FILENAME = "ri"
        const val IMAGE_FOLDER = "rf/"
        const val SOUND_INDEX_FILENAME = "si"
        const val SOUND_FOLDER = "sf/"
        const val BOOT_FILENAME = "bt"
        const val NIGHT_MODE_FILENAME = "nm"
        const val CLEARTEXT_FILENAME = ".cleartext"
    }

    private val blankMp3: ByteArray by lazy {
        val hex = BlankMp3.HEX
        ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    fun write(pack: StoryPack, outputFolder: Path): Path {
        val packFolder = File(outputFolder.toFile(), transformUuid(UUID.fromString(pack.uuid)))
        packFolder.mkdirs()
        if (pack.nightModeAvailable) {
            File(packFolder, NIGHT_MODE_FILENAME).createNewFile()
        }
        File(packFolder, CLEARTEXT_FILENAME).createNewFile()

        val assets = sortedMapOf<String, ByteArray>()
        val actionNodesOrdered = mutableListOf<ActionNode>()
        val actionNodesIndexes = mutableMapOf<ActionNode, Int>()
        var nextActionNodeIndex = 0
        val imageHashOrdered = mutableListOf<String>()
        val audioHashOrdered = mutableListOf<String>()

        val niDos = DataOutputStream(FileOutputStream(File(packFolder, NODE_INDEX_FILENAME)))
        try {
            val bb = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
            bb.putShort(1)
            bb.putShort(pack.version)
            bb.putInt(512)
            bb.putInt(44)
            bb.putInt(pack.stageNodes.orEmpty().size)
            bb.putInt(pack.stageNodes.orEmpty().count { it.image != null })
            bb.putInt(pack.stageNodes.orEmpty().count { it.audio != null })
            bb.put(1)
            bb.put(ByteArray(487))
            niDos.write(bb.array())

            for (node in pack.stageNodes.orEmpty()) {
                var imageIndex = -1
                val image = node.image
                if (image != null) {
                    val imageData = image.rawData ?: continue
                    val imageHash = BytesUtils.sha1Hex(imageData)
                    if (imageHash !in imageHashOrdered) {
                        require(image.mimeType == "image/bmp") { "FS pack file requires image assets to be BMP." }
                        requireIsRle4Bmp(imageData)
                        imageIndex = imageHashOrdered.size
                        imageHashOrdered.add(imageHash)
                        assets.putIfAbsent(imageHash, imageData)
                    } else {
                        imageIndex = imageHashOrdered.indexOf(imageHash)
                    }
                }

                var audioIndex = -1
                var audio = node.audio
                if (audio == null) {
                    audio = AudioAsset("audio/mpeg", blankMp3, "blank_audio_placeholder")
                }
                val audioData = audio.rawData ?: continue
                val audioHash = BytesUtils.sha1Hex(audioData)
                if (audioHash !in audioHashOrdered) {
                    require(audio.mimeType == "audio/mp3" || audio.mimeType == "audio/mpeg") {
                        "FS pack file requires audio assets to be MP3."
                    }
                    require(!Id3Tags.hasId3v1Tag(audioData) && !Id3Tags.hasId3v2Tag(audioData)) {
                        "FS pack file does not support ID3 tags in MP3 files."
                    }
                    val audioFileFormat = AudioSystem.getAudioFileFormat(ByteArrayInputStream(audioData))
                    require(audioFileFormat.format.channels == 1 && audioFileFormat.format.sampleRate == 44100f) {
                        "FS pack file requires MP3 audio assets to be MONO / 44100Hz."
                    }
                    audioIndex = audioHashOrdered.size
                    audioHashOrdered.add(audioHash)
                    assets.putIfAbsent(audioHash, audioData)
                } else {
                    audioIndex = audioHashOrdered.indexOf(audioHash)
                }

                node.okTransition?.let { transition ->
                    val actionNode = transition.actionNode!!
                    if (actionNode !in actionNodesOrdered) {
                        actionNodesOrdered.add(actionNode)
                        actionNodesIndexes[actionNode] = nextActionNodeIndex
                        nextActionNodeIndex += actionNode.options.orEmpty().size
                    }
                }
                node.homeTransition?.let { transition ->
                    val actionNode = transition.actionNode!!
                    if (actionNode !in actionNodesOrdered) {
                        actionNodesOrdered.add(actionNode)
                        actionNodesIndexes[actionNode] = nextActionNodeIndex
                        nextActionNodeIndex += actionNode.options.orEmpty().size
                    }
                }

                val controls = node.controlSettings!!
                writeStageNode(
                    niDos,
                    imageIndex,
                    audioIndex,
                    node.okTransition?.let { actionNodesIndexes[it.actionNode!!] ?: -1 } ?: -1,
                    node.okTransition?.let { it.actionNode!!.options.orEmpty().size } ?: -1,
                    node.okTransition?.let { it.optionIndex.toInt() } ?: -1,
                    node.homeTransition?.let { actionNodesIndexes[it.actionNode!!] ?: -1 } ?: -1,
                    node.homeTransition?.let { it.actionNode!!.options.orEmpty().size } ?: -1,
                    node.homeTransition?.let { it.optionIndex.toInt() } ?: -1,
                    controls.wheelEnabled,
                    controls.okEnabled,
                    controls.homeEnabled,
                    controls.pauseEnabled,
                    controls.autoJumpEnabled,
                )
            }
        } finally {
            niDos.close()
        }

        File(packFolder, LIST_INDEX_FILENAME).writeBytes(ByteArrayOutputStream().also { liBaos ->
            DataOutputStream(liBaos).use { liDos ->
                for (actionNode in actionNodesOrdered) {
                    writeActionNode(liDos, actionNode.options.orEmpty().map { option ->
                        pack.stageNodes.orEmpty().indexOf(option)
                    }.toIntArray())
                }
            }
        }.toByteArray())

        FileOutputStream(File(packFolder, IMAGE_INDEX_FILENAME)).use { riFos ->
            val riBaos = ByteArrayOutputStream()
            DataOutputStream(riBaos).use { riDos ->
                for (i in imageHashOrdered.indices) {
                    val rfPath = assetPathFromIndex(i)
                    riDos.write(rfPath.toByteArray(StandardCharsets.UTF_8))
                    val rfFile = File(packFolder, IMAGE_FOLDER + rfPath.replace('\\', '/'))
                    rfFile.parentFile.mkdirs()
                    FileOutputStream(rfFile).use { it.write(assets.getValue(imageHashOrdered[i])) }
                }
            }
            riFos.write(riBaos.toByteArray())
        }

        FileOutputStream(File(packFolder, SOUND_INDEX_FILENAME)).use { siFos ->
            val siBaos = ByteArrayOutputStream()
            DataOutputStream(siBaos).use { siDos ->
                for (i in audioHashOrdered.indices) {
                    val sfPath = assetPathFromIndex(i)
                    siDos.write(sfPath.toByteArray(StandardCharsets.UTF_8))
                    val sfFile = File(packFolder, SOUND_FOLDER + sfPath.replace('\\', '/'))
                    sfFile.parentFile.mkdirs()
                    FileOutputStream(sfFile).use { it.write(assets.getValue(audioHashOrdered[i])) }
                }
            }
            siFos.write(siBaos.toByteArray())
        }

        return packFolder.toPath()
    }

    private fun transformUuid(uuid: UUID): String {
        val uuidStr = uuid.toString().replace("-", "")
        return uuidStr.substring(uuidStr.length - 8).uppercase()
    }

    private fun writeStageNode(
        niDos: DataOutputStream,
        imageAssetIndexInRI: Int,
        soundAssetIndexInSI: Int,
        okTransitionActionNodeIndexInLI: Int,
        okTransitionNumberOfOptions: Int,
        okTransitionSelectedOptionIndex: Int,
        homeTransitionActionNodeIndexInLI: Int,
        homeTransitionNumberOfOptions: Int,
        homeTransitionSelectedOptionIndex: Int,
        wheel: Boolean,
        ok: Boolean,
        home: Boolean,
        pause: Boolean,
        autoplay: Boolean,
    ) {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(imageAssetIndexInRI)
        bb.putInt(soundAssetIndexInSI)
        bb.putInt(okTransitionActionNodeIndexInLI)
        bb.putInt(okTransitionNumberOfOptions)
        bb.putInt(okTransitionSelectedOptionIndex)
        bb.putInt(homeTransitionActionNodeIndexInLI)
        bb.putInt(homeTransitionNumberOfOptions)
        bb.putInt(homeTransitionSelectedOptionIndex)
        bb.putShort(boolToShort(wheel))
        bb.putShort(boolToShort(ok))
        bb.putShort(boolToShort(home))
        bb.putShort(boolToShort(pause))
        bb.putShort(boolToShort(autoplay))
        bb.putShort(0)
        niDos.write(bb.array())
    }

    private fun boolToShort(b: Boolean): Short = if (b) 1 else 0

    private fun writeActionNode(liDos: DataOutputStream, stageNodesIndexes: IntArray) {
        val bb = ByteBuffer.allocate(stageNodesIndexes.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (index in stageNodesIndexes) {
            bb.putInt(index)
        }
        liDos.write(bb.array())
    }

    private fun assetPathFromIndex(index: Int): String = String.format("000\\%08d", index)

    private fun requireIsRle4Bmp(imageData: ByteArray) {
        val bmpBuffer = ByteBuffer.wrap(imageData).order(ByteOrder.LITTLE_ENDIAN)
        require(bmpBuffer.getShort(28).toInt() == 4 && bmpBuffer.getInt(30) == 2) {
            "FS pack file requires image assets to use 4-bit depth and RLE encoding."
        }
        require(bmpBuffer.getInt(18) == 320 && bmpBuffer.getInt(22) == 240) {
            "FS pack file requires image assets to be 320x240 pixels."
        }
    }
}
