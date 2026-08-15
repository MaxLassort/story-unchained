package com.maxlass.studio.pack.domain.dto

import com.maxlass.studio.pack.domain.model.Pack
import kotlinx.serialization.Serializable

/**
 * Paginated response for GET /packs.
 * @property content Packs for the current page.
 * @property totalCount Total number of packs (all pages).
 * @property page Zero-based page index.
 * @property pageSize Number of items per page.
 */
@Serializable
data class PagedPacksResponse(
    val content: List<Pack>,
    val totalCount: Long,
    val page: Int,
    val pageSize: Int
) {
    val totalPages: Int
        get() = if (pageSize <= 0) 0 else ((totalCount + pageSize - 1) / pageSize).toInt()

    val hasNext: Boolean get() = page + 1 < totalPages
    val hasPrevious: Boolean get() = page > 0
}
