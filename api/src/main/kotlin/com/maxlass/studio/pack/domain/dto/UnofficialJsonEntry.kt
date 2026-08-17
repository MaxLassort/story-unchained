package com.maxlass.studio.pack.domain.dto

/**
 * One pack entry as stored in unofficial.json (Studio Core format).
 * Keys: uuid, title, description, image (base64 or URL).
 */
data class UnofficialJsonEntry(
    val uuid: String,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null
)