package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.dto.TtsVoiceDto
import com.maxlass.studio.settings.domain.Settings
import com.maxlass.studio.settings.service.SettingsService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.springframework.ai.elevenlabs.api.ElevenLabsVoicesApi
import org.springframework.web.server.ResponseStatusException

class TtsVoiceCatalogServiceTest : StringSpec({

    beforeTest { clearAllMocks() }

    val settingsService = mockk<SettingsService>()

    fun service() = TtsVoiceCatalogService(settingsService)

    fun settings(elevenLabsApiKey: String? = null) = Settings(
        libraryPath = "/tmp/library",
        ttsElevenLabsApiKey = elevenLabsApiKey,
    )

    "returns the static OpenAI voice list" {
        val response = runBlocking { service().getVoices("OPENAI") }

        response.provider shouldBe "OPENAI"
        response.voices shouldContain TtsVoiceDto(id = "alloy", name = "Alloy")
        response.voices shouldContain TtsVoiceDto(id = "ash", name = "Ash")
        response.voices.size shouldBe 11
    }

    "returns an empty list for the FREE provider" {
        val response = runBlocking { service().getVoices("FREE") }

        response.provider shouldBe "FREE"
        response.voices shouldBe emptyList()
    }

    "returns an empty list when no provider is given" {
        val response = runBlocking { service().getVoices(null) }

        response.provider shouldBe "FREE"
        response.voices shouldBe emptyList()
    }

    "rejects ElevenLabs voices without a configured API key" {
        coEvery { settingsService.getSettings() } returns settings()

        shouldThrow<ResponseStatusException> {
            runBlocking { service().getVoices("ELEVENLABS") }
        }
    }

    "falls back to the built-in default voices when the live API call fails" {
        coEvery { settingsService.getSettings() } returns settings(elevenLabsApiKey = "el-key")
        val api = mockk<ElevenLabsVoicesApi>()
        val builder = mockk<ElevenLabsVoicesApi.Builder>()
        mockkStatic(ElevenLabsVoicesApi::class)
        every { ElevenLabsVoicesApi.builder() } returns builder
        every { builder.apiKey("el-key") } returns builder
        every { builder.build() } returns api
        every { api.getVoices() } throws RuntimeException("401 - voices_read missing")

        try {
            val response = runBlocking { service().getVoices("ELEVENLABS") }

            response.provider shouldBe "ELEVENLABS"
            response.fallback shouldBe true
            response.voices shouldContain TtsVoiceDto(id = "21m00Tcm4TlvDq8ikWAM", name = "Rachel")
            response.voices.size shouldBe 21
        } finally {
            unmockkStatic(ElevenLabsVoicesApi::class)
        }
    }

    "returns all live voices including custom ones with language labels" {
        coEvery { settingsService.getSettings() } returns settings(elevenLabsApiKey = "el-key")
        val premade = mockk<ElevenLabsVoicesApi.Voice> {
            every { voiceId() } returns "21m00Tcm4TlvDq8ikWAM"
            every { name() } returns "Rachel"
            every { labels() } returns mapOf("language" to "en")
            every { verifiedLanguages() } returns emptyList()
            every { fineTuning() } returns mockk { every { language() } returns "" }
        }
        val custom = mockk<ElevenLabsVoicesApi.Voice> {
            every { voiceId() } returns "customVictoriaId12345"
            every { name() } returns "Victoria"
            every { labels() } returns mapOf("language" to "fr")
            every { verifiedLanguages() } returns emptyList()
            every { fineTuning() } returns mockk { every { language() } returns "" }
        }
        val professional = mockk<ElevenLabsVoicesApi.Voice> {
            every { voiceId() } returns "proVoiceIdAAAAAAAAAA"
            every { name() } returns "Paul K — Deep French Narrator"
            every { labels() } returns emptyMap()
            every { verifiedLanguages() } returns listOf(
                mockk { every { language() } returns "fr" },
            )
            every { fineTuning() } returns mockk { every { language() } returns "" }
        }
        val body = mockk<ElevenLabsVoicesApi.Voices> {
            every { voices() } returns listOf(premade, custom, professional)
        }
        val api = mockk<ElevenLabsVoicesApi>()
        val builder = mockk<ElevenLabsVoicesApi.Builder>()
        mockkStatic(ElevenLabsVoicesApi::class)
        every { ElevenLabsVoicesApi.builder() } returns builder
        every { builder.apiKey("el-key") } returns builder
        every { builder.build() } returns api
        every { api.getVoices() } returns org.springframework.http.ResponseEntity.ok(body)

        try {
            val response = runBlocking { service().getVoices("ELEVENLABS") }

            response.fallback shouldBe false
            response.voices.size shouldBe 3
            response.voices shouldContain TtsVoiceDto(id = "customVictoriaId12345", name = "Victoria", language = "fr")
            response.voices shouldContain TtsVoiceDto(id = "21m00Tcm4TlvDq8ikWAM", name = "Rachel", language = "en")
            response.voices shouldContain TtsVoiceDto(
                id = "proVoiceIdAAAAAAAAAA",
                name = "Paul K — Deep French Narrator",
                language = "fr",
            )
        } finally {
            unmockkStatic(ElevenLabsVoicesApi::class)
        }
    }
})