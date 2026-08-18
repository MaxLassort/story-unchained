package com.maxlass.studio.pack.adapter

import com.maxlass.studio.pack.port.external.TextToSpeechPort
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Free fallback TTS using the public Google Translate TTS endpoint
 * (`translate.google.com/translate_tts`, `client=tw-ob`).
 *
 * Google limits each request to ~200 characters, so longer texts are split on word
 * boundaries and the MP3 chunks are concatenated. No API key required.
 */
class GoogleTranslateTtsAdapter(
    private val defaultLang: String = "fr",
    private val maxCharsPerRequest: Int = 200,
    private val baseUrl: String = "https://translate.google.com",
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : TextToSpeechPort {

    override suspend fun synthesize(text: String, voice: String?, lang: String?): ByteArray = withContext(Dispatchers.IO) {
        val language = lang ?: defaultLang
        val segments = segment(text, maxCharsPerRequest)
        logger.info("Google Translate TTS: {} chars split into {} request(s)", text.length, segments.size)
        val output = ByteArrayOutputStream()
        for (segment in segments) {
            val url = "$baseUrl/translate_tts?ie=UTF-8&client=tw-ob&tl=$language&q=" +
                URLEncoder.encode(segment, StandardCharsets.UTF_8)
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            check(response.statusCode() == 200) {
                "Google Translate TTS returned HTTP ${response.statusCode()} (text too long or unsupported language '$lang')"
            }
            output.write(response.body())
        }
        output.toByteArray()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(GoogleTranslateTtsAdapter::class.java)

        private const val USER_AGENT = "Mozilla/5.0 (compatible; StoryUnchained/1.0)"

        /** Splits [text] into chunks of at most [max] chars, breaking on spaces when possible. */
        internal fun segment(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val segments = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                val end = minOf(start + max, text.length)
                if (end < text.length) {
                    val lastSpace = text.lastIndexOf(' ', end - 1)
                    val boundary = if (lastSpace > start) lastSpace else end
                    segments += text.substring(start, boundary)
                    start = boundary + 1
                } else {
                    segments += text.substring(start)
                    start = text.length
                }
            }
            return segments
        }
    }
}