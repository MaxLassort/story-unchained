package com.maxlass.studio.pack.adapter

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.infrastructure.metadata.MetadataDb
import com.maxlass.studio.pack.domain.dto.OfficialMetadataDto
import com.maxlass.studio.pack.port.external.MetadataRefreshPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.io.File
import java.io.IOException

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

    override fun refreshOfficialMetadata() {
        val token = fetchGuestToken()
            ?: throw IllegalStateException("Failed to get guest token")
        val response = fetchPacksDatabase(token)
            ?: throw IllegalStateException("Failed to fetch packs database")
        writeOfficialDatabase(response)
    }

    override fun findOfficialMetadataById(uuid: String): OfficialMetadataDto? {
        return getOfficialMetadataMap()[uuid]
    }

    override fun getOfficialMetadataMap(): Map<String, OfficialMetadataDto> {
        val root = readOfficialDatabase() ?: return emptyMap()
        // Same as MetadataStore: API / legacy files may wrap packs under "response".
        val packsRoot = root["response"]?.jsonObject ?: root
        return packsRoot.entries.mapNotNull { (_, value) ->
            val pack = value as? JsonObject ?: return@mapNotNull null
            val uuid = pack["uuid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val localizedInfos = pack["localized_infos"]?.jsonObject ?: return@mapNotNull null
            val locale = if ("fr_FR" in localizedInfos) "fr_FR" else localizedInfos.keys.firstOrNull() ?: return@mapNotNull null
            val info = localizedInfos[locale]?.jsonObject ?: return@mapNotNull null
            val title = info["title"]?.jsonPrimitive?.content
            val description = info["description"]?.jsonPrimitive?.content
            val imageObj = info["image"]?.jsonObject
            val imageUrl = imageObj?.get("image_url")?.jsonPrimitive?.content
                ?.let { MetadataDb.THUMBNAILS_STORAGE_ROOT + it }
            uuid to OfficialMetadataDto(
                title = title,
                description = description,
                thumbnailUrl = imageUrl,
                locale = locale,
                ageMin = pack["age_min"]?.jsonPrimitive?.content?.toIntOrNull(),
                ageMax = pack["age_max"]?.jsonPrimitive?.content?.toIntOrNull(),
                durationMs = pack["duration"]?.jsonPrimitive?.content?.toIntOrNull(),
                storyCount = pack["story_count"]?.jsonPrimitive?.content?.toIntOrNull(),
            )
        }.toMap()
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
