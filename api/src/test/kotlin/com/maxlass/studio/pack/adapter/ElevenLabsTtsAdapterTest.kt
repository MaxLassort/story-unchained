package com.maxlass.studio.pack.adapter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.springframework.ai.audio.tts.Speech
import org.springframework.ai.audio.tts.TextToSpeechPrompt
import org.springframework.ai.audio.tts.TextToSpeechModel
import org.springframework.ai.audio.tts.TextToSpeechResponse
import org.springframework.ai.elevenlabs.api.ElevenLabsVoicesApi
import org.springframework.http.ResponseEntity

class ElevenLabsTtsAdapterTest : StringSpec({

    beforeTest { clearAllMocks() }

    val model = mockk<TextToSpeechModel>()
    val voicesApi = mockk<ElevenLabsVoicesApi>()
    var factoryKey: String? = null
    var factoryVoice: String? = null
    var factoryLang: String? = null

    fun adapter(apiKey: String = "el-key", voice: String? = null) = ElevenLabsTtsAdapter(
        apiKey = apiKey,
        voice = voice,
        modelFactory = { key, v, lang ->
            factoryKey = key
            factoryVoice = v
            factoryLang = lang
            model
        },
        voicesApiFactory = { voicesApi },
    )

    "calls the model with the text and returns the audio bytes" {
        every { model.call(any<TextToSpeechPrompt>()) } returns TextToSpeechResponse(listOf(Speech(byteArrayOf(4, 5))))

        val result = runBlocking { adapter().synthesize("Bonjour") }

        result.toList() shouldBe listOf(4, 5)
        factoryKey shouldBe "el-key"
        verify { model.call(match<org.springframework.ai.audio.tts.TextToSpeechPrompt> { it.instructions.text == "Bonjour" }) }
    }

    "uses the configured voice id when no per-call voice is given" {
        every { model.call(any<TextToSpeechPrompt>()) } returns TextToSpeechResponse(listOf(Speech(byteArrayOf(9))))

        runBlocking { adapter(voice = "21m00Tcm4TlvDq8ikWAM").synthesize("Bonjour") }

        factoryVoice shouldBe "21m00Tcm4TlvDq8ikWAM"
    }

    "overrides the configured voice with the per-call voice" {
        every { model.call(any<TextToSpeechPrompt>()) } returns TextToSpeechResponse(listOf(Speech(byteArrayOf(9))))

        runBlocking { adapter(voice = "21m00Tcm4TlvDq8ikWAM").synthesize("Bonjour", voice = "EXAVITQu4vr4xnSDxMaL") }

        factoryVoice shouldBe "EXAVITQu4vr4xnSDxMaL"
    }

    "does not call the voices API when the voice is already an id" {
        every { model.call(any<TextToSpeechPrompt>()) } returns TextToSpeechResponse(listOf(Speech(byteArrayOf(1))))

        runBlocking { adapter().synthesize("Bonjour", voice = "21m00Tcm4TlvDq8ikWAM") }

        factoryVoice shouldBe "21m00Tcm4TlvDq8ikWAM"
        verify(exactly = 0) { voicesApi.getVoices() }
    }

    "resolves a premade voice name statically without calling voices API" {
        every { model.call(any<TextToSpeechPrompt>()) } returns TextToSpeechResponse(listOf(Speech(byteArrayOf(1))))

        runBlocking { adapter().synthesize("Bonjour", voice = "Rachel") }

        factoryVoice shouldBe "21m00Tcm4TlvDq8ikWAM"
        verify(exactly = 0) { voicesApi.getVoices() }
    }

    "resolves a custom cloned voice display name via the voices API" {
        val custom = mockk<ElevenLabsVoicesApi.Voice> {
            every { name() } returns "MyCustomVoice"
            every { voiceId() } returns "customVoiceId1234567"
        }
        val body = mockk<ElevenLabsVoicesApi.Voices> {
            every { voices() } returns listOf(custom)
        }
        every { voicesApi.getVoices() } returns ResponseEntity.ok(body)
        every { model.call(any<TextToSpeechPrompt>()) } returns TextToSpeechResponse(listOf(Speech(byteArrayOf(1))))

        runBlocking { adapter().synthesize("Bonjour", voice = "MyCustomVoice") }

        factoryVoice shouldBe "customVoiceId1234567"
        verify(exactly = 1) { voicesApi.getVoices() }
    }

    "passes through a custom voice name that cannot be resolved (provider surfaces the error)" {
        every { voicesApi.getVoices() } throws RuntimeException("voice_not_found")
        every { model.call(any<TextToSpeechPrompt>()) } returns TextToSpeechResponse(listOf(Speech(byteArrayOf(1))))

        runBlocking { adapter().synthesize("Bonjour", voice = "UnknownVoiceName") }

        factoryVoice shouldBe "UnknownVoiceName"
    }

    "passes the language code to the model factory" {
        every { model.call(any<TextToSpeechPrompt>()) } returns TextToSpeechResponse(listOf(Speech(byteArrayOf(1))))

        runBlocking { adapter().synthesize("Bonjour", lang = "fr") }

        factoryLang shouldBe "fr"
    }
})