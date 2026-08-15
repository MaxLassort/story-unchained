package com.maxlass.studio.pack.adapter

import com.maxlass.studio.pack.domain.dto.UnofficialJsonEntry
import com.maxlass.studio.pack.port.external.LoadUnofficialMetadataFromFilePort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Adapter that reads unofficial.json (e.g. from Studio Core ~/.studio/db/unofficial.json)
 * and returns a map uuid -> entry for importing into the app database.
 */
class LoadUnofficialMetadataFromFileAdapter : LoadUnofficialMetadataFromFilePort {

    override fun loadFromPath(path: String): Map<String, UnofficialJsonEntry> {
        val file = File(path)
        if (!file.exists() || !file.isFile()) return emptyMap()
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root.mapNotNull { (key, value) ->
                val obj = value as? JsonObject ?: return@mapNotNull null
                val uuid = obj["uuid"]?.jsonPrimitive?.content ?: key
                UnofficialJsonEntry(
                    uuid = uuid,
                    title = obj["title"]?.jsonPrimitive?.content,
                    description = obj["description"]?.jsonPrimitive?.content,
                    image = obj["image"]?.jsonPrimitive?.content
                ).let { uuid to it }
            }.toMap()
        }.getOrElse { emptyMap() }
    }
}
