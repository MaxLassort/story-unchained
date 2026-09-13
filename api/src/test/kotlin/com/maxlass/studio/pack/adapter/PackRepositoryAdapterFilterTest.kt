package com.maxlass.studio.pack.adapter

import com.maxlass.studio.infrastructure.persistence.PackEntity
import com.maxlass.studio.infrastructure.persistence.PackJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackMetadataEntity
import com.maxlass.studio.infrastructure.persistence.PackMetadataJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackVariantEntity
import com.maxlass.studio.infrastructure.persistence.PackVariantId
import com.maxlass.studio.infrastructure.persistence.PackVariantJpaRepository
import com.maxlass.studio.pack.domain.dto.PackFilter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager

class PackRepositoryAdapterFilterTest {

    private val packRepository = mockk<PackJpaRepository>()
    private val metadataRepository = mockk<PackMetadataJpaRepository>()
    private val variantRepository = mockk<PackVariantJpaRepository>()

    private val adapter = PackRepositoryAdapter(
        packRepository = packRepository,
        metadataRepository = metadataRepository,
        variantRepository = variantRepository,
        transactionManager = mockk<PlatformTransactionManager>(),
    )

    private data class PackSpec(
        val id: String,
        val title: String?,
        val locale: String?,
        val official: Boolean,
        val variantCount: Int,
        val ageMin: Int? = null,
        val ageMax: Int? = null,
    )

    private val specs = listOf(
        PackSpec("pack-1", title = "Story Disney 2023", locale = "fr_FR", official = false, variantCount = 1, ageMin = 3, ageMax = 6),
        PackSpec("pack-2", title = "disney", locale = "en_EN", official = true, variantCount = 1),
        PackSpec("pack-3", title = "DISNEY MARVEL", locale = "fr_FR", official = false, variantCount = 1, ageMin = 5, ageMax = 10),
        PackSpec("pack-4", title = "Marvel", locale = "fr_FR", official = false, variantCount = 0, ageMin = 1, ageMax = 2),
        PackSpec("pack-5", title = "Pas de titre", locale = "fr_FR", official = false, variantCount = 1, ageMin = 11, ageMax = 14),
        PackSpec("pack-6", title = null, locale = "fr_FR", official = true, variantCount = 1, ageMin = 3, ageMax = 8),
        PackSpec("pack-7", title = "", locale = "fr_FR", official = false, variantCount = 1),
        PackSpec("pack-8", title = "Partiel", locale = "fr_FR", official = false, variantCount = 1, ageMin = 3),
    )

    @BeforeEach
    fun setUp() {
        every { packRepository.findAll() } returns specs.map { PackEntity(id = it.id) }
        every { metadataRepository.findAll() } returns specs.map {
            PackMetadataEntity(
                packId = it.id,
                title = it.title,
                version = 1,
                factoryDisabled = false,
                nightModeAvailable = false,
                official = it.official,
                locale = it.locale,
                ageMin = it.ageMin,
                ageMax = it.ageMax,
            )
        }
        every { variantRepository.findAll() } returns specs.flatMap { spec ->
            (0 until spec.variantCount).map {
                PackVariantEntity(
                    id = PackVariantId(packId = spec.id, format = "FS"),
                    storagePath = "${spec.id}/pack.fs",
                )
            }
        }
    }

    private fun matchedIds(filter: PackFilter): Set<String> = runBlocking {
        adapter.getFilteredPacksPage(offset = 0, limit = 100, filter = filter).first.map { it.id }.toSet()
    }

    private fun allIds(): Set<String> = specs.map { it.id }.toSet()

    // -------------------------------------------------------------------------
    // Recherche nom : au moins 1 token présent, insensible à la casse
    // -------------------------------------------------------------------------

    @Test
    fun `search single word matches titles containing it case-insensitive`() {
        assertEquals(setOf("pack-1", "pack-2", "pack-3"), matchedIds(PackFilter(search = "disney")))
    }

    @Test
    fun `search multiple words keeps packs matching at least one token`() {
        assertEquals(setOf("pack-1", "pack-2", "pack-3", "pack-4"), matchedIds(PackFilter(search = "disney marvel")))
    }

    @Test
    fun `search tokens split on any whitespace`() {
        assertEquals(
            setOf("pack-1", "pack-2", "pack-3", "pack-4"),
            matchedIds(PackFilter(search = "  disney\t marvel  ")),
        )
    }

    @Test
    fun `search empty string does not filter`() {
        assertEquals(allIds(), matchedIds(PackFilter(search = "")))
    }

    @Test
    fun `search blank string does not filter`() {
        assertEquals(allIds(), matchedIds(PackFilter(search = "   ")))
    }

    @Test
    fun `search null does not filter`() {
        val (page, total) = runBlocking { adapter.getFilteredPacksPage(0, 100, PackFilter()) }
        assertEquals(allIds(), page.map { it.id }.toSet())
        assertEquals(specs.size.toLong(), total)
    }

    @Test
    fun `search unknown word returns empty page`() {
        val (page, total) = runBlocking { adapter.getFilteredPacksPage(0, 100, PackFilter(search = "unknown")) }
        assertTrue(page.isEmpty())
        assertEquals(0L, total)
    }

    @Test
    fun `search skips null and empty titles`() {
        assertEquals(setOf("pack-1"), matchedIds(PackFilter(search = "story")))
    }

    // -------------------------------------------------------------------------
    // Filtre âge : chevauchement de plage, pack sans âge inclus par défaut
    // -------------------------------------------------------------------------

    @Test
    fun `age range includes exact match and overlapping ranges`() {
        assertEquals(setOf("pack-1", "pack-2", "pack-3", "pack-6", "pack-7", "pack-8"), matchedIds(PackFilter(ageMin = 3, ageMax = 6)))
    }

    @Test
    fun `age range includes partially overlapping pack`() {
        val matched = matchedIds(PackFilter(ageMin = 5, ageMax = 10))
        assertTrue("pack-6" in matched, "pack [3,8] doit chevaucher [5,10]")
        assertTrue("pack-3" in matched, "pack [5,10] doit matcher exactement")
    }

    @Test
    fun `age range excludes packs fully outside range`() {
        val matched = matchedIds(PackFilter(ageMin = 5, ageMax = 10))
        assertFalse("pack-4" in matched, "pack [1,2] doit être exclu")
        assertFalse("pack-5" in matched, "pack [11,14] doit être exclu")
    }

    @Test
    fun `age range keeps packs without age by default`() {
        assertEquals(setOf("pack-2", "pack-7", "pack-8"), matchedIds(PackFilter(ageMin = 20, ageMax = 25)))
    }

    @Test
    fun `age min only checks pack age max`() {
        assertEquals(setOf("pack-2", "pack-3", "pack-5", "pack-7", "pack-8"), matchedIds(PackFilter(ageMin = 9)))
    }

    @Test
    fun `age max only checks pack age min`() {
        assertEquals(setOf("pack-1", "pack-2", "pack-4", "pack-6", "pack-7", "pack-8"), matchedIds(PackFilter(ageMax = 4)))
    }

    @Test
    fun `age range both null does not filter`() {
        assertEquals(allIds(), matchedIds(PackFilter(ageMin = null, ageMax = null)))
    }

    // -------------------------------------------------------------------------
    // Combinaisons avec les autres filtres
    // -------------------------------------------------------------------------

    @Test
    fun `search combines with official filter`() {
        assertEquals(setOf("pack-2"), matchedIds(PackFilter(search = "disney", official = true)))
    }

    @Test
    fun `search combines with locale filter`() {
        assertEquals(setOf("pack-1", "pack-3"), matchedIds(PackFilter(search = "disney", locale = "fr_FR")))
    }

    @Test
    fun `search combines with inLibrary filter`() {
        assertEquals(setOf("pack-3"), matchedIds(PackFilter(search = "marvel", inLibrary = true)))
        assertEquals(setOf("pack-4"), matchedIds(PackFilter(search = "marvel", inLibrary = false)))
    }

    @Test
    fun `official filter alone keeps only official packs`() {
        assertEquals(setOf("pack-2", "pack-6"), matchedIds(PackFilter(official = true)))
    }

    @Test
    fun `inLibrary filter alone splits packs by variant presence`() {
        assertEquals(
            specs.filter { it.variantCount > 0 }.map { it.id }.toSet(),
            matchedIds(PackFilter(inLibrary = true)),
        )
        assertEquals(setOf("pack-4"), matchedIds(PackFilter(inLibrary = false)))
    }

    @Test
    fun `age combines with search filter`() {
        assertEquals(setOf("pack-3"), matchedIds(PackFilter(search = "marvel", ageMin = 3, ageMax = 6)))
    }

    @Test
    fun `age combines with official filter`() {
        assertEquals(setOf("pack-2"), matchedIds(PackFilter(official = true, ageMin = 11, ageMax = 14)))
    }

    @Test
    fun `age combines with locale filter`() {
        assertEquals(setOf("pack-5", "pack-7", "pack-8"), matchedIds(PackFilter(locale = "fr_FR", ageMin = 11, ageMax = 14)))
    }

    @Test
    fun `age combines with inLibrary filter`() {
        assertEquals(setOf("pack-2", "pack-5", "pack-7", "pack-8"), matchedIds(PackFilter(inLibrary = true, ageMin = 11, ageMax = 14)))
    }

    @Test
    fun `age combines with search official and inLibrary filters`() {
        assertEquals(
            setOf("pack-2"),
            matchedIds(PackFilter(search = "disney", official = true, inLibrary = true, ageMin = 3, ageMax = 6)),
        )
    }
}
