package com.maxlass.studio.infrastructure.metadata

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Functional test for [MetadataStore] against a real temporary filesystem.
 *
 * Seuls les scénarios sur le catalogue OFFICIEL (official.json) sont couverts pour l'instant.
 * La partie non officielle (unofficial.json) sera testée plus tard (comportement à confirmer).
 */
class MetadataStoreFunctionalTest : StringSpec({

    lateinit var tempDir: Path
    lateinit var officialJsonPath: Path
    lateinit var unofficialJsonPath: Path

    beforeTest {
        tempDir = Files.createTempDirectory("metadata-store-test")
        officialJsonPath = tempDir.resolve("official.json")
        unofficialJsonPath = tempDir.resolve("unofficial.json")
    }

    afterTest {
        tempDir.toFile().deleteRecursively()
    }

    fun store() = MetadataStore(
        officialJsonPath = officialJsonPath,
        unofficialJsonPath = unofficialJsonPath,
    )

    fun writeOfficialDatabase(content: String) {
        Files.writeString(officialJsonPath, content)
    }

    "isOfficialPack and getOfficialMetadata read the catalog (response wrapper unwrapped, fr_FR preferred)" {
        writeOfficialDatabase(
            """
            {
              "response": {
                "entry": {
                  "uuid": "official-1",
                  "locales_available": { "en_EN": {}, "fr_FR": {} },
                  "localized_infos": {
                    "en_EN": { "title": "English", "description": "d", "image": { "image_url": "/en.png" } },
                    "fr_FR": { "title": "Français", "description": "d", "image": { "image_url": "/fr.png" } }
                  }
                }
              }
            }
            """.trimIndent()
        )
        val store = store()

        store.isOfficialPack("official-1") shouldBe true
        store.isOfficialPack("unknown") shouldBe false

        store.getOfficialMetadata("official-1")!!.let {
            it.title shouldBe "Français"
            it.description shouldBe "d"
            it.thumbnail shouldBe MetadataDb.THUMBNAILS_STORAGE_ROOT + "/fr.png"
            it.official shouldBe true
        }
    }

    "parses a catalog without the response wrapper" {
        writeOfficialDatabase(
            """
            {
              "official-1": {
                "uuid": "official-1",
                "locales_available": { "fr_FR": {} },
                "localized_infos": { "fr_FR": { "title": "Officiel", "description": "d", "image": { "image_url": "/i.png" } } }
              }
            }
            """.trimIndent()
        )
        val store = store()

        store.isOfficialPack("official-1") shouldBe true
        store.getOfficialMetadata("official-1")!!.title shouldBe "Officiel"
    }

    "returns null for a uuid not in the catalog" {
        writeOfficialDatabase(
            """
            {
              "response": {
                "entry": { "uuid": "official-1", "locales_available": { "fr_FR": {} }, "localized_infos": { "fr_FR": { "title": "Officiel" } } }
              }
            }
            """.trimIndent()
        )
        val store = store()

        store.isOfficialPack("not-there") shouldBe false
        store.getOfficialMetadata("not-there") shouldBe null
    }

    "handles a missing official.json (no official packs)" {
        val store = store()

        store.isOfficialPack("official-1") shouldBe false
        store.getOfficialMetadata("official-1") shouldBe null
    }

    "returns null for an entry without the uuid field" {
        writeOfficialDatabase(
            """
            {
              "response": {
                "broken": { "locales_available": { "fr_FR": {} }, "localized_infos": { "fr_FR": { "title": "Sans uuid" } } }
              }
            }
            """.trimIndent()
        )
        val store = store()

        store.isOfficialPack("broken") shouldBe false
        store.getOfficialMetadata("broken") shouldBe null
    }
})
