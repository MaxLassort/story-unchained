package com.maxlass.studio.infrastructure.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-Kotlin replacement for the `studio-metadata` `DatabaseMetadataService`.
 *
 * Reads the official.json catalog fetched from the Lunii API (written by
 * [com.maxlass.studio.pack.adapter.MetadataRefreshAdapter]). Unlike the legacy library (which
 * cached the catalog once at startup), it is re-read on each call so a refresh is picked up
 * immediately.
 */
class MetadataStore(
    private val officialJsonPath: Path,
) {

    /** True when the pack UUID is present in the official catalog. */
    fun isOfficialPack(uuid: String): Boolean = officialDatabase().containsKey(uuid)

    /** Official metadata for [uuid], or null when the pack is not in the catalog. */
    fun getOfficialMetadata(uuid: String): DatabasePackMetadata? =
        officialDatabase()[uuid]?.let { toOfficialMetadata(uuid, it) }

    /** Returns the official catalog as a map keyed by pack UUID (inner `uuid` field). */
    private fun officialDatabase(): Map<String, JsonObject> {
        val root = readJson(officialJsonPath) ?: return emptyMap()
        // Legacy files may wrap packs under a "response" object.
        val packsRoot = root["response"]?.jsonObject ?: root
        return packsRoot.mapNotNull { (_, value) ->
            val pack = value as? JsonObject ?: return@mapNotNull null
            val uuid = pack["uuid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            uuid to pack
        }.toMap()
    }

    /** Maps a catalog pack object to [DatabasePackMetadata], preferring `fr_FR` locale. */
    private fun toOfficialMetadata(uuid: String, pack: JsonObject): DatabasePackMetadata? {
        val locales = pack["locales_available"]?.jsonObject ?: return null
        val locale = if ("fr_FR" in locales) "fr_FR" else locales.keys.firstOrNull() ?: return null
        val info = pack["localized_infos"]?.jsonObject?.get(locale)?.jsonObject ?: return null
        val imageUrl = info["image"]?.jsonObject?.get("image_url")?.jsonPrimitive?.contentOrNull
        return DatabasePackMetadata(
            uuid = uuid,
            title = info["title"]?.jsonPrimitive?.contentOrNull,
            description = info["description"]?.jsonPrimitive?.contentOrNull,
            thumbnail = imageUrl?.let { MetadataDb.THUMBNAILS_STORAGE_ROOT + it },
            official = true,
        )
    }

    private fun readJson(path: Path): JsonObject? = runCatching {
        if (!Files.exists(path)) return null
        Json.parseToJsonElement(Files.readString(path)).jsonObject
    }.getOrNull()
}
