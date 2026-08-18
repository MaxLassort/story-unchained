package com.maxlass.studio.pack.domain.dto

import kotlinx.serialization.Serializable

/** A selectable TTS voice (id is the provider voice id / name). */
@Serializable
data class TtsVoiceDto(
    val id: String,
    val name: String,
)

/** List of available voices for a TTS provider. [fallback] is true when the provider voice
 * list could not be fetched live and the built-in default list was used instead. */
@Serializable
data class TtsVoicesResponse(
    val provider: String,
    val voices: List<TtsVoiceDto>,
    val fallback: Boolean = false,
)