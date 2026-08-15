package com.maxlass.studio.pack.port.external

import java.nio.file.Path

/**
 * Pack-level metadata to write into an archive (zip) pack file.
 * Null fields are left untouched in the existing story.json.
 */
data class PackFileMetadata(
    val title: String? = null,
    val description: String? = null,
    val locale: String? = null,
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    val durationMs: Int? = null,
    val storyCount: Int? = null,
    val thumbnailPngBytes: ByteArray? = null,
)

fun interface UpdatePackFileMetadataPort {
    fun updateArchiveMetadata(zipPath: Path, metadata: PackFileMetadata): Path
}
