package com.maxlass.studio.pack.port.external

import com.maxlass.studio.pack.domain.dto.OfficialMetadataDto

/**
 * Port for refreshing and querying the official Lunii metadata JSON database.
 * Implemented by [MetadataRefreshAdapter][com.maxlass.studio.pack.adapters.external.MetadataRefreshAdapter].
 */
interface MetadataRefreshPort {

    /** Refreshes the official metadata by fetching from the Lunii API and writing official.json. */
    fun refreshOfficialMetadata()

    /**
     * Looks up official metadata for a pack by [uuid].
     * @return The official metadata, or null if the pack is not in the official catalog.
     */
    fun findOfficialMetadataById(uuid: String): OfficialMetadataDto?

    /** Loads all official metadata once and returns a UUID-indexed map for fast lookups. */
    fun getOfficialMetadataMap(): Map<String, OfficialMetadataDto>
}
