package com.maxlass.studio.pack.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import java.util.Base64

class DataUriTest : StringSpec({

    "decodes a data URI with PNG mime type" {
        val png = byteArrayOf(0x01, 0x02, 0x03)
        val uri = "data:image/png;base64,${Base64.getEncoder().encodeToString(png)}"
        decodeImageDataUri(uri) shouldBe png
    }

    "decodes a data URI with any image mime type" {
        val jpeg = byteArrayOf(0x11, 0x22)
        val uri = "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(jpeg)}"
        decodeImageDataUri(uri) shouldBe jpeg
    }

    "decodes a bare base64 string without prefix" {
        val bytes = byteArrayOf(0x0a, 0x0b)
        decodeImageDataUri(Base64.getEncoder().encodeToString(bytes)) shouldBe bytes
    }

    "returns null for null or blank input" {
        decodeImageDataUri(null).shouldBeNull()
        decodeImageDataUri("").shouldBeNull()
        decodeImageDataUri("   ").shouldBeNull()
    }

    "returns null for an HTTP URL" {
        decodeImageDataUri("https://example.com/thumbnail.png").shouldBeNull()
        decodeImageDataUri("http://example.com/thumbnail.png").shouldBeNull()
    }

    "returns null for invalid base64" {
        decodeImageDataUri("data:image/png;base64,@@not-base64@@").shouldBeNull()
    }

    "returns null for empty decoded content" {
        decodeImageDataUri("data:image/png;base64,").shouldBeNull()
    }

    "handles base64 containing padding characters" {
        val bytes = "hello".toByteArray()
        val uri = "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"
        val decoded = decodeImageDataUri(uri)
        decoded.shouldNotBeNull()
        String(decoded, Charsets.UTF_8) shouldBe "hello"
    }
})