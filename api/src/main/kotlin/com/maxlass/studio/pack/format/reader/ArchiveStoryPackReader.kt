package com.maxlass.studio.pack.format.reader

import com.maxlass.studio.pack.format.model.ActionNode
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pure-Kotlin reader for ZIP archive packs (`.pack` / `.zip`), mirroring
 * `studio.core.v1.reader.archive.ArchiveStoryPackReader` (uses JDK zip instead of commons-compress).
 */
class ArchiveStoryPackReader {

    private val json = Json

    /** Reads pack metadata from an archive without loading the assets. */
    fun readMetadata(inputStream: InputStream): StoryPackMetadata? {
        val zis = ZipInputStream(inputStream)
        val metadata = StoryPackMetadata(format = "archive")
        var hasStoryJson = false
        while (true) {
            val entry = zis.nextEntry ?: break
            if (!entry.isDirectory && entry.name.equals("story.json", ignoreCase = true)) {
                hasStoryJson = true
                val root = parseJson(zis)
                metadata.version = root["version"]?.jsonPrimitive?.contentOrNull?.toShortOrNull() ?: 0
                metadata.title = (root["title"] as? JsonPrimitive)?.content
                metadata.description = (root["description"] as? JsonPrimitive)?.content
                metadata.nightModeAvailable = (root["nightModeAvailable"] as? JsonPrimitive)?.booleanOrNull ?: false
                val mainStageNode = (root["stageNodes"] as? JsonArray)?.firstOrNull()?.jsonObject
                metadata.uuid = mainStageNode?.get("uuid")?.jsonPrimitive?.content
            } else if (!entry.isDirectory && entry.name.equals("thumbnail.png", ignoreCase = true)) {
                metadata.thumbnail = zis.readBytes()
            }
            zis.closeEntry()
        }
        zis.close()
        return if (hasStoryJson) metadata else null
    }

    /** Reads a full story pack from an archive, resolving assets and transitions. */
    fun read(inputStream: InputStream): StoryPack {
        val zis = ZipInputStream(inputStream)
        val assets = sortedMapOf<String, ByteArray>()
        var factoryDisabled = false
        var version: Short = 0
        val stageNodes = linkedMapOf<String, StageNode>()
        val assetToStageNodes = mutableMapOf<String, MutableList<StageNode>>()
        var squareOne: StageNode? = null
        var enrichedPack: EnrichedPackMetadata? = null
        var nightModeAvailable = false

        while (true) {
            val entry = zis.nextEntry ?: break
            if (!entry.isDirectory && entry.name.equals("story.json", ignoreCase = true)) {
                val root = parseJson(zis)
                version = root["version"]?.jsonPrimitive?.contentOrNull?.toShortOrNull() ?: 0
                val title = (root["title"] as? JsonPrimitive)?.content
                val description = (root["description"] as? JsonPrimitive)?.content
                if (title != null || description != null) {
                    enrichedPack = EnrichedPackMetadata(title, description)
                }
                nightModeAvailable = (root["nightModeAvailable"] as? JsonPrimitive)?.booleanOrNull ?: false
                val actionNodes = linkedMapOf<String, ActionNode>()
                for (actionEl in (root["actionNodes"] as? JsonArray).orEmpty()) {
                    val node = actionEl.jsonObject
                    val id = node["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    actionNodes[id] = ActionNode(null, readEnrichedNodeMetadata(node))
                }
                for (stageEl in (root["stageNodes"] as? JsonArray).orEmpty()) {
                    val node = stageEl.jsonObject
                    val uuid = node["uuid"]?.jsonPrimitive?.contentOrNull ?: continue
                    var okTransition: Transition? = null
                    (node["okTransition"] as? kotlinx.serialization.json.JsonObject)?.let { t ->
                        val actionId = t["actionNode"]?.jsonPrimitive?.contentOrNull ?: ""
                        okTransition = Transition(actionNodes[actionId], t["optionIndex"]?.jsonPrimitive?.contentOrNull?.toShortOrNull() ?: 0)
                    }
                    var homeTransition: Transition? = null
                    (node["homeTransition"] as? kotlinx.serialization.json.JsonObject)?.let { t ->
                        val actionId = t["actionNode"]?.jsonPrimitive?.contentOrNull ?: ""
                        homeTransition = Transition(actionNodes[actionId], t["optionIndex"]?.jsonPrimitive?.contentOrNull?.toShortOrNull() ?: 0)
                    }
                    val control = node["controlSettings"]?.jsonObject
                        ?: throw IllegalStateException("Stage node '$uuid' is missing controlSettings")
                    val stageNode = StageNode(
                        uuid = uuid,
                        image = null,
                        audio = null,
                        okTransition = okTransition,
                        homeTransition = homeTransition,
                        controlSettings = ControlSettings(
                            wheelEnabled = control["wheel"]?.jsonPrimitive?.booleanOrNull ?: false,
                            okEnabled = control["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
                            homeEnabled = control["home"]?.jsonPrimitive?.booleanOrNull ?: false,
                            pauseEnabled = control["pause"]?.jsonPrimitive?.booleanOrNull ?: false,
                            autoJumpEnabled = control["autoplay"]?.jsonPrimitive?.booleanOrNull ?: false,
                        ),
                        enriched = readEnrichedNodeMetadata(node),
                    )
                    if (node["squareOne"]?.jsonPrimitive?.booleanOrNull == true) {
                        squareOne = stageNode
                    }
                    (node["image"] as? JsonPrimitive)?.contentOrNull?.let { imageName ->
                        assetToStageNodes.getOrPut(imageName) { mutableListOf() }.add(stageNode)
                    }
                    (node["audio"] as? JsonPrimitive)?.contentOrNull?.let { audioName ->
                        assetToStageNodes.getOrPut(audioName) { mutableListOf() }.add(stageNode)
                    }
                    stageNodes[uuid] = stageNode
                }
                for (actionEl in (root["actionNodes"] as? JsonArray).orEmpty()) {
                    val node = actionEl.jsonObject
                    val id = node["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val actionNode = actionNodes[id] ?: continue
                    val options = (node["options"] as? JsonArray).orEmpty().mapNotNull { opt ->
                        stageNodes[opt.jsonPrimitive.content]
                    }
                    actionNode.options = options
                }
            } else if (!entry.isDirectory && entry.name.startsWith("assets/")) {
                assets[entry.name.removePrefix("assets/")] = zis.readBytes()
            }
            zis.closeEntry()
        }

        for ((assetName, bytes) in assets) {
            val dotIndex = assetName.lastIndexOf('.')
            if (dotIndex < 0) continue
            val extension = assetName.substring(dotIndex).lowercase()
            val referencing = assetToStageNodes[assetName] ?: continue
            for (stageNode in referencing) {
                when (extension) {
                    ".bmp" -> stageNode.image = ImageAsset("image/bmp", bytes, assetName)
                    ".png" -> stageNode.image = ImageAsset("image/png", bytes, assetName)
                    ".jpg", ".jpeg" -> stageNode.image = ImageAsset("image/jpeg", bytes, assetName)
                    ".wav" -> stageNode.audio = AudioAsset("audio/x-wav", bytes, assetName)
                    ".mp3" -> stageNode.audio = AudioAsset("audio/mpeg", bytes, assetName)
                    ".ogg", ".oga" -> stageNode.audio = AudioAsset("audio/ogg", bytes, assetName)
                }
            }
        }
        zis.close()

        val nodes = stageNodes.values.toMutableList()
        squareOne?.let { first ->
            nodes.remove(first)
            nodes.add(0, first)
        }
        return StoryPack(
            uuid = nodes.firstOrNull()?.uuid,
            factoryDisabled = factoryDisabled,
            version = version,
            stageNodes = nodes,
            enriched = enrichedPack,
            nightModeAvailable = nightModeAvailable,
        )
    }

    private fun parseJson(zis: ZipInputStream): kotlinx.serialization.json.JsonObject =
        json.parseToJsonElement(zis.readBytes().decodeToString()).jsonObject

    private fun readEnrichedNodeMetadata(node: kotlinx.serialization.json.JsonObject): EnrichedNodeMetadata? {
        val name = (node["name"] as? JsonPrimitive)?.content
        val type = (node["type"] as? JsonPrimitive)?.content?.let(EnrichedNodeType::fromLabel)
        val groupId = (node["groupId"] as? JsonPrimitive)?.content
        val position = node["position"]?.jsonObject?.let { p ->
            EnrichedNodePosition(
                x = p["x"]?.jsonPrimitive?.contentOrNull?.toShortOrNull() ?: 0,
                y = p["y"]?.jsonPrimitive?.contentOrNull?.toShortOrNull() ?: 0,
            )
        }
        return if (name != null || type != null || groupId != null || position != null) {
            EnrichedNodeMetadata(name, type, groupId, position)
        } else {
            null
        }
    }
}
