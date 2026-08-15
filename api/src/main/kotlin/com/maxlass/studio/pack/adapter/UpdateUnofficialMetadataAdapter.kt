package com.maxlass.studio.pack.adapter

import com.maxlass.studio.infrastructure.metadata.DatabasePackMetadata
import com.maxlass.studio.infrastructure.metadata.MetadataStore
import com.maxlass.studio.pack.port.external.UpdateUnofficialMetadataPort

/**
 * Adapter that updates unofficial pack metadata via [MetadataStore].
 * Official packs are not updated (metadata comes from the official API).
 */
class UpdateUnofficialMetadataAdapter(
    private val metadataStore: MetadataStore
) : UpdateUnofficialMetadataPort {

    override fun updateUnofficialMetadata(uuid: String, title: String?, description: String?, thumbnail: String?) {
        if (metadataStore.isOfficialPack(uuid)) return
        val meta = DatabasePackMetadata(uuid, title, description, thumbnail, false)
        metadataStore.refreshUnofficialMetadata(meta)
    }
}
