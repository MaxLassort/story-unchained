package com.maxlass.studio.pack.port.persistence

import com.maxlass.studio.pack.domain.dto.PackFilter
import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.domain.model.PackFormat

interface PackRepositoryPort {

    suspend fun savePack(pack: Pack)

    suspend fun getAllPacks(): List<Pack>

    suspend fun getPacksPage(offset: Int, limit: Int): Pair<List<Pack>, Long>

    suspend fun getFilteredPacksPage(offset: Int, limit: Int, filter: PackFilter): Pair<List<Pack>, Long>

    suspend fun deletePackMetadata(packId: String)

    /** Removes the given format variants for a pack (DB rows only). */
    suspend fun deleteVariants(packId: String, formats: Collection<PackFormat>)
}
