package com.maxlass.studio.pack.format.reader

import com.maxlass.studio.pack.format.model.AudioAsset
import com.maxlass.studio.pack.format.model.ControlSettings
import com.maxlass.studio.pack.format.model.ImageAsset
import com.maxlass.studio.pack.format.model.StageNode
import com.maxlass.studio.pack.format.model.StoryPack
import com.maxlass.studio.pack.format.model.StoryPackMetadata
import com.maxlass.studio.pack.format.model.Transition
import com.maxlass.studio.pack.format.utils.BytesUtils
import com.maxlass.studio.pack.format.utils.XxteaCipher
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Pure-Kotlin reader for FS packs (extracted folder on a Lunii device), mirroring
 * `studio.core.v1.reader.fs.FsStoryPackReader`.
 */
class FsStoryPackReader {

    companion object {
        private val log = LoggerFactory.getLogger(FsStoryPackReader::class.java)

        const val NODE_INDEX_FILENAME = "ni"
        const val LIST_INDEX_FILENAME = "li"
        const val IMAGE_INDEX_FILENAME = "ri"
        const val IMAGE_FOLDER = "rf/"
        const val SOUND_INDEX_FILENAME = "si"
        const val SOUND_FOLDER = "sf/"
        const val NIGHT_MODE_FILENAME = "nm"
        const val CLEARTEXT_FILENAME = ".cleartext"
        val CLEARTEXT_RI_BEGINNING: ByteArray = "000\\".toByteArray(Charsets.UTF_8)
    }

    /** Reads pack metadata from an FS pack folder (ni header + night mode file). */
    fun readMetadata(inputFolder: Path): StoryPackMetadata {
        val metadata = StoryPackMetadata(format = "fs")
        val packFolder = inputFolder.toFile()
        val niFis = FileInputStream(File(packFolder, NODE_INDEX_FILENAME))
        val niDis = DataInputStream(niFis)
        val bb = ByteBuffer.wrap(niDis.readNBytes(512)).order(ByteOrder.LITTLE_ENDIAN)
        metadata.version = bb.getShort(2)
        niDis.close()
        niFis.close()
        metadata.uuid = inputFolder.fileName.toString().split(".", limit = 2)[0]
        metadata.nightModeAvailable = File(packFolder, NIGHT_MODE_FILENAME).exists()
        return metadata
    }

    /** Reads a full story pack from an FS pack folder. */
    fun read(inputFolder: Path): StoryPack {
        val stageNodes = sortedMapOf<Int, StageNode>()
        val actionNodesOptionsCount = sortedMapOf<Int, Int>()
        val transitionsWithAction = sortedMapOf<Int, MutableList<Transition>>()
        val uuid = inputFolder.fileName.toString().split(".", limit = 2)[0]
        val packFolder = inputFolder.toFile()
        val nightModeAvailable = File(packFolder, NIGHT_MODE_FILENAME).exists()
        val isCleartext = isCleartext(inputFolder, true)
        val riContent = readCipheredFile(File(packFolder, IMAGE_INDEX_FILENAME).toPath(), isCleartext)
        val siContent = readCipheredFile(File(packFolder, SOUND_INDEX_FILENAME).toPath(), isCleartext)
        val liContent = readCipheredFile(File(packFolder, LIST_INDEX_FILENAME).toPath(), isCleartext)

        val niFis = FileInputStream(File(packFolder, NODE_INDEX_FILENAME))
        val niDis = DataInputStream(niFis)
        var bb = ByteBuffer.wrap(niDis.readNBytes(512)).order(ByteOrder.LITTLE_ENDIAN)
        bb.short // nodes list start (unused)
        val version = bb.short
        bb.int // nodesList (unused)
        val nodeSize = bb.int
        val stageNodesCount = bb.int
        bb.int // imageAssetsCount (unused)
        bb.int // soundAssetsCount (unused)
        val factoryDisabled = bb.get() != 0.toByte()

        for (i in 0 until stageNodesCount) {
            bb = ByteBuffer.wrap(niDis.readNBytes(nodeSize)).order(ByteOrder.LITTLE_ENDIAN)
            val imageAssetIndexInRI = bb.int
            val soundAssetIndexInSI = bb.int
            val okTransitionActionNodeIndexInLI = bb.int
            val okTransitionNumberOfOptions = bb.int
            val okTransitionSelectedOptionIndex = bb.int
            val homeTransitionActionNodeIndexInLI = bb.int
            val homeTransitionNumberOfOptions = bb.int
            val homeTransitionSelectedOptionIndex = bb.int
            val wheel = bb.short != 0.toShort()
            val ok = bb.short != 0.toShort()
            val home = bb.short != 0.toShort()
            val pause = bb.short != 0.toShort()
            val autoplay = bb.short != 0.toShort()

            var okTransition: Transition? = null
            if (okTransitionActionNodeIndexInLI != -1 && okTransitionNumberOfOptions != -1 && okTransitionSelectedOptionIndex != -1) {
                actionNodesOptionsCount.putIfAbsent(okTransitionActionNodeIndexInLI, okTransitionNumberOfOptions)
                okTransition = Transition(null, okTransitionSelectedOptionIndex.toShort())
                transitionsWithAction.getOrPut(okTransitionActionNodeIndexInLI) { mutableListOf() }.add(okTransition)
            }
            var homeTransition: Transition? = null
            if (homeTransitionActionNodeIndexInLI != -1 && homeTransitionNumberOfOptions != -1 && homeTransitionSelectedOptionIndex != -1) {
                actionNodesOptionsCount.putIfAbsent(homeTransitionActionNodeIndexInLI, homeTransitionNumberOfOptions)
                homeTransition = Transition(null, homeTransitionSelectedOptionIndex.toShort())
                transitionsWithAction.getOrPut(homeTransitionActionNodeIndexInLI) { mutableListOf() }.add(homeTransition)
            }

            var image: ImageAsset? = null
            if (imageAssetIndexInRI != -1) {
                val imagePath = String(riContent.copyOfRange(imageAssetIndexInRI * 12, imageAssetIndexInRI * 12 + 12), Charsets.UTF_8)
                val rfContent = readCipheredFile(
                    File(packFolder, IMAGE_FOLDER + imagePath.replace("\\", "/")).toPath(),
                    isCleartext,
                )
                image = ImageAsset("image/bmp", rfContent, imagePath)
            }
            var audio: AudioAsset? = null
            if (soundAssetIndexInSI != -1) {
                val audioPath = String(siContent.copyOfRange(soundAssetIndexInSI * 12, soundAssetIndexInSI * 12 + 12), Charsets.UTF_8)
                val sfContent = readCipheredFile(
                    File(packFolder, SOUND_FOLDER + audioPath.replace("\\", "/")).toPath(),
                    isCleartext,
                )
                audio = AudioAsset("audio/mpeg", sfContent, audioPath)
            }

            val stageNode = StageNode(
                uuid = if (i == 0) uuid else UUID.randomUUID().toString(),
                image = image,
                audio = audio,
                okTransition = okTransition,
                homeTransition = homeTransition,
                controlSettings = ControlSettings(wheel, ok, home, pause, autoplay),
                enriched = null,
            )
            stageNodes[i] = stageNode
        }
        niDis.close()
        niFis.close()

        val liBb = ByteBuffer.wrap(liContent).order(ByteOrder.LITTLE_ENDIAN)
        for ((offset, count) in actionNodesOptionsCount) {
            val options = ArrayList<StageNode>(count)
            liBb.position(offset * 4)
            for (i in 0 until count) {
                options.add(stageNodes.getValue(liBb.int))
            }
            val actionNode = com.maxlass.studio.pack.format.model.ActionNode(options, null)
            transitionsWithAction[offset]?.forEach { it.actionNode = actionNode }
        }

        return StoryPack(
            uuid = uuid,
            factoryDisabled = factoryDisabled,
            version = version,
            stageNodes = stageNodes.values.toList(),
            enriched = null,
            nightModeAvailable = nightModeAvailable,
        )
    }

    /**
     * Returns whether the pack is stored in cleartext. When [fixBrokenCleartext] is true,
     * a missing `.cleartext` marker is re-created if the `ri` index starts with `000\`.
     */
    fun isCleartext(inputFolder: Path, fixBrokenCleartext: Boolean): Boolean {
        val packFolder = inputFolder.toFile()
        var isCleartext = File(packFolder, CLEARTEXT_FILENAME).exists()
        if (fixBrokenCleartext) {
            val riRawContent = Files.readAllBytes(File(packFolder, IMAGE_INDEX_FILENAME).toPath())
            if (!isCleartext && riRawContent.copyOfRange(0, CLEARTEXT_RI_BEGINNING.size).contentEquals(CLEARTEXT_RI_BEGINNING)) {
                log.warn("Story pack contains cleartext data but is missing {} file: fixing...", CLEARTEXT_FILENAME)
                File(packFolder, CLEARTEXT_FILENAME).createNewFile()
                isCleartext = true
            }
        }
        return isCleartext
    }

    private fun readCipheredFile(path: Path, isCleartext: Boolean): ByteArray {
        val content = Files.readAllBytes(path)
        return if (isCleartext) content else decipherFirstBlockCommonKey(content)
    }

    /** Decrypts the first block (512 bytes) of [data] with the shared XXTEA key. */
    private fun decipherFirstBlockCommonKey(data: ByteArray): ByteArray {
        val block = data.copyOfRange(0, minOf(512, data.size))
        val dataInt = BytesUtils.toIntArray(block, ByteOrder.LITTLE_ENDIAN)
        val decryptedInt = XxteaCipher.btea(
            dataInt,
            -minOf(128, data.size / 4),
            BytesUtils.toIntArray(XxteaCipher.COMMON_KEY, ByteOrder.BIG_ENDIAN),
        )
        val decryptedBlock = BytesUtils.toByteArray(decryptedInt, ByteOrder.LITTLE_ENDIAN)
        val bb = ByteBuffer.allocate(data.size)
        bb.put(decryptedBlock)
        if (data.size > 512) {
            bb.put(data.copyOfRange(512, data.size))
        }
        return bb.array()
    }
}
