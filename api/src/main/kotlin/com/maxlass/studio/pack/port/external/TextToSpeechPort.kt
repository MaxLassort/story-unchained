package com.maxlass.studio.pack.port.external

/**
 * Text-to-speech synthesis port. Implementations must return audio that can be
 * normalized to MP3 mono 44.1 kHz (see [com.maxlass.studio.pack.format.utils.AudioConversion]).
 */
interface TextToSpeechPort {
    /** Synthesizes [text] into audio bytes (MP3). [voice] overrides the configured provider voice
     * and [lang] the language (ISO 639-1, used by the free Google fallback) when non-null. */
    suspend fun synthesize(text: String, voice: String? = null, lang: String? = null): ByteArray
}

/** TTS providers selectable in user settings. */
enum class TtsProvider {
    OPENAI,
    ELEVENLABS,
    FREE;

    companion object {
        fun fromSettings(value: String?): TtsProvider? = value?.let { v ->
            entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
        }
        /** Parse a free‑form query‑string ("OPENAI", "ELEVENLABS", anything else → FREE). */
        fun fromValue(value: String?): TtsProvider
            = when (value) {
                "OPENAI" -> OPENAI
                "ELEVENLABS" -> ELEVENLABS
                else -> FREE
            }
    }
}

/** Creates a keyed TTS adapter for one user (BYOK) with the configured voice. */
fun interface KeyedTtsAdapterFactory {
    fun create(apiKey: String, voice: String?): TextToSpeechPort
}