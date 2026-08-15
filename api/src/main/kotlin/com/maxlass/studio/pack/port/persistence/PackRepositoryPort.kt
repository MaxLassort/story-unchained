package com.maxlass.studio.pack.port.persistence

import com.maxlass.studio.pack.domain.dto.PackFilter
import com.maxlass.studio.pack.domain.model.Pack

interface PackRepositoryPort {

    suspend fun savePack(pack: Pack)

    suspend fun getAllPacks(): List<Pack>

    suspend fun getPacksPage(offset: Int, limit: Int): Pair<List<Pack>, Long>

    suspend fun getFilteredPacksPage(offset: Int, limit: Int, filter: PackFilter): Pair<List<Pack>, Long>

    suspend fun deletePackMetadata(packId: String)
}
