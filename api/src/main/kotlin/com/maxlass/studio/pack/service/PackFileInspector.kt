package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.PACK_EXT_RAW
import com.maxlass.studio.pack.domain.dto.RawPackMeta
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.port.external.MetaDataReaderPort
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile

class PackFileInspector(
    private val metadataReader: MetaDataReaderPort
) {
    fun detectFormatAndMetadata(file: File): Pair<PackFormat, RawPackMeta>? =
        when {
            file.isDirectory -> detectFromDirectory(file)
            file.isFile && file.extension.equals("zip", ignoreCase = true) -> detectFromZipFile(file)
            file.isFile -> detectFromRawFile(file)
            else -> null
        }

    private fun detectFromDirectory(directory: File): Pair<PackFormat, RawPackMeta>? {
        if (!looksLikeFsDirectory(directory)) return null
        return metadataReader.readFsMetadata(directory.toPath())?.let { PackFormat.FS to it }
    }

    private fun detectFromZipFile(file: File): Pair<PackFormat, RawPackMeta>? {
        val fsMeta = detectFsInsideZip(file)
        if (fsMeta != null) return PackFormat.FS to fsMeta
        if (!looksLikeArchiveZip(file)) return null
        val archiveMeta = metadataReader.readArchiveMetadata(file.toPath())
        return archiveMeta?.let { PackFormat.ARCHIVE to it }
    }

    private fun detectFromRawFile(file: File): Pair<PackFormat, RawPackMeta>? {
        if (!file.extension.equals(PACK_EXT_RAW, ignoreCase = true)) return null
        val binaryMeta = file.inputStream().use { metadataReader.readBinaryMetadata(it) }
        return binaryMeta?.let { PackFormat.RAW to it }
    }

    /**
     * Detects FS packs stored as a single .zip (UUID root + ni/li/ri).
     * Extracts to temp dir, reads metadata, then cleans up.
     */
    fun detectFsInsideZip(file: File): RawPackMeta? {
        val rootFolder = findUuidRootFolderWithFsMarkers(file) ?: return null
        val tempDir = Files.createTempDirectory("pack-fs-inside-zip-").toFile()
        return runCatching {
            unzipToDirectory(file, tempDir)
            metadataReader.readFsMetadata(File(tempDir, rootFolder).toPath())
        }.getOrNull().also { tempDir.deleteRecursively() }
    }

    fun findUuidRootFolderWithFsMarkers(zipFile: File): String? {
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries().asSequence().toList()
            if (entries.isEmpty()) return null
            val firstSegments = entries.mapNotNull { entry ->
                entry.name.trimStart('/').substringBefore('/', missingDelimiterValue = "").ifBlank { null }
            }.toSet()
            if (firstSegments.size != 1) return null
            val root = firstSegments.first()
            if (!isUuidLike(root)) return null
            val hasNi = entries.any { it.name.equals("$root/ni", ignoreCase = true) }
            val hasLi = entries.any { it.name.equals("$root/li", ignoreCase = true) }
            val hasRi = entries.any { it.name.equals("$root/ri", ignoreCase = true) }
            return if (hasNi || hasLi || hasRi) root else null
        }
    }

    fun looksLikeArchiveZip(zipFile: File): Boolean {
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries().asSequence().toList()
            val hasStoryJsonAtRoot = entries.any { !it.isDirectory && it.name.equals("story.json", ignoreCase = true) }
            val hasAssetsAtRoot = entries.any { !it.isDirectory && it.name.startsWith("assets/", ignoreCase = true) }
            return hasStoryJsonAtRoot && hasAssetsAtRoot
        }
    }

    fun looksLikeFsDirectory(directory: File): Boolean {
        val hasNi = File(directory, "ni").isFile
        val hasLi = File(directory, "li").isFile
        val hasRi = File(directory, "ri").isFile
        return hasNi || hasLi || hasRi
    }

    private fun isUuidLike(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    fun unzipToDirectory(zipFile: File, destinationDirectory: File) {
        ZipFile(zipFile).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                val outFile = File(destinationDirectory, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        Files.copy(input, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }
}
