package com.maxlass.studio.settings.domain

import kotlinx.serialization.Serializable

/** User settings (e.g. library path for packs). */
@Serializable
data class Settings(
    /** Directory where the user stores pack files (.zip, FS, RAW). */
    val libraryPath: String,
    /** Path to Studio Core unofficial.json (e.g. ~/.studio/db/unofficial.json). */
    val unofficialDbPath: String? = null,
    /** Preferred Lunii target type when no device is plugged ("RAW", "FS"), or null for auto mode. */
    val targetDeviceType: String? = null,
    /** Text-to-speech provider used for story creation ("OPENAI", "ELEVENLABS"), or null/blank for the free fallback ("FREE"). */
    val ttsProvider: String? = null,
    /** User OpenAI API key (BYOK). Stored in plain text in the local settings file. */
    val ttsOpenAiApiKey: String? = null,
    /** User ElevenLabs API key (BYOK). Stored in plain text in the local settings file. */
    val ttsElevenLabsApiKey: String? = null,
    /** Preferred TTS voice for the configured provider, or null for the provider default. */
    val ttsVoice: String? = null,
    /** TTS language (ISO 639-1, e.g. "fr"), used by the free Google Translate fallback. Null means "fr". */
    val ttsLang: String? = null
)
