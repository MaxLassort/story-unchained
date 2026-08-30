package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.dto.TtsVoiceDto
import com.maxlass.studio.pack.domain.dto.TtsVoicesResponse
import com.maxlass.studio.pack.port.external.TtsProvider
import com.maxlass.studio.settings.service.SettingsService
import org.slf4j.LoggerFactory
import org.springframework.ai.elevenlabs.api.ElevenLabsVoicesApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Lists the available voices for a TTS provider. OpenAI voices are static; ElevenLabs voices
 * are fetched live with the user's API key (BYOK), falling back to the built-in default voice
 * list when the key lacks the `voices_read` permission or the API call fails.
 */
@Service
class TtsVoiceCatalogService(
    private val settingsService: SettingsService,
) {

    companion object {
        private val logger = LoggerFactory.getLogger(TtsVoiceCatalogService::class.java)

        /**
         * Well-known ElevenLabs premade voice ids (public, stable, accessible on all subscription tiers
         * including Free). Used when live fetching fails (e.g. API key without `voices_read` permission).
         */
        val DEFAULT_ELEVENLABS_VOICES: List<TtsVoiceDto> = listOf(
            "Xb7hH8MSUJpSbSDYk0k2" to "Alice",
            "9BWtsMINqrJLrRacOk9x" to "Aria",
            "pqHfZKP75CvOlQylNhV4" to "Bill",
            "nPczCjzI2devNBz1zQrb" to "Brian",
            "N2lVS1w4EtoT3dr4eOWO" to "Callum",
            "IKne3meq5aSn9XLyUdCD" to "Charlie",
            "XB0fDUnXU5powFXDhCwa" to "Charlotte",
            "iP95p4xoKVk53GoZ742B" to "Chris",
            "onwK4e9ZLuTAKqWW03F9" to "Daniel",
            "cjVigY5qzO86Huf0OWal" to "Eric",
            "JBFqnCBsd6RMkjVDRZzb" to "George",
            "cgSgspJ2msm6clMCkdW9" to "Jessica",
            "FGY2WhTYpPnrIDTdsKH5" to "Laura",
            "TX3LPaxmHKxFdv7VOQHJ" to "Liam",
            "pFZP5JQG7iQjIQuC4Bku" to "Lily",
            "XrExE9yKIg1Wjnnl2kTx" to "Matilda",
            "21m00Tcm4TlvDq8ikWAM" to "Rachel",
            "SAz9YHcvj6GT2YYXdXww" to "River",
            "CwhRBWXzGAHq8TQ4Fs17" to "Roger",
            "EXAVITQu4vr4xnSDxMaL" to "Sarah",
            "bIHbv24MWmeRgasZH58o" to "Will",
        ).map { TtsVoiceDto(id = it.first, name = it.second) }
    }

    suspend fun getVoices(provider: String?): TtsVoicesResponse {
        val p = TtsProvider.fromSettings(provider)
        return when (p) {
            TtsProvider.OPENAI -> TtsVoicesResponse("OPENAI", openAiVoices())
            TtsProvider.ELEVENLABS -> {
                val (voices, fallback) = elevenLabsVoices()
                TtsVoicesResponse("ELEVENLABS", voices, fallback)
            }
            else -> TtsVoicesResponse("FREE", emptyList())
        }
    }

    private fun openAiVoices(): List<TtsVoiceDto> = listOf(
        "alloy", "echo", "fable", "onyx", "nova", "shimmer", "ballad", "sage", "coral", "verse", "ash",
    ).map { TtsVoiceDto(id = it, name = it.replaceFirstChar(Char::uppercase)) }

    private suspend fun elevenLabsVoices(): Pair<List<TtsVoiceDto>, Boolean> {
        val apiKey = settingsService.getSettings().ttsElevenLabsApiKey?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ElevenLabs API key is not configured")
        return try {
            val api = ElevenLabsVoicesApi.builder()
                .apiKey(apiKey)
                .build()
            val voices = api.getVoices().body?.voices().orEmpty()
            val filtered = voices.filter { voice ->
                // Free tier accounts cannot use community library voices via API (HTTP 402 paid_plan_required).
                // Keep PREMADE, CLONED, GENERATED.
                voice.category() in setOf(
                    ElevenLabsVoicesApi.CategoryEnum.PREMADE,
                    ElevenLabsVoicesApi.CategoryEnum.CLONED,
                    ElevenLabsVoicesApi.CategoryEnum.GENERATED,
                )
            }
            if (filtered.isEmpty()) {
                defaultElevenLabsVoices() to true
            } else {
                filtered
                    .sortedBy { it.name() }
                    .map { TtsVoiceDto(id = it.voiceId(), name = it.name()) }
                    .let { it to false }
            }
        } catch (e: Exception) {
            logger.warn("Could not fetch ElevenLabs voices ({}), using built-in default voices", e.message)
            defaultElevenLabsVoices() to true
        }
    }

    /** Well-known ElevenLabs premade voice ids (public, stable). Used when the live list is unavailable. */
    private fun defaultElevenLabsVoices(): List<TtsVoiceDto> = DEFAULT_ELEVENLABS_VOICES
}