package com.maxlass.studio.pack.adapter

import com.maxlass.studio.pack.port.external.PackFileMetadata
import com.maxlass.studio.pack.port.external.UpdatePackFileMetadataPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class UpdateZipMetadataAdapter : UpdatePackFileMetadataPort {

    companion object {
        private val log = LoggerFactory.getLogger(UpdateZipMetadataAdapter::class.java)
        private const val THUMBNAIL_ENTRY = "meta/thumbnail.png"
    }

    private val json = Json { prettyPrint = true }

    override fun updateArchiveMetadata(zipPath: Path, metadata: PackFileMetadata): Path {
        val hasStoryJsonChanges = metadata.title != null || metadata.description != null ||
            metadata.locale != null || metadata.ageMin != null || metadata.ageMax != null ||
            metadata.durationMs != null || metadata.storyCount != null

        val tmp = Files.createTempFile("studio_kmp_metadata_", ".zip")
        try {
            ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
                ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
                    var thumbnailWritten = false

                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "story.json" && hasStoryJsonChanges) {
                            val rawBytes = readEntryBytes(zis)
                            val modified = modifyStoryJson(rawBytes, metadata)
                            zos.putNextEntry(ZipEntry("story.json"))
                            zos.write(modified)
                            zos.closeEntry()
                        } else if (entry.name == THUMBNAIL_ENTRY && metadata.thumbnailPngBytes != null) {
                            zis.skip(Long.MAX_VALUE)
                            zos.putNextEntry(ZipEntry(THUMBNAIL_ENTRY))
                            zos.write(metadata.thumbnailPngBytes)
                            zos.closeEntry()
                            thumbnailWritten = true
                        } else {
                            zos.putNextEntry(ZipEntry(entry.name))
                            zis.copyTo(zos)
                            zos.closeEntry()
                        }
                        entry = zis.nextEntry
                    }

                    if (metadata.thumbnailPngBytes != null && !thumbnailWritten) {
                        zos.putNextEntry(ZipEntry(THUMBNAIL_ENTRY))
                        zos.write(metadata.thumbnailPngBytes)
                        zos.closeEntry()
                    }
                }
            }
            Files.move(tmp, zipPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
        return zipPath
    }

    private fun modifyStoryJson(rawBytes: ByteArray, metadata: PackFileMetadata): ByteArray {
        val root = Json.parseToJsonElement(rawBytes.decodeToString()).jsonObject
        val mutated = root.toMutableMap()
        metadata.title?.let { mutated["title"] = JsonPrimitive(it) }
        metadata.description?.let { mutated["description"] = JsonPrimitive(it) }
        metadata.locale?.let { mutated["locale"] = JsonPrimitive(it) }
        metadata.ageMin?.let { mutated["ageMin"] = JsonPrimitive(it) }
        metadata.ageMax?.let { mutated["ageMax"] = JsonPrimitive(it) }
        metadata.durationMs?.let { mutated["durationMs"] = JsonPrimitive(it) }
        metadata.storyCount?.let { mutated["storyCount"] = JsonPrimitive(it) }
        return json.encodeToString(JsonObject.serializer(), JsonObject(mutated)).toByteArray(Charsets.UTF_8)
    }

    private fun readEntryBytes(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        input.copyTo(buffer)
        return buffer.toByteArray()
    }
}
