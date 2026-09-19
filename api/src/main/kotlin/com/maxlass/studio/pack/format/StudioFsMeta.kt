package com.maxlass.studio.pack.format

import com.maxlass.studio.pack.domain.model.PackMetadata
import com.maxlass.studio.pack.port.external.PackFileMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * StoryUnchained sidecar in an FS pack folder: display metadata + provenance flag.
 * Only written for packs created via the Unchained story wizard (`unchained = true`).
 * Kept cleartext on device ([com.maxlass.studio.device.driver.FsCipher.CLEAR_FILES]).
 */
object StudioFsMeta {

    const val META_FILENAME = "studio-meta.json"
    const val THUMBNAIL_FILENAME = "studio-thumbnail.png"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class Payload(
        val unchained: Boolean = true,
        val title: String? = null,
        val description: String? = null,
        val locale: String? = null,
        val ageMin: Int? = null,
        val ageMax: Int? = null,
        val durationMs: Int? = null,
        val storyCount: Int? = null,
        val linkedOfficialPackId: String? = null,
        /** Relative filename of the cover PNG inside the pack folder, when present. */
        val thumbnail: String? = null,
    )

    fun write(packFolder: Path, metadata: PackMetadata, thumbnailPngBytes: ByteArray? = null) {
        require(metadata.unchained) { "FS sidecar is only written for Unchained packs" }
        val thumbName = if (thumbnailPngBytes != null && thumbnailPngBytes.isNotEmpty()) {
            Files.write(packFolder.resolve(THUMBNAIL_FILENAME), thumbnailPngBytes)
            THUMBNAIL_FILENAME
        } else if (Files.isRegularFile(packFolder.resolve(THUMBNAIL_FILENAME))) {
            THUMBNAIL_FILENAME
        } else {
            null
        }
        val payload = Payload(
            unchained = true,
            title = metadata.title,
            description = metadata.description,
            locale = metadata.locale,
            ageMin = metadata.ageMin,
            ageMax = metadata.ageMax,
            durationMs = metadata.durationMs,
            storyCount = metadata.storyCount,
            linkedOfficialPackId = metadata.linkedOfficialPackId,
            thumbnail = thumbName,
        )
        Files.writeString(
            packFolder.resolve(META_FILENAME),
            json.encodeToString(Payload.serializer(), payload),
        )
    }

    /**
     * Patches an existing (or creates a new) sidecar from [PackFileMetadata].
     * Caller must ensure the pack is Unchained.
     */
    fun update(packFolder: Path, fileMetadata: PackFileMetadata, unchained: Boolean = true) {
        val existing = read(packFolder)
        if (fileMetadata.thumbnailPngBytes != null && fileMetadata.thumbnailPngBytes.isNotEmpty()) {
            Files.write(packFolder.resolve(THUMBNAIL_FILENAME), fileMetadata.thumbnailPngBytes)
        }
        val hasThumb = Files.isRegularFile(packFolder.resolve(THUMBNAIL_FILENAME))
        val payload = Payload(
            unchained = fileMetadata.unchained ?: existing?.unchained ?: unchained,
            title = fileMetadata.title ?: existing?.title,
            description = fileMetadata.description ?: existing?.description,
            locale = fileMetadata.locale ?: existing?.locale,
            ageMin = fileMetadata.ageMin ?: existing?.ageMin,
            ageMax = fileMetadata.ageMax ?: existing?.ageMax,
            durationMs = fileMetadata.durationMs ?: existing?.durationMs,
            storyCount = fileMetadata.storyCount ?: existing?.storyCount,
            linkedOfficialPackId = existing?.linkedOfficialPackId,
            thumbnail = if (hasThumb) THUMBNAIL_FILENAME else existing?.thumbnail,
        )
        Files.writeString(
            packFolder.resolve(META_FILENAME),
            json.encodeToString(Payload.serializer(), payload),
        )
    }

    fun read(packFolder: Path): Payload? {
        val metaPath = packFolder.resolve(META_FILENAME)
        if (!Files.isRegularFile(metaPath)) return null
        return runCatching {
            json.decodeFromString(Payload.serializer(), Files.readString(metaPath))
        }.getOrNull()
    }

    fun readThumbnailBytes(packFolder: Path): ByteArray? {
        val payload = read(packFolder)
        val name = payload?.thumbnail ?: THUMBNAIL_FILENAME
        val path = packFolder.resolve(name)
        return if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
    }
}
