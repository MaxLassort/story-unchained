package com.maxlass.studio.pack.service


import com.maxlass.studio.pack.format.utils.AudioConversion
import com.maxlass.studio.pack.port.external.KeyedTtsAdapterFactory
import com.maxlass.studio.pack.port.external.TextToSpeechPort
import com.maxlass.studio.pack.port.external.TtsProvider
import com.maxlass.studio.settings.service.SettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TTS engine: selects the provider configured in user settings (BYOK), falls back to the
 * free Google Translate adapter when no provider or API key is set, or when the provider
 * call fails. All output is normalized to MP3 mono 44.1 kHz via [AudioConversion.anyToMp3].
 */
@Service
class TtsEngine(
    private val settingsService: SettingsService,
    private val openAiTtsAdapterFactory: KeyedTtsAdapterFactory,
    private val elevenLabsTtsAdapterFactory: KeyedTtsAdapterFactory,
    private val freeTtsAdapter: TextToSpeechPort,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TtsEngine::class.java)
    }

    /** Synthesizes [text] to MP3, using [voice] and [lang] when provided (else the configured settings values). */
    suspend fun synthesize(text: String, voice: String? = null, lang: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val settings = settingsService.getSettings()
        val effectiveVoice = voice ?: settings.ttsVoice
        val effectiveLang = lang ?: settings.ttsLang ?: "fr"
        val provider = TtsProvider.fromSettings(settings.ttsProvider)
        val apiKey = when (provider) {
            TtsProvider.OPENAI -> settings.ttsOpenAiApiKey
            TtsProvider.ELEVENLABS -> settings.ttsElevenLabsApiKey
            else -> null
        }?.takeIf { it.isNotBlank() }

        val adapter = when (provider) {
            TtsProvider.OPENAI -> apiKey?.let { openAiTtsAdapterFactory.create(it, effectiveVoice) }
            TtsProvider.ELEVENLABS -> apiKey?.let { elevenLabsTtsAdapterFactory.create(it, effectiveVoice) }
            else -> null
        }

        val raw = if (adapter != null) {
            try {
                adapter.synthesize(text, lang = effectiveLang)
            } catch (e: Exception) {
                logger.warn("TTS provider {} failed ({}), falling back to free TTS", provider, e.message)
                freeTtsAdapter.synthesize(text, lang = effectiveLang)
            }
        } else {
            logger.info("No TTS provider configured, using free Google Translate TTS")
            freeTtsAdapter.synthesize(text, lang = effectiveLang)
        }

        AudioConversion.anyToMp3(raw)
    }
}