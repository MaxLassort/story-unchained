package com.maxlass.studio.pack.format.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object BytesUtils {

    /** Lowercase hex SHA-1 digest of [data] (replaces commons-codec `DigestUtils.sha1Hex`). */
    fun sha1Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append("0123456789abcdef"[(b.toInt() ushr 4) and 0xF])
            sb.append("0123456789abcdef"[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    /** Converts a byte array into an int array, 4 bytes per int, in the given byte order. */
    fun toIntArray(data: ByteArray, endianness: ByteOrder): IntArray {
        val bb = ByteBuffer.wrap(data).order(endianness)
        val ints = IntArray(data.size / 4)
        for (i in ints.indices) {
            ints[i] = bb.int
        }
        return ints
    }

    /** Converts an int array into a byte array, 4 bytes per int, in the given byte order. */
    fun toByteArray(data: IntArray, endianness: ByteOrder): ByteArray {
        val bb = ByteBuffer.allocate(data.size * 4).order(endianness)
        for (i in data) {
            bb.putInt(i)
        }
        return bb.array()
    }

    /** Reverses the endianness of [data] (little-endian <-> big-endian). */
    fun reverseEndianness(data: ByteArray): ByteArray =
        toByteArray(toIntArray(data, ByteOrder.LITTLE_ENDIAN), ByteOrder.BIG_ENDIAN)

    /** Lowercase hex encoding of [data] (replaces commons-codec `Hex.encodeHexString`). */
    fun toHexString(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            sb.append("0123456789abcdef"[(b.toInt() ushr 4) and 0xF])
            sb.append("0123456789abcdef"[b.toInt() and 0xF])
        }
        return sb.toString()
    }
}
