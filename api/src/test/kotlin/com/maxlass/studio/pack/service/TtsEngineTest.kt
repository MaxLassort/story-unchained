package com.maxlass.studio.pack.service


import com.maxlass.studio.pack.format.utils.AudioConversion
import com.maxlass.studio.pack.port.external.KeyedTtsAdapterFactory
import com.maxlass.studio.pack.port.external.TextToSpeechPort
import com.maxlass.studio.settings.domain.Settings
import com.maxlass.studio.settings.service.SettingsService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking

class TtsEngineTest : StringSpec({

    val settingsService = mockk<SettingsService>()
    val openAiFactory = mockk<KeyedTtsAdapterFactory>()
    val elevenLabsFactory = mockk<KeyedTtsAdapterFactory>()
    val freeAdapter = mockk<TextToSpeechPort>()
    val openAiAdapter = mockk<TextToSpeechPort>()
    val elevenLabsAdapter = mockk<TextToSpeechPort>()

    fun engine() = TtsEngine(
        settingsService = settingsService,
        openAiTtsAdapterFactory = openAiFactory,
        elevenLabsTtsAdapterFactory = elevenLabsFactory,
        freeTtsAdapter = freeAdapter,
    )

    fun settings(
        provider: String? = null,
        openAiApiKey: String? = null,
        elevenLabsApiKey: String? = null,
        voice: String? = null,
        ttsLang: String? = null,
    ) = Settings(
        libraryPath = "/tmp/library",
        ttsProvider = provider,
        ttsOpenAiApiKey = openAiApiKey,
        ttsElevenLabsApiKey = elevenLabsApiKey,
        ttsVoice = voice,
        ttsLang = ttsLang,
    )

    beforeTest {
        clearAllMocks()
        mockkObject(AudioConversion)
        every { AudioConversion.anyToMp3(any()) } answers { firstArg() }
    }

    afterTest { unmockkObject(AudioConversion) }

    "uses the free Google Translate adapter when no provider is configured" {
        coEvery { settingsService.getSettings() } returns settings()
        coEvery { freeAdapter.synthesize("texte", null, any()) } returns byteArrayOf(1, 2, 3)

        val result = engine().synthesize("texte")

        result.toList() shouldBe listOf(1, 2, 3)
        coVerify(exactly = 1) { freeAdapter.synthesize("texte", null, any()) }
        coVerify(exactly = 0) { openAiFactory.create(any(), any()) }
        coVerify(exactly = 0) { elevenLabsFactory.create(any(), any()) }
    }

    "uses the free adapter when the provider has no API key" {
        coEvery { settingsService.getSettings() } returns settings(provider = "OPENAI")
        coEvery { freeAdapter.synthesize("texte", null, any()) } returns byteArrayOf(1, 2, 3)

        val result = engine().synthesize("texte")

        result.toList() shouldBe listOf(1, 2, 3)
        coVerify(exactly = 0) { openAiFactory.create(any(), any()) }
    }

    "uses the OpenAI adapter with the configured key and voice" {
        coEvery { settingsService.getSettings() } returns settings(provider = "OPENAI", openAiApiKey = "sk-test", voice = "nova")
        every { openAiFactory.create("sk-test", "nova") } returns openAiAdapter
        coEvery { openAiAdapter.synthesize("texte", null, any()) } returns byteArrayOf(1, 2, 3)

        val result = runBlocking { engine().synthesize("texte") }

        result.toList() shouldBe listOf(1, 2, 3)
        coVerify(exactly = 0) { freeAdapter.synthesize(any(), any(), any()) }
    }

    "uses the ElevenLabs adapter with its own key, not the OpenAI one" {
        coEvery { settingsService.getSettings() } returns settings(
            provider = "ELEVENLABS",
            openAiApiKey = "sk-test",
            elevenLabsApiKey = "el-key",
        )
        every { elevenLabsFactory.create("el-key", null) } returns elevenLabsAdapter
        coEvery { elevenLabsAdapter.synthesize("texte", null, any()) } returns byteArrayOf(1, 2, 3)

        runBlocking { engine().synthesize("texte") }

        coVerify(exactly = 1) { elevenLabsFactory.create("el-key", null) }
        coVerify(exactly = 0) { openAiFactory.create(any(), any()) }
    }

    "prefers the requested voice over the configured one" {
        coEvery { settingsService.getSettings() } returns settings(provider = "ELEVENLABS", elevenLabsApiKey = "key", voice = "configured")
        every { elevenLabsFactory.create("key", "requested") } returns elevenLabsAdapter
        coEvery { elevenLabsAdapter.synthesize("texte", null, any()) } returns byteArrayOf(1, 2, 3)

        runBlocking { engine().synthesize("texte", voice = "requested") }

        coVerify(exactly = 1) { elevenLabsFactory.create("key", "requested") }
        coVerify(exactly = 0) { elevenLabsFactory.create("key", "configured") }
    }

    "falls back to the free adapter when the provider fails" {
        coEvery { settingsService.getSettings() } returns settings(provider = "OPENAI", openAiApiKey = "sk-test")
        every { openAiFactory.create("sk-test", null) } returns openAiAdapter
        coEvery { openAiAdapter.synthesize(any(), any()) } throws RuntimeException("provider down")
        coEvery { freeAdapter.synthesize("texte", null, any()) } returns byteArrayOf(9, 9)

        val result = runBlocking { engine().synthesize("texte") }

        result.toList() shouldBe listOf(9, 9)
        coVerify(exactly = 1) { freeAdapter.synthesize("texte", null, any()) }
    }

    "normalizes the raw audio to MP3" {
        coEvery { settingsService.getSettings() } returns settings(provider = "OPENAI", openAiApiKey = "sk-test")
        every { openAiFactory.create("sk-test", null) } returns openAiAdapter
        coEvery { openAiAdapter.synthesize("texte", null, any()) } returns byteArrayOf(5, 5, 5)
        every { AudioConversion.anyToMp3(byteArrayOf(5, 5, 5)) } returns byteArrayOf(7, 7)

        val result = runBlocking { engine().synthesize("texte") }

        result.toList() shouldBe listOf(7, 7)
    }

    "uses the configured language for the free fallback" {
        coEvery { settingsService.getSettings() } returns settings(ttsLang = "de")
        coEvery { freeAdapter.synthesize("texte", null, "de") } returns byteArrayOf(1, 2, 3)

        runBlocking { engine().synthesize("texte") }

        coVerify(exactly = 1) { freeAdapter.synthesize("texte", null, "de") }
    }

    "defaults the language to fr when not configured" {
        coEvery { settingsService.getSettings() } returns settings()
        coEvery { freeAdapter.synthesize("texte", null, "fr") } returns byteArrayOf(1, 2, 3)

        runBlocking { engine().synthesize("texte") }

        coVerify(exactly = 1) { freeAdapter.synthesize("texte", null, "fr") }
    }

    "prefers the requested language over the configured one" {
        coEvery { settingsService.getSettings() } returns settings(ttsLang = "de")
        coEvery { freeAdapter.synthesize("texte", null, "es") } returns byteArrayOf(1, 2, 3)

        runBlocking { engine().synthesize("texte", lang = "es") }

        coVerify(exactly = 1) { freeAdapter.synthesize("texte", null, "es") }
    }
})