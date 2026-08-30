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

/** Thrown when a keyed TTS provider (OPENAI / ELEVENLABS) is requested but no API key is configured. */
class TtsApiKeyMissingException(val provider: TtsProvider) :
    RuntimeException("No API key configured for ${provider.name}. Add one in Settings to use this provider.")

/** Thrown when the selected keyed TTS provider (OPENAI / ELEVENLABS) fails at call time. */
class TtsProviderException(val provider: TtsProvider, cause: Throwable) :
    RuntimeException(
        "${provider.name} TTS failed: ${cause.message}. " +
            "You can switch to the free Google Translate provider (FREE) in Settings or in the form.",
        cause,
    )

/**
 * TTS engine: uses the provider configured in user settings (BYOK) — **no silent fallback**.
 * When OPENAI / ELEVENLABS is selected but no API key is set, throws [TtsApiKeyMissingException];
 * when the provider call fails, throws [TtsProviderException] so the frontend can suggest
 * switching to the free Google Translate provider. All output is normalized to MP3 mono
 * 44.1 kHz via [AudioConversion.anyToMp3].
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

    /** Synthesizes [text] to MP3, using [voice] and [lang] when provided (else the configured settings values).
     *  If [provider] is non‑null it overrides the provider taken from user settings for this call only. */
    suspend fun synthesize(
        text: String,
        voice: String? = null,
        lang: String? = null,
        provider: TtsProvider? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val settings = settingsService.getSettings()
        // ---- resolve the effective provider (explicit > settings) ----
        val effectiveProvider = when {
            provider != null -> provider
            else -> TtsProvider.fromSettings(settings.ttsProvider) ?: TtsProvider.FREE
        }
        val apiKey = when (effectiveProvider) {
            TtsProvider.OPENAI -> settings.ttsOpenAiApiKey
            TtsProvider.ELEVENLABS -> settings.ttsElevenLabsApiKey
            else -> null
        }?.takeIf { it.isNotBlank() }

        val effectiveVoice = voice?.takeIf { it.isNotBlank() } ?: settings.ttsVoice
        val effectiveLang = lang?.takeIf { it.isNotBlank() } ?: settings.ttsLang ?: "fr"

        val adapter = when (effectiveProvider) {
            TtsProvider.OPENAI -> apiKey?.let { openAiTtsAdapterFactory.create(it, effectiveVoice) }
            TtsProvider.ELEVENLABS -> apiKey?.let { elevenLabsTtsAdapterFactory.create(it, effectiveVoice) }
            else -> null
        }

        // A keyed provider was explicitly requested (via this call's provider override or user
        // settings) but no API key is configured — surface this to the caller instead of
        // silently degrading to the free engine.
        if (adapter == null && effectiveProvider != TtsProvider.FREE) {
            throw TtsApiKeyMissingException(effectiveProvider)
        }

        val raw = if (adapter != null) {
            try {
                adapter.synthesize(text, voice = effectiveVoice, lang = effectiveLang)
            } catch (e: Exception) {
                logger.warn("TTS provider {} failed: {}", effectiveProvider, e.message)
                // No silent fallback: surface the failure so the frontend can suggest
                // switching to the free Google Translate provider.
                throw TtsProviderException(effectiveProvider, e)
            }
        } else {
            logger.info("Using free Google Translate TTS")
            freeTtsAdapter.synthesize(text, voice = effectiveVoice, lang = effectiveLang)
        }

        AudioConversion.anyToMp3(raw)
    }
}