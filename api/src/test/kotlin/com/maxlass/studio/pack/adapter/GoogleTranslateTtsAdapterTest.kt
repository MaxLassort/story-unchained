package com.maxlass.studio.pack.adapter

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicInteger

class GoogleTranslateTtsAdapterTest : StringSpec({

    fun serverReturning(vararg chunks: ByteArray): Pair<HttpServer, AtomicInteger> {
        val count = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/translate_tts") { exchange ->
            val idx = count.getAndIncrement()
            val body = if (idx < chunks.size) chunks[idx] else ByteArray(0)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server to count
    }

    fun adapterFor(server: HttpServer) = GoogleTranslateTtsAdapter(
        defaultLang = "fr",
        maxCharsPerRequest = 200,
        baseUrl = "http://127.0.0.1:${server.address.port}",
        httpClient = HttpClient.newBuilder().build(),
    )

    "keeps a short text as a single request" {
        val (server, count) = serverReturning(byteArrayOf(1, 2, 3))
        try {
            val result = runBlocking { adapterFor(server).synthesize("Bonjour") }
            result.toList() shouldBe listOf(1, 2, 3)
            count.get() shouldBe 1
        } finally {
            server.stop(0)
        }
    }

    "splits long texts into multiple requests on word boundaries" {
        val longText = (1..120).joinToString(" ") { "mot$it" }
        val (server, count) = serverReturning(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4))
        try {
            val result = runBlocking { adapterFor(server).synthesize(longText) }
            result.toList() shouldBe listOf(1, 2, 3, 4)
            count.get() shouldBe 4
        } finally {
            server.stop(0)
        }
    }

    "segments text without exceeding the max chars per request" {
        val longText = (1..120).joinToString(" ") { "mot$it" }
        val segments = GoogleTranslateTtsAdapter.segment(longText, 200)
        segments.forEach { it.length shouldBeLessThanOrEqualTo 200 }
        segments.joinToString(" ").replace(Regex("\\s+"), " ").trim() shouldBe longText
    }

    "fails on a non-200 response" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/translate_tts") { exchange ->
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }
        server.start()
        try {
            shouldThrow<IllegalStateException> {
                runBlocking { adapterFor(server).synthesize("Bonjour") }
            }
        } finally {
            server.stop(0)
        }
    }

    "builds a request URL with encoded query and tw-ob client" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var seenQuery: String? = null
        server.createContext("/translate_tts") { exchange ->
            seenQuery = exchange.requestURI.query
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.start()
        try {
            runBlocking { adapterFor(server).synthesize("Café !") }
            seenQuery shouldBe "ie=UTF-8&client=tw-ob&tl=fr&q=Café+!"
        } finally {
            server.stop(0)
        }
    }
})