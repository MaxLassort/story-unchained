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
import com.maxlass.studio.pack.util.findThumbnailEntry
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile

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
        officialCache: Map<String, OfficialMetadataDto>
    ): Pack {
        val fromOfficial = officialCache[meta.uuid]
        val thumbnail = resolveThumbnail(file, meta.uuid, format, fromOfficial)
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
        fromOfficial: OfficialMetadataDto?
    ): String? {
        if (fromOfficial != null) return fromOfficial.thumbnailUrl

        return when (format) {
            PackFormat.ARCHIVE -> resolveArchiveThumbnail(file, packId)
            PackFormat.FS -> resolveFsThumbnail(file)
            else -> null
        }
    }

    private fun resolveArchiveThumbnail(zipFile: File, packId: String): String? {
        val cached = thumbnailCache.get(packId)
        if (cached != null) return "data:image/png;base64,${Base64.getEncoder().encodeToString(cached)}"

        val pngBytes = readThumbnailFromZipRoot(zipFile) ?: return null
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

    private fun readThumbnailFromZipRoot(zipFile: File): ByteArray? =
        runCatching {
            ZipFile(zipFile).use { zf ->
                val thumbEntry = findThumbnailEntry(zf) ?: return@use null
                val bytes = zf.getInputStream(thumbEntry).use { it.readBytes() }
                if (bytes.isEmpty()) null else bytes
            }
        }.getOrNull()
}
