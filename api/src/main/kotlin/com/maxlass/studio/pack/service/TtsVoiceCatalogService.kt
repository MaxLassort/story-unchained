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
            voices
                .sortedBy { it.name() }
                .map { TtsVoiceDto(id = it.voiceId(), name = it.name()) }
                .let { it to false }
        } catch (e: Exception) {
            logger.warn("Could not fetch ElevenLabs voices ({}), using built-in default voices", e.message)
            defaultElevenLabsVoices() to true
        }
    }

    /** Well-known ElevenLabs premade voice ids (public, stable). Used when the live list is unavailable. */
    private fun defaultElevenLabsVoices(): List<TtsVoiceDto> = listOf(
        "21m00Tcm4TlvDq8ikWAM" to "Rachel",
        "pNInz6obpgDQGcFmaJgB" to "Adam",
        "ErXwobaYiN019PkySvjV" to "Antoni",
        "EXAVITQu4vr4xnSDxMaL" to "Bella",
        "TxGEqnHWrfWFTfGW9XjX" to "Josh",
        "VR6AewLTigWG4xSOukaG" to "Arnold",
        "Aza3l4woJwNuFm0Tzc1K" to "Domi",
        "MF3mGyEYCl7XYWbV9V6O" to "Elli",
        "N2lVS1w4EtoT3dr4eOWO" to "Callum",
        "IKne3meq5aSn9XLyUdCD" to "Charlie",
        "JBFqnCBsd6RMkjVDRZzb" to "George",
        "LcfcDiN3B44gKlibh9v7" to "Emily",
        "oWAxZDx7w5VEj9dCyTzz" to "Grace",
        "SOYHLrjzK2X1ezoPC6cr" to "Harry",
        "ZQe5CZNOzWyzPSCn5a3Z" to "James",
        "t0jbNlBVZ17f02VDIeMI" to "Jessie",
        "TX3LPaxmHKxFdv7VOQHJ" to "Liam",
        "XrExE9yKIg1Wjnnl2kTx" to "Matilda",
    ).map { TtsVoiceDto(id = it.first, name = it.second) }
}