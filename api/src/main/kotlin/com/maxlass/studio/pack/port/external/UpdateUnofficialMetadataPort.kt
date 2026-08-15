package com.maxlass.studio.pack.port.external

/**
 * Port for updating unofficial pack metadata (title, description, thumbnail) in the metadata DB.
 * Used when the user edits metadata for a pack that is not in the official catalog.
 */
interface UpdateUnofficialMetadataPort {

    /**
     * Updates the unofficial metadata for the pack with [uuid].
     * No-op if the pack is official (official metadata is read-only from the API).
     * [thumbnail] can be a URL or a data:image/...;base64,... string.
     */
    fun updateUnofficialMetadata(uuid: String, title: String?, description: String?, thumbnail: String?)
}
