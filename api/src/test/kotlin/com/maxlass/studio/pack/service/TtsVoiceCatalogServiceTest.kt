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
            response.voices.size shouldBe 18
        } finally {
            unmockkStatic(ElevenLabsVoicesApi::class)
        }
    }
})