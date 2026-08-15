package com.maxlass.studio.pack.format.writer

import com.maxlass.studio.pack.format.model.ActionNode
import com.maxlass.studio.pack.format.model.AssetType
import com.maxlass.studio.pack.format.model.PackConstants
import com.maxlass.studio.pack.format.model.StageNode
import com.maxlass.studio.pack.format.model.StoryPack
import com.maxlass.studio.pack.format.model.Transition
import com.maxlass.studio.pack.format.reader.AssetAddr
import com.maxlass.studio.pack.format.reader.SectorAddr
import com.maxlass.studio.pack.format.utils.BytesUtils
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Pure-Kotlin writer for raw binary packs, mirroring
 * `studio.core.v1.writer.binary.BinaryStoryPackWriter`.
 */
class BinaryStoryPackWriter {

    fun write(pack: StoryPack, outputStream: OutputStream, enrichedBinaryFormat: Boolean) {
        val dos = DataOutputStream(outputStream)
        dos.writeShort(pack.stageNodes.orEmpty().size)
        dos.writeByte(if (pack.factoryDisabled) 1 else 0)
        dos.writeShort(pack.version.toInt())
        var enrichedPackMetadataSize = 0
        val enrichedPack = pack.enriched
        if (enrichedBinaryFormat && enrichedPack != null) {
            writePadding(dos, 59)
            writeTruncatedString(dos, enrichedPack.title, 64)
            writeTruncatedString(dos, enrichedPack.description, 128)
            enrichedPackMetadataSize = 443
        }
        writePadding(dos, 507 - enrichedPackMetadataSize)

        val actionNodesMap = sortedMapOf<SectorAddr, ActionNode>()
        var nextFreeOffset = pack.stageNodes.orEmpty().size
        for (stageNode in pack.stageNodes.orEmpty()) {
            stageNode.okTransition?.let { transition ->
                if (!actionNodesMap.containsValue(transition.actionNode)) {
                    actionNodesMap[SectorAddr(nextFreeOffset++)] = transition.actionNode!!
                }
            }
            stageNode.homeTransition?.let { transition ->
                if (!actionNodesMap.containsValue(transition.actionNode)) {
                    actionNodesMap[SectorAddr(nextFreeOffset++)] = transition.actionNode!!
                }
            }
        }

        val assetsHashes = sortedMapOf<String, AssetAddr>()
        val assetsData = sortedMapOf<AssetAddr, ByteArray>()

        for (stageNode in pack.stageNodes.orEmpty()) {
            val image = stageNode.image ?: continue
            val imageData = image.rawData ?: continue
            val assetHash = BytesUtils.sha1Hex(imageData)
            if (assetsHashes.containsKey(assetHash)) continue
            require(image.mimeType == "image/bmp") {
                "Cannot write binary pack file from a compressed story pack. Uncompress the pack assets first."
            }
            val imageSectors = (imageData.size + 511) / 512
            val addr = AssetAddr(nextFreeOffset, imageSectors, AssetType.IMAGE)
            assetsHashes[assetHash] = addr
            assetsData[addr] = imageData
            nextFreeOffset += imageSectors
        }
        for (stageNode in pack.stageNodes.orEmpty()) {
            val audio = stageNode.audio ?: continue
            val audioData = audio.rawData ?: continue
            val assetHash = BytesUtils.sha1Hex(audioData)
            if (assetsHashes.containsKey(assetHash)) continue
            require(audio.mimeType == "audio/x-wav") {
                "Cannot write binary pack file from a compressed story pack. Uncompress the pack assets first."
            }
            val audioSectors = (audioData.size + 511) / 512
            val addr = AssetAddr(nextFreeOffset, audioSectors, AssetType.AUDIO)
            assetsHashes[assetHash] = addr
            assetsData[addr] = audioData
            nextFreeOffset += audioSectors
        }

        for (stageNode in pack.stageNodes.orEmpty()) {
            val nodeUuid = UUID.fromString(stageNode.uuid)
            dos.writeLong(nodeUuid.mostSignificantBits)
            dos.writeLong(nodeUuid.leastSignificantBits)
            val image = stageNode.image
            if (image == null) {
                dos.writeInt(-1)
                dos.writeInt(-1)
            } else {
                val assetAddr = assetsHashes.getValue(BytesUtils.sha1Hex(image.rawData!!))
                dos.writeInt(assetAddr.offset)
                dos.writeInt(assetAddr.size)
            }
            val audio = stageNode.audio
            if (audio == null) {
                dos.writeInt(-1)
                dos.writeInt(-1)
            } else {
                val assetAddr = assetsHashes.getValue(BytesUtils.sha1Hex(audio.rawData!!))
                dos.writeInt(assetAddr.offset)
                dos.writeInt(assetAddr.size)
            }
            writeTransition(dos, actionNodesMap, stageNode.okTransition)
            writeTransition(dos, actionNodesMap, stageNode.homeTransition)
            val controls = stageNode.controlSettings!!
            dos.writeShort(if (controls.wheelEnabled) 1 else 0)
            dos.writeShort(if (controls.okEnabled) 1 else 0)
            dos.writeShort(if (controls.homeEnabled) 1 else 0)
            dos.writeShort(if (controls.pauseEnabled) 1 else 0)
            dos.writeShort(if (controls.autoJumpEnabled) 1 else 0)
            var enrichedNodeMetadataSize = 0
            if (enrichedBinaryFormat && stageNode.enriched != null) {
                writePadding(dos, 58)
                enrichedNodeMetadataSize = 58 + writeEnrichedNodeMetadata(dos, stageNode)
            }
            writePadding(dos, 458 - enrichedNodeMetadataSize)
        }

        var currentOffset = pack.stageNodes.orEmpty().size
        for ((actionNodeAddr, actionNode) in actionNodesMap) {
            while (actionNodeAddr.offset > currentOffset) {
                writePadding(dos, 512)
                currentOffset++
            }
            for (option in actionNode.options.orEmpty()) {
                dos.writeShort(pack.stageNodes.orEmpty().indexOf(option))
            }
            var enrichedNodeMetadataSize = 0
            if (enrichedBinaryFormat && actionNode.enriched != null) {
                val alignmentOverflow = 2 * actionNode.options.orEmpty().size % 16
                val alignmentPadding = 48 + if (alignmentOverflow > 0) 16 - alignmentOverflow else 0
                writePadding(dos, alignmentPadding)
                enrichedNodeMetadataSize = alignmentPadding + writeEnrichedNodeMetadata(dos, actionNode)
            }
            writePadding(dos, 512 - 2 * actionNode.options.orEmpty().size - enrichedNodeMetadataSize)
            currentOffset++
        }

        for ((assetAddr, assetBytes) in assetsData) {
            while (assetAddr.offset > currentOffset) {
                writePadding(dos, 512)
                currentOffset++
            }
            dos.write(assetBytes, 0, assetBytes.size)
            val overflow = assetBytes.size % 512
            if (overflow > 0) {
                writePadding(dos, 512 - overflow)
            }
            currentOffset += assetAddr.size
        }
        dos.write(PackConstants.CHECK_BYTES, 0, PackConstants.CHECK_BYTES.size)
    }

    private fun writeTransition(dos: DataOutputStream, actionNodesMap: Map<SectorAddr, ActionNode>, transition: Transition?) {
        if (transition == null) {
            dos.writeShort(-1)
            dos.writeShort(-1)
            dos.writeShort(-1)
        } else {
            val nodeAddr = actionNodesMap.entries.first { it.value == transition.actionNode }.key
            dos.writeShort(nodeAddr.offset)
            dos.writeShort(transition.actionNode!!.options.orEmpty().size)
            dos.writeShort(transition.optionIndex.toInt())
        }
    }

    private fun writeEnrichedNodeMetadata(dos: DataOutputStream, node: com.maxlass.studio.pack.format.model.Node): Int {
        writeTruncatedString(dos, node.enriched?.name, 64)
        val groupId = node.enriched?.groupId
        if (groupId != null) {
            val uuid = UUID.fromString(groupId)
            dos.writeLong(uuid.mostSignificantBits)
            dos.writeLong(uuid.leastSignificantBits)
        } else {
            writePadding(dos, 16)
        }
        val type = node.enriched?.type
        if (type != null) {
            dos.writeByte(type.code.toInt())
        } else {
            dos.writeByte(0)
        }
        val position = node.enriched?.position
        if (position != null) {
            dos.writeShort(position.x.toInt())
            dos.writeShort(position.y.toInt())
        } else {
            writePadding(dos, 4)
        }
        return 149
    }

    private fun writeTruncatedString(dos: DataOutputStream, str: String?, maxChars: Int) {
        if (str != null) {
            val strLength = minOf(str.length, maxChars)
            dos.writeChars(str.substring(0, strLength))
            val remaining = maxChars - strLength
            if (remaining > 0) {
                writePadding(dos, remaining * 2)
            }
        } else {
            writePadding(dos, maxChars * 2)
        }
    }

    private fun writePadding(dos: DataOutputStream, length: Int) {
        if (length <= 0) return
        dos.write(ByteArray(length), 0, length)
    }
}
