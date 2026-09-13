package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.dto.PackFilter
import com.maxlass.studio.pack.domain.dto.PagedPacksResponse
import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.domain.model.PackMetadata
import com.maxlass.studio.pack.port.external.MetadataRefreshPort
import org.springframework.stereotype.Service

/**
 * Lists the packs of the official Lunii catalog (official.json), paginated and filtered.
 * The JSON file is the database here: entries have no library variants.
 */
@Service
class ListOfficialPacksUseCase(
    private val metadataRefreshPort: MetadataRefreshPort,
) {
    suspend fun invoke(page: Int, pageSize: Int, filter: PackFilter = PackFilter()): PagedPacksResponse {
        val safePage = page.coerceAtLeast(0)
        val safeSize = pageSize.coerceIn(1, 200)
        val tokens = filter.searchTokens()

        val all = metadataRefreshPort.getOfficialMetadataMap().entries
            .filter { (_, dto) ->
                (tokens == null || tokens.any { token -> dto.title?.contains(token, ignoreCase = true) == true }) &&
                    (filter.locale == null || dto.locale == null || dto.locale == filter.locale) &&
                    filter.matchesAge(dto.ageMin, dto.ageMax)
            }
            .map { (uuid, dto) ->
                Pack(
                    id = uuid,
                    metadata = PackMetadata(
                        title = dto.title,
                        description = dto.description,
                        thumbnail = dto.thumbnailUrl,
                        version = OFFICIAL_CATALOG_VERSION,
                        factoryDisabled = false,
                        nightModeAvailable = false,
                        official = true,
                        linkedOfficialPackId = null,
                        locale = dto.locale,
                        ageMin = dto.ageMin,
                        ageMax = dto.ageMax,
                        durationMs = dto.durationMs,
                        storyCount = dto.storyCount,
                    ),
                    variants = emptyList(),
                )
            }

        val total = all.size.toLong()
        val content = all.drop(safePage * safeSize).take(safeSize)
        return PagedPacksResponse(
            content = content,
            totalCount = total,
            page = safePage,
            pageSize = safeSize,
        )
    }

    private companion object {
        const val OFFICIAL_CATALOG_VERSION: Short = 1
    }
}
