package com.maxlass.studio.pack.port.external

import java.nio.file.Path

/**
 * Port for extracting the first image from an FS (filesystem) pack as PNG bytes.
 * Used for non-official packs to get a thumbnail from the pack data.
 */
fun interface ExtractThumbnailFromFsPackPort {

    /**
     * Reads the pack at [packPath], extracts the first stage node image (BMP), converts to PNG.
     * @return PNG bytes or null if no image or on error
     */
    fun extractThumbnail(packPath: Path): ByteArray?
}
