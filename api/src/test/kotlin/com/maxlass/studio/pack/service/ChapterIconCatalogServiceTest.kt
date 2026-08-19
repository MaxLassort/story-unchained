package com.maxlass.studio.pack.service

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the on-demand Lucide icon fetch + search against a local HTTP server acting as
 * the lucide-static CDN and the jsDelivr catalog API.
 */
class ChapterIconCatalogServiceTest : StringSpec({

    fun server(): Pair<HttpServer, String> {
        val iconHits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/icons/moon-star.svg") { exchange ->
            iconHits.incrementAndGet()
            val body = """<svg viewBox="0 0 24 24"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z"/></svg>"""
            exchange.responseHeaders.add("Content-Type", "image/svg+xml")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.write(body.toByteArray())
            exchange.close()
        }
        server.createContext("/icons/unknown.svg") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.createContext("/icons/bad.svg") { exchange ->
            val body = "<html>Not found: bad.svg</html>"
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.write(body.toByteArray())
            exchange.close()
        }
        server.createContext("/catalog") { exchange ->
            val body = """
                {"name":"lucide-static","files":[
                  {"name":"icons","type":"directory","files":[
                    {"name":"moon-star.svg","type":"file"},
                    {"name":"moon.svg","type":"file"},
                    {"name":"castle.svg","type":"file"}
                  ]}
                ]}
            """.trimIndent()
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.write(body.toByteArray())
            exchange.close()
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    "fetches a remote icon on demand and caches it" {
        val (server, baseUrl) = server()
        try {
            val catalog = ChapterIconCatalogService()
            catalog.iconBaseUrl = baseUrl
            catalog.catalogUrl = "$baseUrl/catalog"

            val svg = runBlocking { catalog.loadIcon("moon-star") }
            svg shouldNotBe null
            svg!!.contains("<path") shouldBe true

            // second call hits the in-memory cache, not the server
            runBlocking { catalog.loadIcon("moon-star") }
        } finally {
            server.stop(0)
        }
    }

    "returns null for unknown remote icons" {
        val (server, baseUrl) = server()
        try {
            val catalog = ChapterIconCatalogService()
            catalog.iconBaseUrl = baseUrl
            catalog.catalogUrl = "$baseUrl/catalog"

            runBlocking { catalog.loadIcon("unknown") } shouldBe null
        } finally {
            server.stop(0)
        }
    }

    "rejects non-SVG responses from the CDN" {
        val (server, baseUrl) = server()
        try {
            val catalog = ChapterIconCatalogService()
            catalog.iconBaseUrl = baseUrl
            catalog.catalogUrl = "$baseUrl/catalog"

            runBlocking { catalog.loadIcon("bad") } shouldBe null
        } finally {
            server.stop(0)
        }
    }

    "searches the remote catalog" {
        val (server, baseUrl) = server()
        try {
            val catalog = ChapterIconCatalogService()
            catalog.iconBaseUrl = baseUrl
            catalog.catalogUrl = "$baseUrl/catalog"

            // bundled icons match first, then remote catalog entries
            val results = runBlocking { catalog.searchIcons("moon") }
            results.map { it.id } shouldBe listOf("moon", "moon-star")
        } finally {
            server.stop(0)
        }
    }

    "bundled icons are listed and loadable without the network" {
        val catalog = ChapterIconCatalogService()
        catalog.iconBaseUrl = "http://127.0.0.1:1" // unreachable
        catalog.catalogUrl = "http://127.0.0.1:1"

        val icons = catalog.listIcons()
        icons.size shouldBe 4
        icons.map { it.id } shouldContain "star"

        val svg = runBlocking { catalog.loadIcon("star") }
        svg shouldNotBe null
        svg!!.contains("<path") shouldBe true
    }
})