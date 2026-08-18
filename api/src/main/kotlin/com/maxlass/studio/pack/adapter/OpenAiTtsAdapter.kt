package com.maxlass.studio.pack.adapter

import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientImpl
import com.openai.core.ClientOptions
import com.maxlass.studio.pack.port.external.TextToSpeechPort
import org.springframework.ai.audio.tts.TextToSpeechModel
import org.springframework.ai.audio.tts.TextToSpeechPrompt
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioSpeechOptions
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenAI TTS adapter (BYOK): builds a Spring AI [TextToSpeechModel] at runtime with the
 * user-provided API key, so no OpenAI credentials are needed in application.yml.
 */
class OpenAiTtsAdapter(
    private val apiKey: String,
    private val voice: String?,
    private val modelFactory: (apiKey: String, voice: String?) -> TextToSpeechModel = ::defaultModel,
) : TextToSpeechPort {

    override suspend fun synthesize(text: String, voice: String?, lang: String?): ByteArray = withContext(Dispatchers.IO) {
        val effectiveVoice = voice ?: this@OpenAiTtsAdapter.voice
        val model = modelFactory(apiKey, effectiveVoice)
        logger.info("OpenAI TTS: synthesizing {} chars with voice {}", text.length, effectiveVoice ?: "default")
        model.call(TextToSpeechPrompt(text)).result.output
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OpenAiTtsAdapter::class.java)

        /** Default model factory: gpt-4o-mini-tts, MP3 output, voice from options when known. */
        private fun defaultModel(apiKey: String, voice: String?): TextToSpeechModel {
            val client: OpenAIClient = OpenAIClientImpl(
                ClientOptions.builder()
                    .apiKey(apiKey)
                    .build(),
            )
            val options = OpenAiAudioSpeechOptions.builder()
                .responseFormat(OpenAiAudioSpeechOptions.AudioResponseFormat.MP3)
                .speed(OpenAiAudioSpeechOptions.DEFAULT_SPEED)
                .apply {
                    model(OpenAiAudioSpeechOptions.DEFAULT_SPEECH_MODEL)
                    voice?.let { v ->
                        try {
                            voice(OpenAiAudioSpeechOptions.Voice.valueOf(v.uppercase()))
                        } catch (_: IllegalArgumentException) {
                            // Unknown voice name: keep provider default
                        }
                    }
                }
                .build()
            return OpenAiAudioSpeechModel.builder()
                .openAiClient(client)
                .options(options)
                .build()
        }
    }
}