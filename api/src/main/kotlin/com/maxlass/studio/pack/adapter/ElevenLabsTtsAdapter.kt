package com.maxlass.studio.pack.adapter

import com.maxlass.studio.pack.port.external.TextToSpeechPort
import com.maxlass.studio.pack.service.TtsVoiceCatalogService
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.audio.tts.TextToSpeechModel
import org.springframework.ai.audio.tts.TextToSpeechPrompt
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechModel
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechOptions
import org.springframework.ai.elevenlabs.api.ElevenLabsApi
import org.springframework.ai.elevenlabs.api.ElevenLabsVoicesApi

/**
 * ElevenLabs TTS adapter (BYOK): builds a Spring AI [TextToSpeechModel] at runtime with the
 * user-provided API key. Output is forced to MP3 44.1 kHz 128 kbps (mono).
 */
class ElevenLabsTtsAdapter(
    private val apiKey: String,
    private val voice: String?,
    private val modelFactory: (apiKey: String, voice: String?, lang: String?) -> TextToSpeechModel = ::defaultModel,
    private val voicesApiFactory: (apiKey: String) -> ElevenLabsVoicesApi = ::defaultVoicesApi,
) : TextToSpeechPort {

    override suspend fun synthesize(text: String, voice: String?, lang: String?): ByteArray = withContext(Dispatchers.IO) {
        val effectiveVoice = resolveVoiceId(voice ?: this@ElevenLabsTtsAdapter.voice)
        val model = modelFactory(apiKey, effectiveVoice, lang)
        logger.info(
            "ElevenLabs TTS: synthesizing {} chars with voice {} (lang: {})",
            text.length,
            effectiveVoice ?: DEFAULT_VOICE_ID,
            lang ?: "default",
        )
        model.call(TextToSpeechPrompt(text)).result.output
    }

    /**
     * ElevenLabs voice ids are opaque 20-char strings; user settings may still hold a display
     * name (ex. "Rachel") saved by an older version of the settings dialog. Resolve names to
     * their id via the static catalog or the voices API so a name never reaches the TTS endpoint;
     * unknown values are passed through unchanged so the provider's own "voice_not_found" error
     * stays accurate.
     */
    private fun resolveVoiceId(voice: String?): String? {
        if (voice == null || VOICE_ID_FORMAT.matches(voice)) return voice
        val trimmed = voice.trim()
        val staticMatch = STATIC_VOICES_BY_NAME[trimmed.lowercase()]
        if (staticMatch != null) return staticMatch
        val resolved = runCatching {
            voicesApiFactory(apiKey)
                .getVoices()
                .body?.voices()
                ?.firstOrNull { it.name().equals(trimmed, ignoreCase = true) }
                ?.voiceId()
        }.getOrNull()
        if (resolved == null) {
            logger.warn(
                "ElevenLabs voice '{}' is not a voice id and could not be resolved by name; using it as-is",
                voice,
            )
        }
        return resolved ?: voice
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ElevenLabsTtsAdapter::class.java)

        /** Premade ElevenLabs voice ids are opaque 20-char base62 strings (ex. "21m00Tcm4TlvDq8ikWAM"). */
        private val VOICE_ID_FORMAT = Regex("[A-Za-z0-9]{20}")

        private val STATIC_VOICES_BY_NAME = TtsVoiceCatalogService.DEFAULT_ELEVENLABS_VOICES
            .associate { it.name.lowercase() to it.id }
            .plus("bella" to "EXAVITQu4vr4xnSDxMaL")

        /** Default voices API factory used to resolve legacy display names to voice ids. */
        private fun defaultVoicesApi(apiKey: String): ElevenLabsVoicesApi =
            ElevenLabsVoicesApi.builder()
                .apiKey(apiKey)
                .build()

        const val DEFAULT_MODEL = "eleven_turbo_v2_5"
        const val DEFAULT_OUTPUT_FORMAT = "mp3_44100_128"
        const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Rachel

        /** Default model factory: eleven_turbo_v2_5, MP3 44.1 kHz 128 kbps, voice id and language code when provided. */
        private fun defaultModel(apiKey: String, voice: String?, lang: String?): TextToSpeechModel {
            val api = ElevenLabsApi.builder()
                .apiKey(apiKey)
                .build()
            val effectiveVoiceId = voice?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE_ID
            val optionsBuilder = ElevenLabsTextToSpeechOptions.builder()
                .model(DEFAULT_MODEL)
                .outputFormat(DEFAULT_OUTPUT_FORMAT)
                .voiceId(effectiveVoiceId)
            val isoLang = lang?.takeIf { it.isNotBlank() }
                ?.trim()
                ?.substringBefore('-')
                ?.substringBefore('_')
                ?.lowercase()
            if (isoLang != null) {
                optionsBuilder.languageCode(isoLang)
            }
            return ElevenLabsTextToSpeechModel(api, optionsBuilder.build())
        }
    }
}