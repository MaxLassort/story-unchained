package com.maxlass.studio.settings.data

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.settings.domain.Settings
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class SettingsRepositoryImplTest : StringSpec({

    lateinit var tempDir: Path

    beforeTest {
        tempDir = Files.createTempDirectory("settings-repository-test")
    }

    afterTest {
        tempDir.toFile().deleteRecursively()
    }

    fun repository() = SettingsRepositoryImpl(
        studioProperties = StudioProperties(storageDir = tempDir),
    )

    "getSettings creates the default settings file on first access" {
        val repo = repository()

        val settings = repo.getSettings()

        settings.libraryPath shouldBe StudioProperties().defaultLibraryPath.toFile().absolutePath
        settings.unofficialDbPath shouldBe null
        settings.targetDeviceType shouldBe null
        settings.ttsProvider shouldBe null
        settings.ttsOpenAiApiKey shouldBe null
        settings.ttsElevenLabsApiKey shouldBe null
        settings.ttsVoice shouldBe null
        tempDir.resolve("settings.json").toFile().exists() shouldBe true
    }

    "saveSettings round-trips the TTS fields" {
        val repo = repository()

        repo.saveSettings(
            Settings(
                libraryPath = "/packs",
                unofficialDbPath = "~/.studio/db/unofficial.json",
                targetDeviceType = "FS",
                ttsProvider = "OPENAI",
                ttsOpenAiApiKey = "sk-test-123",
                ttsElevenLabsApiKey = "el-key-456",
                ttsVoice = "alloy",
            )
        )

        val reloaded = SettingsRepositoryImpl(StudioProperties(storageDir = tempDir)).getSettings()
        reloaded.ttsProvider shouldBe "OPENAI"
        reloaded.ttsOpenAiApiKey shouldBe "sk-test-123"
        reloaded.ttsElevenLabsApiKey shouldBe "el-key-456"
        reloaded.ttsVoice shouldBe "alloy"
    }

    "getSettings ignores unknown keys in the file (forward compatibility)" {
        val repo = repository()
        val file = tempDir.resolve("settings.json")
        file.toFile().writeText(
            """
            {
              "libraryPath": "/packs",
              "ttsProvider": "ELEVENLABS",
              "ttsElevenLabsApiKey": "el-key",
              "futureField": "ignored"
            }
            """.trimIndent()
        )

        val settings = repo.getSettings()

        settings.libraryPath shouldBe "/packs"
        settings.ttsProvider shouldBe "ELEVENLABS"
        settings.ttsElevenLabsApiKey shouldBe "el-key"
        settings.ttsVoice shouldBe null
    }
})