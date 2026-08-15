package com.maxlass.studio.pack.format.writer

import com.maxlass.studio.pack.format.model.ActionNode
import com.maxlass.studio.pack.format.model.EnrichedNodeMetadata
import com.maxlass.studio.pack.format.model.StageNode
import com.maxlass.studio.pack.format.model.StoryPack
import com.maxlass.studio.pack.format.utils.BytesUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure-Kotlin writer for ZIP archive packs, mirroring
 * `studio.core.v1.writer.archive.ArchiveStoryPackWriter` (kotlinx.serialization instead of gson).
 */
class ArchiveStoryPackWriter {

    private val json = Json { prettyPrint = true }

    fun write(pack: StoryPack, outputStream: OutputStream) {
        val assets = sortedMapOf<String, ByteArray>()
        val actionNodeToId = mutableMapOf<ActionNode, String>()

        val story = buildJsonObject {
            put("format", "v1")
            pack.enriched?.let { enriched ->
                put("title", enriched.title ?: "MISSING_PACK_TITLE")
                enriched.description?.let { put("description", it) }
            }
            put("version", pack.version)
            put("nightModeAvailable", pack.nightModeAvailable)
            put("stageNodes", buildJsonArray {
                pack.stageNodes.orEmpty().forEachIndexed { index, node ->
                    add(buildJsonObject {
                        put("uuid", node.uuid)
                        node.enriched?.let { putEnriched(this, it) }
                        if (index == 0) put("squareOne", true)
                        val image = node.image
                        if (image == null) {
                            put("image", JsonNull)
                        } else {
                            val raw = image.rawData ?: byteArrayOf()
                            val fileName = BytesUtils.sha1Hex(raw) + extensionFromMimeType(image.mimeType)
                            put("image", fileName)
                            assets.putIfAbsent(fileName, raw)
                        }
                        val audio = node.audio
                        if (audio == null) {
                            put("audio", JsonNull)
                        } else {
                            val raw = audio.rawData ?: byteArrayOf()
                            val fileName = BytesUtils.sha1Hex(raw) + extensionFromMimeType(audio.mimeType)
                            put("audio", fileName)
                            assets.putIfAbsent(fileName, raw)
                        }
                        val okTransition = node.okTransition
                        if (okTransition == null) {
                            put("okTransition", JsonNull)
                        } else {
                            val id = actionNodeToId.getOrPut(okTransition.actionNode!!) { UUID.randomUUID().toString() }
                            put("okTransition", buildJsonObject {
                                put("actionNode", id)
                                put("optionIndex", okTransition.optionIndex)
                            })
                        }
                        val homeTransition = node.homeTransition
                        if (homeTransition == null) {
                            put("homeTransition", JsonNull)
                        } else {
                            val id = actionNodeToId.getOrPut(homeTransition.actionNode!!) { UUID.randomUUID().toString() }
                            put("homeTransition", buildJsonObject {
                                put("actionNode", id)
                                put("optionIndex", homeTransition.optionIndex)
                            })
                        }
                        val controls = node.controlSettings
                        put("controlSettings", buildJsonObject {
                            put("wheel", controls?.wheelEnabled ?: false)
                            put("ok", controls?.okEnabled ?: false)
                            put("home", controls?.homeEnabled ?: false)
                            put("pause", controls?.pauseEnabled ?: false)
                            put("autoplay", controls?.autoJumpEnabled ?: false)
                        })
                    })
                }
            })
            put("actionNodes", buildJsonArray {
                for ((actionNode, id) in actionNodeToId) {
                    add(buildJsonObject {
                        put("id", id)
                        actionNode.enriched?.let { putEnriched(this, it) }
                        put("options", buildJsonArray {
                            actionNode.options.orEmpty().forEach { add(JsonPrimitive(it.uuid ?: "")) }
                        })
                    })
                }
            })
        }

        ZipOutputStream(outputStream).use { zos ->
            zos.putNextEntry(ZipEntry("story.json"))
            zos.write(json.encodeToString(JsonObject.serializer(), story).toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("assets/"))
            zos.closeEntry()
            for ((name, bytes) in assets) {
                zos.putNextEntry(ZipEntry("assets/$name"))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    private fun putEnriched(builder: kotlinx.serialization.json.JsonObjectBuilder, enriched: EnrichedNodeMetadata) {
        builder.put("name", enriched.name ?: "MISSING_NAME")
        enriched.type?.let { builder.put("type", it.label) }
        enriched.groupId?.let { builder.put("groupId", it) }
        enriched.position?.let { position ->
            builder.put("position", buildJsonObject {
                put("x", position.x)
                put("y", position.y)
            })
        }
    }

    private fun extensionFromMimeType(mimeType: String?): String = when (mimeType) {
        "image/bmp" -> ".bmp"
        "image/png" -> ".png"
        "image/jpeg" -> ".jpg"
        "audio/x-wav" -> ".wav"
        "audio/mpeg" -> ".mp3"
        "audio/ogg" -> ".ogg"
        else -> ""
    }
}
