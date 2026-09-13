package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.dto.OfficialMetadataDto
import com.maxlass.studio.pack.domain.dto.PackFilter
import com.maxlass.studio.pack.port.external.MetadataRefreshPort
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ListOfficialPacksUseCaseTest {

    private val metadataRefreshPort = mockk<MetadataRefreshPort>()
    private val useCase = ListOfficialPacksUseCase(metadataRefreshPort)

    private val catalog = mapOf(
        "uuid-1" to OfficialMetadataDto(
            title = "Story Disney 2023", description = null, thumbnailUrl = "https://img/1.png",
            locale = "fr_FR", ageMin = 3, ageMax = 6, durationMs = 1000, storyCount = 4,
        ),
        "uuid-2" to OfficialMetadataDto(title = "disney", description = null, thumbnailUrl = null, locale = "en_EN"),
        "uuid-3" to OfficialMetadataDto(
            title = "DISNEY MARVEL", description = null, thumbnailUrl = null, locale = "fr_FR", ageMin = 5, ageMax = 10,
        ),
        "uuid-4" to OfficialMetadataDto(
            title = "Marvel", description = null, thumbnailUrl = null, locale = "de_DE", ageMin = 1, ageMax = 2,
        ),
        "uuid-5" to OfficialMetadataDto(
            title = "Partiel", description = null, thumbnailUrl = null, locale = "fr_FR", ageMin = 3,
        ),
    )

    @BeforeEach
    fun setUp() {
        every { metadataRefreshPort.getOfficialMetadataMap() } returns catalog
    }

    private fun page(filter: PackFilter, page: Int = 0, size: Int = 100) = runBlocking {
        useCase.invoke(page, size, filter)
    }

    private fun ids(filter: PackFilter): Set<String> = page(filter).content.map { it.id }.toSet()

    @Test
    fun `no filter returns whole catalog mapped as official packs`() = runBlocking {
        val response = page(PackFilter())
        assertEquals(catalog.keys, response.content.map { it.id }.toSet())
        assertEquals(catalog.size.toLong(), response.totalCount)
        val first = response.content.first { it.id == "uuid-1" }
        assertTrue(first.metadata.official)
        assertTrue(first.variants.isEmpty())
        assertEquals("Story Disney 2023", first.metadata.title)
        assertEquals("https://img/1.png", first.metadata.thumbnail)
        assertEquals(3, first.metadata.ageMin)
        assertEquals(6, first.metadata.ageMax)
        assertEquals(1000, first.metadata.durationMs)
        assertEquals(4, first.metadata.storyCount)
    }

    @Test
    fun `search matches titles containing at least one token case-insensitive`() {
        assertEquals(setOf("uuid-1", "uuid-2", "uuid-3", "uuid-4"), ids(PackFilter(search = "disney marvel")))
    }

    @Test
    fun `locale filter keeps exact locale and null locale`() {
        assertEquals(setOf("uuid-1", "uuid-3", "uuid-5"), ids(PackFilter(locale = "fr_FR")))
    }

    @Test
    fun `age range keeps overlapping ranges and packs without age`() {
        assertEquals(setOf("uuid-1", "uuid-2", "uuid-3", "uuid-5"), ids(PackFilter(ageMin = 5, ageMax = 10)))
    }

    @Test
    fun `age range far away keeps only packs without age`() {
        assertEquals(setOf("uuid-2", "uuid-5"), ids(PackFilter(ageMin = 20, ageMax = 25)))
    }

    @Test
    fun `filters combine as intersection`() {
        assertEquals(
            setOf("uuid-1", "uuid-3"),
            ids(PackFilter(search = "disney", locale = "fr_FR", ageMin = 5, ageMax = 10)),
        )
    }

    @Test
    fun `pagination slices catalog and reports total`() = runBlocking {
        val response = page(PackFilter(), page = 1, size = 2)
        assertEquals(5L, response.totalCount)
        assertEquals(1, response.page)
        assertEquals(2, response.pageSize)
        assertEquals(2, response.content.size)
        assertEquals(listOf("uuid-3", "uuid-4"), response.content.map { it.id })
    }
}
