package com.maxlass.studio.pack.adapter

import com.maxlass.studio.pack.port.external.TextToSpeechPort
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.audio.tts.TextToSpeechModel
import org.springframework.ai.audio.tts.TextToSpeechPrompt
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechModel
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechOptions
import org.springframework.ai.elevenlabs.api.ElevenLabsApi

/**
 * ElevenLabs TTS adapter (BYOK): builds a Spring AI [TextToSpeechModel] at runtime with the
 * user-provided API key. Output is forced to MP3 44.1 kHz 128 kbps (mono).
 */
class ElevenLabsTtsAdapter(
    private val apiKey: String,
    private val voice: String?,
    private val modelFactory: (apiKey: String, voice: String?) -> TextToSpeechModel = ::defaultModel,
) : TextToSpeechPort {

    override suspend fun synthesize(text: String, voice: String?, lang: String?): ByteArray = withContext(Dispatchers.IO) {
        val effectiveVoice = voice ?: this@ElevenLabsTtsAdapter.voice
        val model = modelFactory(apiKey, effectiveVoice)
        logger.info("ElevenLabs TTS: synthesizing {} chars with voice {}", text.length, effectiveVoice ?: "default")
        model.call(TextToSpeechPrompt(text)).result.output
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ElevenLabsTtsAdapter::class.java)

        const val DEFAULT_MODEL = "eleven_turbo_v2_5"
        const val DEFAULT_OUTPUT_FORMAT = "mp3_44100_128"

        /** Default model factory: eleven_turbo_v2_5, MP3 44.1 kHz 128 kbps, voice id from options when known. */
        private fun defaultModel(apiKey: String, voice: String?): TextToSpeechModel {
            val api = ElevenLabsApi.builder()
                .apiKey(apiKey)
                .build()
            val optionsBuilder = ElevenLabsTextToSpeechOptions.builder()
                .model(DEFAULT_MODEL)
                .outputFormat(DEFAULT_OUTPUT_FORMAT)
            voice?.let { v ->
                optionsBuilder.voiceId(v)
            }
            return ElevenLabsTextToSpeechModel(api, optionsBuilder.build())
        }
    }
}