package com.maxlass.studio.pack.port.external

import com.maxlass.studio.pack.domain.dto.RawPackMeta
import java.io.InputStream
import java.nio.file.Path

/**
 * Port for reading raw metadata from pack files or directories (ZIP, .pack, FS).
 * Implemented by [MetaDataReaderAdapter][com.maxlass.studio.pack.adapters.external.MetaDataReaderAdapter].
 */
interface MetaDataReaderPort {

    /** Reads metadata from an archive (ZIP) file. */
    fun readArchiveMetadata(path: Path): RawPackMeta?

    /** Reads metadata from a raw binary (.pack) input stream. */
    fun readBinaryMetadata(inputStream: InputStream): RawPackMeta?

    /** Reads metadata from a filesystem pack directory. */
    fun readFsMetadata(path: Path): RawPackMeta?
}
