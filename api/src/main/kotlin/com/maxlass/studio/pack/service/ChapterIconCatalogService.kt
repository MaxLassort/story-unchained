package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.dto.ChapterIconDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Catalog of Lucide SVG icons (ISC license) offered as fallback chapter images when the
 * user has no image for a chapter. A curated subset is bundled in `resources/icons/` for
 * offline use; any Lucide icon can also be fetched on demand from the lucide-static CDN,
 * identified by its kebab-case slug (e.g. `moon-star`), and is cached in memory.
 */
@Service
class ChapterIconCatalogService {

    companion object {
        private val logger = LoggerFactory.getLogger(ChapterIconCatalogService::class.java)
        private const val ICONS_LOCATION = "classpath*:icons/*.svg"
        private const val DEFAULT_ICON_BASE_URL = "https://cdn.jsdelivr.net/npm/lucide-static@latest"
        private const val DEFAULT_CATALOG_URL = "https://data.jsdelivr.com/v1/packages/npm/lucide-static@latest"
        private const val CATALOG_TTL_MS = 24L * 60 * 60 * 1000
        private const val MAX_REMOTE_CACHE = 500

        /** Slugs with an official Lucide name; added manually as needed when the catalog API is unreachable. */
        private val KNOWN_SLUGS = setOf(
            "anchor", "apple", "bike", "bird", "book", "book-open", "bus", "cake", "car", "castle",
            "cat", "cherry", "cloud", "cloud-moon", "cloud-sun", "coffee", "compass", "cookie",
            "cooking-pot", "crown", "cup-soda", "dog", "earth", "ferris-wheel", "fish", "flame",
            "flower-2", "ghost", "globe", "heart", "home", "ice-cream-cone", "map", "map-pin",
            "mic", "milk", "moon", "moon-star", "music", "paw-print", "pizza", "plane", "rabbit",
            "rainbow", "rocket", "sailboat", "shield", "ship", "snail", "snowflake", "soup",
            "space", "sparkles", "star", "sun", "sun-moon", "sword", "telescope", "tent",
            "train", "tree-deciduous", "tree-pine", "utensils", "wand-2", "wheat",
        )
    }

    /** Base URL of the lucide-static CDN (overridable for tests). */
    var iconBaseUrl: String = DEFAULT_ICON_BASE_URL

    /** URL of the jsDelivr catalog API (overridable for tests). */
    var catalogUrl: String = DEFAULT_CATALOG_URL

    private val bundledIcons: Map<String, ChapterIcon> = loadBundledIcons()
    private val remoteCache = ConcurrentHashMap<String, String>()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile
    private var remoteCatalog: Pair<List<String>, Long>? = null

    /** All bundled icons, sorted by id. */
    fun listIcons(): List<ChapterIconDto> =
        bundledIcons.values.sortedBy { it.dto.id }.map { it.dto }

    /** Raw SVG content of the icon with the given id, or null when unknown/unreachable. */
    suspend fun loadIcon(id: String): String? = withContext(Dispatchers.IO) {
        bundledIcons[id]?.svg
            ?: remoteCache[id]
            ?: fetchRemoteIcon(id)?.also { if (remoteCache.size < MAX_REMOTE_CACHE) remoteCache[id] = it }
    }

    /**
     * Searches the full Lucide icon catalog by name (case-insensitive substring on the slug),
     * returning up to [limit] matches. Bundled icons come first (they render offline).
     */
    suspend fun searchIcons(query: String, limit: Int = 50): List<ChapterIconDto> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()
        val bundled = bundledIcons.values
            .filter { it.dto.id.contains(q) }
            .sortedBy { it.dto.id }
            .map { it.dto }
        val remote = remoteCatalogSlugs()
            .asSequence()
            .filter { it.contains(q) }
            .sorted()
            .map { toDto(it) }
            .toList()
        (bundled + remote).distinctBy { it.id }.take(limit)
    }

    private fun toDto(slug: String): ChapterIconDto =
        ChapterIconDto(id = slug, name = slug.replace('-', ' ').replaceFirstChar(Char::uppercase))

    /** All Lucide slugs, fetched from the catalog API (cached 24h), with the bundled set as fallback. */
    private fun remoteCatalogSlugs(): List<String> {
        val cached = remoteCatalog
        if (cached != null && System.currentTimeMillis() - cached.second < CATALOG_TTL_MS) return cached.first
        val slugs = try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$catalogUrl"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) parseCatalogSlugs(response.body()) else null
        } catch (e: Exception) {
            logger.warn("Could not fetch Lucide catalog ({}), using bundled slugs", e.message)
            null
        }
        val result = slugs ?: KNOWN_SLUGS.toList()
        remoteCatalog = result to System.currentTimeMillis()
        return result
    }

    /** Extracts icon slugs from the jsDelivr catalog JSON (`files[].files[].name`). */
    private fun parseCatalogSlugs(json: String): List<String>? = try {
        val root = Json.parseToJsonElement(json)
        val iconsDir = root.jsonObject["files"]?.jsonArray
            ?.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == "icons" }
        val files = iconsDir?.jsonObject?.get("files")?.jsonArray ?: return null
        files.mapNotNull { file ->
            val name = file.jsonObject["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            name.removeSuffix(".svg").takeIf { name.endsWith(".svg") }
        }
    } catch (e: Exception) {
        logger.warn("Could not parse Lucide catalog JSON: {}", e.message)
        null
    }

    private fun fetchRemoteIcon(id: String): String? {
        if (!id.matches(Regex("[a-z0-9-]+")) || id.length > 64) return null
        val svg = try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$iconBaseUrl/icons/$id.svg"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            response.body()
        } catch (e: Exception) {
            logger.warn("Could not fetch Lucide icon '{}': {}", id, e.message)
            null
        }
        // Sanity check: must look like a real Lucide SVG (paths + viewBox).
        if (svg == null || !svg.contains("<path") || !svg.contains("viewBox")) return null
        return svg
    }

    private data class ChapterIcon(val dto: ChapterIconDto, val svg: String)

    private fun loadBundledIcons(): Map<String, ChapterIcon> {
        val resolver = PathMatchingResourcePatternResolver()
        return try {
            resolver.getResources(ICONS_LOCATION).mapNotNull { resource ->
                val fileName = resource.filename ?: return@mapNotNull null
                val id = fileName.removeSuffix(".svg")
                if (id.isEmpty()) return@mapNotNull null
                val svg = InputStreamReader(resource.inputStream, StandardCharsets.UTF_8).use { it.readText() }
                ChapterIcon(ChapterIconDto(id = id, name = id.replace('-', ' ').replaceFirstChar(Char::uppercase)), svg)
            }.associateBy { it.dto.id }
        } catch (e: Exception) {
            logger.warn("Could not load bundled chapter icons: {}", e.message)
            emptyMap()
        }
    }
}