package com.maxlass.studio.pack.util

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal fun findThumbnailInZip(entryName: String): String? {
    val path = entryName.trimStart('/', '\\').replace('\\', '/')
    return when {
        path.equals("meta/thumbnail.png", ignoreCase = true) -> "meta/thumbnail.png"
        path.equals("thumbnail.png", ignoreCase = true) -> "thumbnail.png"
        else -> null
    }
}

/**
 * Returns the pack thumbnail entry, preferring `meta/thumbnail.png` (the real cover written by
 * the app and pack tools) over a legacy root `thumbnail.png`.
 */
internal fun findThumbnailEntry(zipFile: ZipFile): ZipEntry? {
    val entries = zipFile.entries().asSequence().filter { !it.isDirectory }.toList()
    return entries.firstOrNull { findThumbnailInZip(it.name) == "meta/thumbnail.png" }
        ?: entries.firstOrNull { findThumbnailInZip(it.name) != null }
}

/**
 * Reads the pack cover bytes from a zip (preferring `meta/thumbnail.png`, then a legacy root
 * `thumbnail.png`). Returns `null` when the entry is absent or empty, or the zip cannot be read.
 */
internal fun readThumbnailBytes(zipPath: Path): ByteArray? =
    runCatching {
        ZipFile(zipPath.toFile()).use { zf ->
            val entry = findThumbnailEntry(zf) ?: return@use null
            val bytes = zf.getInputStream(entry).use { it.readBytes() }
            if (bytes.isEmpty()) null else bytes
        }
    }.getOrNull()