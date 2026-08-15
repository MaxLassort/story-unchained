package com.maxlass.studio.pack.adapter

import com.maxlass.studio.pack.domain.dto.RawPackMeta
import com.maxlass.studio.pack.format.model.StoryPackMetadata
import com.maxlass.studio.pack.format.reader.ArchiveStoryPackReader
import com.maxlass.studio.pack.format.reader.BinaryStoryPackReader
import com.maxlass.studio.pack.format.reader.FsStoryPackReader
import com.maxlass.studio.pack.port.external.MetaDataReaderPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * External adapter that implements [MetaDataReaderPort] using studio-core readers.
 * Converts [StoryPackMetadata] to domain [RawPackMeta].
 */
class MetaDataReaderAdapter : MetaDataReaderPort {

    private val archiveReader = ArchiveStoryPackReader()
    private val binaryReader = BinaryStoryPackReader()
    private val fsReader = FsStoryPackReader()

    override fun readArchiveMetadata(path: Path): RawPackMeta? {
        return try {
            val base = FileInputStream(path.toFile()).use { archiveReader.readMetadata(it) }?.toRawPackMeta()
                ?: return null
            val extra = readExtendedArchiveMetadata(path)
            base.copy(
                locale = extra.locale,
                ageMin = extra.ageMin,
                ageMax = extra.ageMax,
                durationMs = extra.durationMs,
                storyCount = extra.storyCount,
            )
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun readBinaryMetadata(inputStream: InputStream): RawPackMeta? {
        return try {
            binaryReader.readMetadata(inputStream).toRawPackMeta()
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun readFsMetadata(path: Path): RawPackMeta? {
        return try {
            fsReader.readMetadata(path).toRawPackMeta()
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    /** Maps studio-core [StoryPackMetadata] to domain [RawPackMeta]. */
    private fun StoryPackMetadata.toRawPackMeta(): RawPackMeta =
        RawPackMeta(
            uuid = uuid ?: "",
            title = title,
            description = description,
            version = version,
            isNightModeAvailable = nightModeAvailable
        )

    private data class ExtendedArchiveMetadata(
        val locale: String?,
        val ageMin: Int?,
        val ageMax: Int?,
        val durationMs: Int?,
        val storyCount: Int?,
    )

    private fun readExtendedArchiveMetadata(path: Path): ExtendedArchiveMetadata =
        runCatching {
            ZipFile(path.toFile()).use { zf ->
                val entry = zf.entries().asSequence().firstOrNull {
                    !it.isDirectory && it.name.equals("story.json", ignoreCase = true)
                } ?: return@use ExtendedArchiveMetadata(null, null, null, null, null)
                val root = zf.getInputStream(entry).use {
                    Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
                }
                ExtendedArchiveMetadata(
                    locale = root["locale"]?.jsonPrimitive?.contentOrNull,
                    ageMin = root["ageMin"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                    ageMax = root["ageMax"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                    durationMs = root["durationMs"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                    storyCount = root["storyCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                )
            }
        }.getOrElse { ExtendedArchiveMetadata(null, null, null, null, null) }
}
