package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import org.springframework.stereotype.Service

/**
 * Use case: returns all packs registered in the library.
 */
@Service
class GetAllPacksUseCase(
    private val packRepository: PackRepositoryPort
) {
    /** Returns all packs currently registered in the library. */
    suspend fun invoke(): List<Pack> = packRepository.getAllPacks()
}
