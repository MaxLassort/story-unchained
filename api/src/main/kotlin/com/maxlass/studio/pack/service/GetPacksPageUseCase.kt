package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.dto.PackFilter
import com.maxlass.studio.pack.domain.dto.PagedPacksResponse
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import org.springframework.stereotype.Service

@Service
class GetPacksPageUseCase(
    private val packRepository: PackRepositoryPort
) {
    suspend fun invoke(page: Int, pageSize: Int, filter: PackFilter = PackFilter()): PagedPacksResponse {
        val safePage = page.coerceAtLeast(0)
        val safeSize = pageSize.coerceIn(1, 200)
        val (content, totalCount) = packRepository.getFilteredPacksPage(
            offset = safePage * safeSize,
            limit = safeSize,
            filter = filter,
        )
        return PagedPacksResponse(
            content = content,
            totalCount = totalCount,
            page = safePage,
            pageSize = safeSize,
        )
    }
}
