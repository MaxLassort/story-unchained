package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.port.external.MetadataRefreshPort
import org.springframework.stereotype.Service

/**
 * Use case: refreshes the official Lunii metadata database (fetches from API, writes official.json).
 */
@Service
class RefreshOfficialMetadataUseCase(
    private val metadataRefreshPort: MetadataRefreshPort
) {
    fun invoke() {
        metadataRefreshPort.refreshOfficialMetadata()
    }
}
