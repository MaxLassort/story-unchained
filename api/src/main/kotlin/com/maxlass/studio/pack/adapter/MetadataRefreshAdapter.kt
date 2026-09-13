package com.maxlass.studio.pack.adapter

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.infrastructure.metadata.MetadataDb
import com.maxlass.studio.pack.domain.dto.OfficialMetadataDto
import com.maxlass.studio.pack.port.external.MetadataRefreshPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

private val json = Json { prettyPrint = true }

/** HTTP connection timeout in milliseconds. */
private const val HTTP_CONNECT_TIMEOUT_MS = 10_000

/** HTTP read timeout in milliseconds. */
private const val HTTP_READ_TIMEOUT_MS = 10_000

/**
 * External adapter that implements [MetadataRefreshPort] by replicating the logic of the
 * legacy `DatabaseMetadataService` (fetch guest token, call Lunii packs API, write official.json),
 * using Spring [RestClient] instead of HttpURLConnection.
 */
class MetadataRefreshAdapter(
    restClientBuilder: RestClient.Builder,
    private val studioProperties: StudioProperties,
) : MetadataRefreshPort {

    companion object {
        private val log = LoggerFactory.getLogger(MetadataRefreshAdapter::class.java)
    }

    private val restClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS)
                setReadTimeout(HTTP_READ_TIMEOUT_MS)
            }
        )
        .build()

    @Volatile
    private var officialMetadataCache: Pair<Long, Map<String, OfficialMetadataDto>>? = null

    override fun refreshOfficialMetadata() {
        val token = fetchGuestToken()
            ?: throw IllegalStateException("Failed to get guest token")
        val response = fetchPacksDatabase(token)
            ?: throw IllegalStateException("Failed to fetch packs database")
        writeOfficialDatabase(rewriteDeadImageUrls(response))
    }

    override fun findOfficialMetadataById(uuid: String): OfficialMetadataDto? {
        return getOfficialMetadataMap()[uuid]
    }

    override fun getOfficialMetadataMap(): Map<String, OfficialMetadataDto> {
        val file = File(getOfficialDatabasePath())
        if (!file.exists() || !file.isFile) return emptyMap()
        officialMetadataCache?.takeIf { it.first == file.lastModified() }?.let { return it.second }

        val root = readOfficialDatabase() ?: return emptyMap()
        // Same as MetadataStore: API / legacy files may wrap packs under "response".
        val packsRoot = root["response"]?.jsonObject ?: root
        val map = packsRoot.entries.mapNotNull { (_, value) ->
            val pack = value as? JsonObject ?: return@mapNotNull null
            val uuid = pack["uuid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val localizedInfos = pack["localized_infos"]?.jsonObject ?: return@mapNotNull null
            val locale = if ("fr_FR" in localizedInfos) "fr_FR" else localizedInfos.keys.firstOrNull() ?: return@mapNotNull null
            val info = localizedInfos[locale]?.jsonObject ?: return@mapNotNull null
            val title = info["title"]?.jsonPrimitive?.content
            val description = info["description"]?.jsonPrimitive?.content
            val imageObj = info["image"]?.jsonObject
            val imageUrl = imageObj?.get("image_url")?.jsonPrimitive?.content
                ?.let { MetadataDb.resolveImageUrl(it) }
            uuid to OfficialMetadataDto(
                title = title,
                description = description,
                thumbnailUrl = imageUrl,
                slug = pack["slug"]?.jsonPrimitive?.content,
                locale = locale,
                ageMin = pack["age_min"]?.jsonPrimitive?.content?.toIntOrNull(),
                ageMax = pack["age_max"]?.jsonPrimitive?.content?.toIntOrNull(),
                durationMs = pack["duration"]?.jsonPrimitive?.content?.toIntOrNull(),
                storyCount = pack["story_count"]?.jsonPrimitive?.content?.toIntOrNull(),
            )
        }.toMap()
        officialMetadataCache = file.lastModified() to map
        return map
    }

    /**
     * Some GCS cover objects are gone (HTTP 403) while the pack is still sold. Lunii mirrors
     * the same files on its public Shopify CDN under the identical file name, so probe each
     * `localized_infos.*.image.image_url`: keep the GCS URL when reachable, otherwise swap it
     * for the absolute Shopify CDN URL (kept as-is by [getOfficialMetadataMap]). Packs
     * reachable nowhere keep their original URL and are logged.
     */
    private fun rewriteDeadImageUrls(packs: JsonObject): JsonObject {
        val fixed = packs.entries.associate { (key, value) ->
            val pack = value as? JsonObject ?: return@associate key to value
            key to rewritePackImageUrls(pack)
        }
        return JsonObject(fixed)
    }

    /** Rewrites dead GCS image URLs to Shopify CDN for a single pack. */
    private fun rewritePackImageUrls(pack: JsonObject): JsonObject {
        val localizedInfos = pack["localized_infos"] as? JsonObject ?: return pack
        val updatedInfos = localizedInfos.entries.fold(localizedInfos) { currentInfos, (locale, infoValue) ->
            val info = infoValue as? JsonObject ?: return@fold currentInfos
            val imageObj = info["image"] as? JsonObject ?: return@fold currentInfos
            val imageUrl = (imageObj["image_url"] as? JsonPrimitive)?.contentOrNull ?: return@fold currentInfos
            if (imageUrl.startsWith("http")) return@fold currentInfos // already absolute
            val gcsUrl = MetadataDb.THUMBNAILS_STORAGE_ROOT + imageUrl
            if (isUrlReachable(gcsUrl)) return@fold currentInfos
            val shopifyUrl = MetadataDb.SHOPIFY_IMAGES_ROOT + imageUrl.substringAfterLast('/')
            if (!isUrlReachable(shopifyUrl)) {
                log.warn("No reachable cover for pack {} ({}): GCS and Shopify CDN both failed", pack["uuid"], imageUrl)
                return@fold currentInfos
            }
            log.info("Cover unreachable on GCS, using Shopify CDN for pack {}: {}", pack["uuid"], shopifyUrl)
            val newImage = JsonObject(imageObj + ("image_url" to JsonPrimitive(shopifyUrl)))
            val newInfo = JsonObject(info + ("image" to newImage))
            JsonObject(currentInfos + (locale to newInfo))
        }
        return if (updatedInfos === localizedInfos) pack
        else JsonObject(pack + ("localized_infos" to updatedInfos))
    }

    /** Lightweight HEAD probe used to detect dead cover objects (GCS answers 403). */
    private fun isUrlReachable(url: String): Boolean {
        val conn = runCatching {
            URI.create(url).toURL().openConnection() as HttpURLConnection
        }.getOrNull() ?: return false
        return try {
            conn.requestMethod = "HEAD"
            conn.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            conn.readTimeout = HTTP_READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "StoryUnchained/1.0")
            conn.responseCode in 200..299
        } catch (e: IOException) {
            false
        } catch (e: RuntimeException) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchGuestToken(): String? {
        return runCatching {
            val body = restClient.get()
                .uri(MetadataDb.LUNII_GUEST_TOKEN_URL)
                .retrieve()
                .body(String::class.java)
                ?: return null
            val root = Json.parseToJsonElement(body).jsonObject
            val responseObj = root["response"]?.jsonObject
            val tokenObj = responseObj?.get("token")?.jsonObject
            tokenObj?.get("server")?.jsonPrimitive?.content
        }.onFailure { log.warn("Failed to fetch guest token from Lunii API: {}", it.message) }
            .getOrNull()
    }

    private fun fetchPacksDatabase(token: String): JsonObject? {
        return runCatching {
            val body = restClient.get()
                .uri(MetadataDb.LUNII_PACKS_DATABASE_URL)
                .header("Accept", "application/json")
                .header("X-AUTH-TOKEN", token)
                .retrieve()
                .body(String::class.java)
                ?: return null
            val root = Json.parseToJsonElement(body).jsonObject
            root["response"]?.jsonObject
        }.onFailure { log.warn("Failed to fetch packs database from Lunii API: {}", it.message) }
            .getOrNull()
    }

    private fun getOfficialDatabasePath(): String =
        studioProperties.officialJsonPath.toString()

    private fun readOfficialDatabase(): JsonObject? {
        val file = File(getOfficialDatabasePath())
        if (!file.exists() || !file.isFile) return null
        return try {
            Json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun writeOfficialDatabase(response: JsonObject) {
        val path = getOfficialDatabasePath()
        File(path).parentFile?.mkdirs()
        File(path).writeText(json.encodeToString(JsonObject.serializer(), response))
    }
}
