package com.maxlass.studio.pack.util

import java.util.Base64

/**
 * Decodes a base64 data-URI image (e.g. `data:image/png;base64,<...>`) into raw image bytes.
 *
 * @return the decoded bytes, or `null` when the value is absent, an HTTP URL, or an invalid
 *         base64 string (so callers can skip gracefully without failing the sync).
 */
fun decodeImageDataUri(image: String?): ByteArray? {
    if (image.isNullOrBlank()) return null
    if (image.startsWith("http://") || image.startsWith("https://")) return null

    val commaIndex = image.indexOf(',')
    val base64Part = if (commaIndex >= 0 && image.startsWith("data:", ignoreCase = true)) {
        image.substring(commaIndex + 1)
    } else {
        image
    }

    return runCatching { Base64.getDecoder().decode(base64Part) }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
}