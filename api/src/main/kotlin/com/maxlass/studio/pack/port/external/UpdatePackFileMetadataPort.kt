package com.maxlass.studio.pack.port.external

import java.nio.file.Path

/**
 * Pack-level metadata to write into an archive (zip) or Unchained FS sidecar.
 * Null fields are left untouched in the existing story.json / studio-meta.json.
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
    /** When non-null, written into story.json / studio-meta.json (StoryUnchained provenance). */
    val unchained: Boolean? = null,
)

interface UpdatePackFileMetadataPort {
    fun updateArchiveMetadata(zipPath: Path, metadata: PackFileMetadata): Path

    /** Writes or patches [StudioFsMeta] sidecar in an FS pack folder (Unchained packs only). */
    fun updateFsMetadata(packFolder: Path, metadata: PackFileMetadata): Path
}
