package com.maxlass.studio.pack.port.external

import com.maxlass.studio.pack.domain.dto.UnofficialJsonEntry

/**
 * Port for loading unofficial pack metadata from a JSON file (e.g. Studio Core's unofficial.json).
 */
fun interface LoadUnofficialMetadataFromFilePort {

    /** Returns uuid -> entry for the file at [path], or an empty map if missing/invalid. */
    fun loadFromPath(path: String): Map<String, UnofficialJsonEntry>
}