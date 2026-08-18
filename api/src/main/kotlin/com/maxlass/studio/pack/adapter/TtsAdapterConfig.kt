package com.maxlass.studio.pack.adapter

import com.maxlass.studio.pack.port.external.KeyedTtsAdapterFactory
import com.maxlass.studio.pack.port.external.TextToSpeechPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires the BYOK TTS adapter factories and the free fallback adapter. */
@Configuration
class TtsAdapterConfig {

    @Bean
    fun openAiTtsAdapterFactory(): KeyedTtsAdapterFactory =
        KeyedTtsAdapterFactory { apiKey, voice -> OpenAiTtsAdapter(apiKey, voice) }

    @Bean
    fun elevenLabsTtsAdapterFactory(): KeyedTtsAdapterFactory =
        KeyedTtsAdapterFactory { apiKey, voice -> ElevenLabsTtsAdapter(apiKey, voice) }

    @Bean
    fun freeTtsAdapter(): TextToSpeechPort = GoogleTranslateTtsAdapter()
}