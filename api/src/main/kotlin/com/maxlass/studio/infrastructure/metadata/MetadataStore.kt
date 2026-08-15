package com.maxlass.studio.infrastructure.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-Kotlin replacement for the `studio-metadata` `DatabaseMetadataService`.
 *
 * Reads/writes the two JSON metadata databases:
 *  - official.json: read-only catalog fetched from the Lunii API (written by [com.maxlass.studio.pack.adapter.MetadataRefreshAdapter]).
 *  - unofficial.json: user overrides for packs not in the official catalog.
 *
 * Unlike the legacy library (which cached the official catalog once at startup), the official
 * database is re-read on each call so a refresh of official.json is picked up immediately.
 */
class MetadataStore(
    private val officialJsonPath: Path,
    private val unofficialJsonPath: Path,
) {

    private val log = LoggerFactory.getLogger(MetadataStore::class.java)

    /** True when the pack UUID is present in the official catalog. */
    fun isOfficialPack(uuid: String): Boolean = officialDatabase().containsKey(uuid)

    /** Official metadata for [uuid], or null when the pack is not in the catalog. */
    fun getOfficialMetadata(uuid: String): DatabasePackMetadata? =
        officialDatabase()[uuid]?.let { toOfficialMetadata(uuid, it) }

    /** Official metadata first, unofficial as fallback (legacy `getPackMetadata`). */
    fun getPackMetadata(uuid: String): DatabasePackMetadata? =
        getOfficialMetadata(uuid) ?: getUnofficialMetadata(uuid)

    /** Unofficial metadata for [uuid], or null when not present. */
    fun getUnofficialMetadata(uuid: String): DatabasePackMetadata? {
        val entry = readUnofficialDatabase()[uuid]?.jsonObject ?: return null
        return DatabasePackMetadata(
            uuid = uuid,
            title = entry["title"]?.jsonPrimitive?.contentOrNull,
            description = entry["description"]?.jsonPrimitive?.contentOrNull,
            thumbnail = entry["image"]?.jsonPrimitive?.contentOrNull,
            official = false,
        )
    }

    /**
     * Merges [meta] into unofficial.json. No-op when the pack is in the official catalog
     * (official metadata is read-only, comes from the API).
     */
    fun refreshUnofficialMetadata(meta: DatabasePackMetadata) {
        if (isOfficialPack(meta.uuid)) return
        val root = readUnofficialDatabase()
        val entry = buildJsonObject {
            put("uuid", meta.uuid)
            meta.title?.let { put("title", it) }
            meta.description?.let { put("description", it) }
            meta.thumbnail?.let { put("image", it) }
        }
        writeUnofficialDatabase(JsonObject(root + (meta.uuid to entry)))
    }

    /** Removes from unofficial.json every pack that is now part of the official catalog. */
    fun cleanUnofficialDatabase() {
        val root = readUnofficialDatabase()
        val official = officialDatabase()
        val officialKeys = root.keys.filter { it in official }
        if (officialKeys.isEmpty()) return
        log.info("Cleaning {} now-official entries from unofficial database", officialKeys.size)
        writeUnofficialDatabase(JsonObject(root - officialKeys))
    }

    /** Creates unofficial.json with an empty `{}` object when it does not exist yet. */
    fun ensureUnofficialDatabaseExists() {
        if (Files.exists(unofficialJsonPath)) return
        writeUnofficialDatabase(JsonObject(emptyMap()))
    }

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

    private fun readUnofficialDatabase(): JsonObject =
        readJson(unofficialJsonPath) ?: JsonObject(emptyMap())

    private fun writeUnofficialDatabase(root: JsonObject) {
        Files.createDirectories(unofficialJsonPath.parent)
        Files.writeString(unofficialJsonPath, json.encodeToString(JsonObject.serializer(), root))
    }

    private fun readJson(path: Path): JsonObject? = runCatching {
        if (!Files.exists(path)) return null
        Json.parseToJsonElement(Files.readString(path)).jsonObject
    }.getOrNull()

    companion object {
        private val json = Json { prettyPrint = true }
    }
}
