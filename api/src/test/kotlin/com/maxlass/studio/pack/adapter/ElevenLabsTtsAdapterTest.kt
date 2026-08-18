package com.maxlass.studio.pack.adapter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.springframework.ai.audio.tts.Speech
import org.springframework.ai.audio.tts.TextToSpeechModel
import org.springframework.ai.audio.tts.TextToSpeechResponse

class ElevenLabsTtsAdapterTest : StringSpec({

    beforeTest { clearAllMocks() }

    val model = mockk<TextToSpeechModel>()
    var factoryKey: String? = null
    var factoryVoice: String? = null

    fun adapter(apiKey: String = "el-key", voice: String? = null) = ElevenLabsTtsAdapter(
        apiKey = apiKey,
        voice = voice,
        modelFactory = { key, v ->
            factoryKey = key
            factoryVoice = v
            model
        },
    )

    "calls the model with the text and returns the audio bytes" {
        every { model.call(any<org.springframework.ai.audio.tts.TextToSpeechPrompt>()) } returns TextToSpeechResponse(listOf(Speech(byteArrayOf(4, 5))))

        val result = runBlocking { adapter().synthesize("Bonjour") }

        result.toList() shouldBe listOf(4, 5)
        factoryKey shouldBe "el-key"
        verify { model.call(match<org.springframework.ai.audio.tts.TextToSpeechPrompt> { it.instructions.text == "Bonjour" }) }
    }

    "uses the configured voice id when no per-call voice is given" {
        every { model.call(any<org.springframework.ai.audio.tts.TextToSpeechPrompt>()) } returns TextToSpeechResponse(listOf(Speech(byteArrayOf(9))))

        runBlocking { adapter(voice = "21m00Tcm4TlvDq8ikWAM").synthesize("Bonjour") }

        factoryVoice shouldBe "21m00Tcm4TlvDq8ikWAM"
    }

    "overrides the configured voice with the per-call voice" {
        every { model.call(any<org.springframework.ai.audio.tts.TextToSpeechPrompt>()) } returns TextToSpeechResponse(listOf(Speech(byteArrayOf(9))))

        runBlocking { adapter(voice = "21m00Tcm4TlvDq8ikWAM").synthesize("Bonjour", voice = "EXAVITQu4vr4xnSDxMaL") }

        factoryVoice shouldBe "EXAVITQu4vr4xnSDxMaL"
    }
})