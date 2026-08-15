package com.maxlass.studio.pack.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipThumbnailEntryTest : StringSpec({

    "prefers meta/thumbnail.png over root thumbnail.png" {
        val tmp = File.createTempFile("zip-thumb", ".zip")
        ZipOutputStream(tmp.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("thumbnail.png"))
            zos.write(byteArrayOf(1))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("meta/thumbnail.png"))
            zos.write(byteArrayOf(2, 2))
            zos.closeEntry()
        }
        ZipFile(tmp).use { zf ->
            val entry = findThumbnailEntry(zf)!!
            entry.name shouldBe "meta/thumbnail.png"
            zf.getInputStream(entry).use { it.readBytes() } shouldBe byteArrayOf(2, 2)
        }
        tmp.delete()
    }

    "falls back to root thumbnail.png when meta/thumbnail.png is absent" {
        val tmp = File.createTempFile("zip-thumb", ".zip")
        ZipOutputStream(tmp.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("thumbnail.png"))
            zos.write(byteArrayOf(1))
            zos.closeEntry()
        }
        ZipFile(tmp).use { zf ->
            findThumbnailEntry(zf)!!.name shouldBe "thumbnail.png"
        }
        tmp.delete()
    }

    "returns null when no thumbnail exists" {
        val tmp = File.createTempFile("zip-thumb", ".zip")
        ZipOutputStream(tmp.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("story.json"))
            zos.write(byteArrayOf(1))
            zos.closeEntry()
        }
        ZipFile(tmp).use { zf ->
            findThumbnailEntry(zf) shouldBe null
        }
        tmp.delete()
    }
})
