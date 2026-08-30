package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.cache.ThumbnailCache
import com.maxlass.studio.pack.domain.dto.OfficialMetadataDto
import com.maxlass.studio.pack.domain.dto.RawPackMeta
import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.domain.model.PackMetadata
import com.maxlass.studio.pack.domain.model.PackVariant
import com.maxlass.studio.pack.port.external.ExtractThumbnailFromFsPackPort
import com.maxlass.studio.pack.port.external.MetaDataReaderPort
import com.maxlass.studio.pack.util.readThumbnailBytes
import java.io.File
import java.util.Base64

class PackMetaExtractor(
    private val metadataReader: MetaDataReaderPort,
    private val extractThumbnailFromFsPack: ExtractThumbnailFromFsPackPort,
    private val thumbnailCache: ThumbnailCache
) {
    private val fileInspector = PackFileInspector(metadataReader)

    fun buildPack(
        file: File,
        meta: RawPackMeta,
        format: PackFormat,
        officialCache: Map<String, OfficialMetadataDto>,
        existingThumbnail: String? = null,
        hasArchiveVariant: Boolean = false
    ): Pack {
        val fromOfficial = officialCache[meta.uuid]
        val thumbnail = resolveThumbnail(file, meta.uuid, format, fromOfficial, existingThumbnail, hasArchiveVariant)
        val metadata = PackMetadata(
            title = fromOfficial?.title ?: meta.title,
            description = fromOfficial?.description ?: meta.description,
            thumbnail = thumbnail,
            version = meta.version,
            factoryDisabled = false,
            nightModeAvailable = meta.isNightModeAvailable,
            official = fromOfficial != null,
            linkedOfficialPackId = null,
            locale = fromOfficial?.locale ?: meta.locale,
            ageMin = fromOfficial?.ageMin ?: meta.ageMin,
            ageMax = fromOfficial?.ageMax ?: meta.ageMax,
            durationMs = fromOfficial?.durationMs ?: meta.durationMs,
            storyCount = fromOfficial?.storyCount ?: meta.storyCount,
        )
        return Pack(
            id = meta.uuid,
            metadata = metadata,
            variants = listOf(PackVariant(format = format, storagePath = file.absolutePath))
        )
    }

    private fun resolveThumbnail(
        file: File,
        packId: String,
        format: PackFormat,
        fromOfficial: OfficialMetadataDto?,
        existingThumbnail: String?,
        hasArchiveVariant: Boolean
    ): String? {
        if (fromOfficial != null) return fromOfficial.thumbnailUrl

        return when (format) {
            PackFormat.ARCHIVE -> resolveArchiveThumbnail(file, packId) ?: existingThumbnail
            // A pack that also exists as an archive zip stores its real cover in the zip's
            // `meta/thumbnail.png`. Reuse that cover instead of overwriting it with the raw first
            // FS image (which is a placeholder-quality "default"), otherwise a 2-format pack ends
            // up showing the wrong thumbnail.
            PackFormat.FS -> if (hasArchiveVariant) {
                resolveCachedThumbnail(packId) ?: existingThumbnail
            } else {
                resolveFsThumbnail(file)
            }
            else -> null
        }
    }

    private fun resolveCachedThumbnail(packId: String): String? =
        thumbnailCache.get(packId)?.let {
            "data:image/png;base64,${Base64.getEncoder().encodeToString(it)}"
        }

    private fun resolveArchiveThumbnail(zipFile: File, packId: String): String? {
        val cached = thumbnailCache.get(packId)
        if (cached != null) return "data:image/png;base64,${Base64.getEncoder().encodeToString(cached)}"

        val pngBytes = readThumbnailBytes(zipFile.toPath()) ?: return null
        thumbnailCache.put(packId, pngBytes)
        return "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
    }

    private fun resolveFsThumbnail(file: File): String? {
        if (file.isFile && file.extension.equals("zip", ignoreCase = true)) {
            return resolveFsThumbnailFromZip(file)
        }
        if (!file.isDirectory) return null
        val pngBytes = extractThumbnailFromFsPack.extractThumbnail(file.toPath()) ?: return null
        return "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
    }

    private fun resolveFsThumbnailFromZip(zipFile: File): String? {
        val rootFolder = fileInspector.findUuidRootFolderWithFsMarkers(zipFile) ?: return null
        val tempDir = java.nio.file.Files.createTempDirectory("pack-fs-thumb-zip-").toFile()
        return runCatching {
            fileInspector.unzipToDirectory(zipFile, tempDir)
            val pngBytes = extractThumbnailFromFsPack.extractThumbnail(File(tempDir, rootFolder).toPath())
            pngBytes?.let { "data:image/png;base64,${Base64.getEncoder().encodeToString(it)}" }
        }.getOrNull().also { tempDir.deleteRecursively() }
    }

}
