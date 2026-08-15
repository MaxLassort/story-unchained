package com.maxlass.studio.pack.format.reader

import com.maxlass.studio.pack.format.model.ActionNode
import com.maxlass.studio.pack.format.model.AssetType
import com.maxlass.studio.pack.format.model.AudioAsset
import com.maxlass.studio.pack.format.model.ControlSettings
import com.maxlass.studio.pack.format.model.EnrichedNodeMetadata
import com.maxlass.studio.pack.format.model.EnrichedNodePosition
import com.maxlass.studio.pack.format.model.EnrichedNodeType
import com.maxlass.studio.pack.format.model.EnrichedPackMetadata
import com.maxlass.studio.pack.format.model.ImageAsset
import com.maxlass.studio.pack.format.model.StageNode
import com.maxlass.studio.pack.format.model.StoryPack
import com.maxlass.studio.pack.format.model.StoryPackMetadata
import com.maxlass.studio.pack.format.model.Transition
import java.io.DataInputStream
import java.io.InputStream
import java.util.UUID

/** Sector address used as a key for binary pack layout. */
data class SectorAddr(val offset: Int) : Comparable<SectorAddr> {
    override fun compareTo(other: SectorAddr): Int = offset - other.offset
}

/** Asset sector address used as a key for binary pack layout. */
data class AssetAddr(val offset: Int, val size: Int, val type: AssetType) : Comparable<AssetAddr> {
    override fun compareTo(other: AssetAddr): Int = offset - other.offset
}

/**
 * Pure-Kotlin reader for raw binary packs (Lunii RAW format), mirroring
 * `studio.core.v1.reader.binary.BinaryStoryPackReader`.
 */
class BinaryStoryPackReader {

    /** Reads pack metadata from a raw binary pack header. */
    fun readMetadata(inputStream: InputStream): StoryPackMetadata {
        val dis = DataInputStream(inputStream)
        val metadata = StoryPackMetadata(format = "raw")
        dis.skipBytes(3)
        metadata.version = dis.readShort()
        dis.skipBytes(59)
        metadata.title = readString(dis, 64)
        metadata.description = readString(dis, 128)
        dis.skipBytes(64)
        val uuidLow = dis.readLong()
        val uuidHigh = dis.readLong()
        metadata.uuid = UUID(uuidLow, uuidHigh).toString()
        dis.close()
        return metadata
    }

    /** Reads a full story pack from a raw binary pack. */
    fun read(inputStream: InputStream): StoryPack {
        val dis = DataInputStream(inputStream)
        val stages = dis.readShort()
        val factoryDisabled = dis.readByte() == 1.toByte()
        val version = dis.readShort()
        var enrichedPack: EnrichedPackMetadata? = null
        dis.skipBytes(59)
        val title = readString(dis, 64)
        val description = readString(dis, 128)
        if (title != null || description != null) {
            enrichedPack = EnrichedPackMetadata(title, description)
        }
        dis.skipBytes(64)

        val stageNodes = sortedMapOf<SectorAddr, StageNode>()
        val stagesWithImage = sortedMapOf<AssetAddr, MutableList<StageNode>>()
        val stagesWithAudio = sortedMapOf<AssetAddr, MutableList<StageNode>>()
        val transitionsWithAction = sortedMapOf<SectorAddr, MutableList<Transition>>()
        val actionNodesToVisit = sortedSetOf<SectorAddr>()
        val assetAddrsToVisit = sortedSetOf<AssetAddr>()

        for (i in 0 until stages) {
            val uuid = UUID(dis.readLong(), dis.readLong()).toString()
            val imageOffset = dis.readInt()
            val imageSize = dis.readInt()
            var imageAssetAddr: AssetAddr? = null
            if (imageOffset != -1) {
                imageAssetAddr = AssetAddr(imageOffset, imageSize, AssetType.IMAGE)
                assetAddrsToVisit.add(imageAssetAddr)
            }
            val audioOffset = dis.readInt()
            val audioSize = dis.readInt()
            var audioAssetAddr: AssetAddr? = null
            if (audioOffset != -1) {
                audioAssetAddr = AssetAddr(audioOffset, audioSize, AssetType.AUDIO)
                assetAddrsToVisit.add(audioAssetAddr)
            }
            val okTransitionOffset = dis.readShort()
            dis.readShort() // okTransitionCount (unused)
            val okTransitionIndex = dis.readShort()
            var okActionNodeAddr: SectorAddr? = null
            if (okTransitionOffset.toInt() != -1) {
                okActionNodeAddr = SectorAddr(okTransitionOffset.toInt())
                actionNodesToVisit.add(okActionNodeAddr)
            }
            val homeTransitionOffset = dis.readShort()
            dis.readShort() // homeTransitionCount (unused)
            val homeTransitionIndex = dis.readShort()
            var homeActionNodeAddr: SectorAddr? = null
            if (homeTransitionOffset.toInt() != -1) {
                homeActionNodeAddr = SectorAddr(homeTransitionOffset.toInt())
                actionNodesToVisit.add(homeActionNodeAddr)
            }
            val wheelEnabled = dis.readShort() == 1.toShort()
            val okEnabled = dis.readShort() == 1.toShort()
            val homeEnabled = dis.readShort() == 1.toShort()
            val pauseEnabled = dis.readShort() == 1.toShort()
            val autoJumpEnabled = dis.readShort() == 1.toShort()
            dis.skipBytes(58)
            val enrichedNodeMetadata = readEnrichedNodeMetadata(dis)
            val address = SectorAddr(i)
            val okTransition = okActionNodeAddr?.let { Transition(null, okTransitionIndex) }
            val homeTransition = homeActionNodeAddr?.let { Transition(null, homeTransitionIndex) }
            val stageNode = StageNode(
                uuid = uuid,
                image = null,
                audio = null,
                okTransition = okTransition,
                homeTransition = homeTransition,
                controlSettings = ControlSettings(wheelEnabled, okEnabled, homeEnabled, pauseEnabled, autoJumpEnabled),
                enriched = enrichedNodeMetadata,
            )
            stageNodes[address] = stageNode
            imageAssetAddr?.let { stagesWithImage.getOrPut(it) { mutableListOf() }.add(stageNode) }
            audioAssetAddr?.let { stagesWithAudio.getOrPut(it) { mutableListOf() }.add(stageNode) }
            okActionNodeAddr?.let { transitionsWithAction.getOrPut(it) { mutableListOf() }.add(okTransition!!) }
            homeActionNodeAddr?.let { transitionsWithAction.getOrPut(it) { mutableListOf() }.add(homeTransition!!) }
            dis.skipBytes(251)
        }

        var currentOffset = stages.toInt()
        for (actionNodeAddr in actionNodesToVisit) {
            while (actionNodeAddr.offset > currentOffset) {
                dis.skipBytes(512)
                currentOffset++
            }
            val options = mutableListOf<StageNode>()
            var optionAddr = dis.readShort()
            while (optionAddr != 0.toShort()) {
                options.add(stageNodes.getValue(SectorAddr(optionAddr.toInt())))
                optionAddr = dis.readShort()
            }
            val alignmentOverflow = 2 * options.size % 16
            val alignmentPadding = 48 + if (alignmentOverflow > 0) 16 - alignmentOverflow else 0
            dis.skipBytes(alignmentPadding - 2)
            val enrichedNodeMetadata = readEnrichedNodeMetadata(dis)
            val actionNode = ActionNode(options, enrichedNodeMetadata)
            transitionsWithAction.getValue(actionNodeAddr).forEach { it.actionNode = actionNode }
            dis.skipBytes(512 - 2 * (options.size + 1) - (alignmentPadding - 2) - 128 - 16 - 1 - 4)
            currentOffset++
        }

        for (assetAddr in assetAddrsToVisit) {
            while (assetAddr.offset > currentOffset) {
                dis.skipBytes(512)
                currentOffset++
            }
            val assetBytes = ByteArray(512 * assetAddr.size)
            dis.read(assetBytes, 0, assetBytes.size)
            when (assetAddr.type) {
                AssetType.AUDIO -> {
                    val audioAsset = AudioAsset("audio/x-wav", assetBytes, "0x" + Integer.toHexString(assetAddr.offset))
                    stagesWithAudio.getValue(assetAddr).forEach { it.audio = audioAsset }
                }
                AssetType.IMAGE -> {
                    val imageAsset = ImageAsset("image/bmp", assetBytes, "0x" + Integer.toHexString(assetAddr.offset))
                    stagesWithImage.getValue(assetAddr).forEach { it.image = imageAsset }
                }
            }
            currentOffset += assetAddr.size
        }
        dis.close()

        return StoryPack(
            uuid = stageNodes.getValue(SectorAddr(0)).uuid,
            factoryDisabled = factoryDisabled,
            version = version,
            stageNodes = stageNodes.values.toList(),
            enriched = enrichedPack,
            nightModeAvailable = false,
        )
    }

    private fun readString(dis: DataInputStream, maxChars: Int): String? {
        val bytes = ByteArray(maxChars * 2)
        dis.read(bytes)
        val str = String(bytes, Charsets.UTF_16)
        val firstNullChar = str.indexOf('\u0000')
        return when {
            firstNullChar == 0 -> null
            firstNullChar == -1 -> str
            else -> str.substring(0, firstNullChar)
        }
    }

    private fun readEnrichedNodeMetadata(dis: DataInputStream): EnrichedNodeMetadata? {
        val name = readString(dis, 64)
        val groupIdLow = dis.readLong()
        val groupIdHigh = dis.readLong()
        val groupId = if (groupIdLow != 0L || groupIdHigh != 0L) UUID(groupIdLow, groupIdHigh).toString() else null
        val typeByte = dis.readByte()
        val type = if (typeByte != 0.toByte()) EnrichedNodeType.fromCode(typeByte) else null
        val positionX = dis.readShort()
        val positionY = dis.readShort()
        val position = if (positionX != 0.toShort() || positionY != 0.toShort()) {
            EnrichedNodePosition(positionX, positionY)
        } else {
            null
        }
        return if (name != null || type != null || groupId != null || position != null) {
            EnrichedNodeMetadata(name, type, groupId, position)
        } else {
            null
        }
    }
}
