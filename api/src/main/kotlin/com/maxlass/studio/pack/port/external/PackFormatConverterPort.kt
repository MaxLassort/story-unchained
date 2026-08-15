package com.maxlass.studio.pack.port.external

import com.maxlass.studio.pack.domain.model.PackFormat
import java.nio.file.Path

/**
 * Port for converting a pack from one physical format to another using studio-core readers/writers.
 */
fun interface PackFormatConverterPort {
    /**
     * Converts a pack from [sourceFormat] to [targetFormat] by using studio-core readers/writers.
     *
     * @param sourcePath Path to the source physical variant (file or directory, depending on format).
     * @param destinationDir Destination directory where the converted variant will be stored.
     */
    fun convert(
        sourcePath: Path,
        sourceFormat: PackFormat,
        targetFormat: PackFormat,
        destinationDir: Path
    ): Path
}
