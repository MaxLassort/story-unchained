package com.maxlass.studio.pack.format.utils

import java.nio.ByteOrder

/**
 * XXTEA block cipher used to encrypt/decrypt the first block of FS pack index
 * and asset files. Mirrors `studio.core.v1.utils.XXTEACipher`.
 */
object XxteaCipher {

    /** 128-bit key shared across all Lunii FS packs. */
    val COMMON_KEY: ByteArray = byteArrayOf(
        (-111).toByte(), (-67).toByte(), 122, 10, (-89).toByte(), 84, 64, (-87).toByte(),
        (-69).toByte(), (-44).toByte(), (-99).toByte(), 108, (-32).toByte(), (-36).toByte(), (-64).toByte(), (-29).toByte(),
    )

    private const val DELTA = -1640531527

    /**
     * Encrypts (n > 1) or decrypts (n < -1) [v] in place using key [k].
     * Mirrors the reference `btea` implementation (n = -len for decryption).
     */
    fun btea(v: IntArray, n: Int, k: IntArray): IntArray {
        if (n > 1) {
            var rounds = 1 + 52 / n
            var sum = 0
            var z = v[n - 1]
            do {
                sum += DELTA
                val e = sum ushr 2 and 3
                var y: Int
                for (p in 0 until n - 1) {
                    y = v[p + 1]
                    v[p] += mx(k, e, p, y, z, sum)
                    z = v[p]
                }
                y = v[0]
                v[n - 1] += mx(k, e, n - 1, y, z, sum)
                z = v[n - 1]
            } while (--rounds != 0)
        } else if (n < -1) {
            var size = -n
            var rounds = 1 + 52 / size
            var sum = rounds * DELTA
            var y = v[0]
            do {
                val e = sum ushr 2 and 3
                var z: Int
                for (p in size - 1 downTo 1) {
                    z = v[p - 1]
                    v[p] -= mx(k, e, p, y, z, sum)
                    y = v[p]
                }
                z = v[size - 1]
                v[0] -= mx(k, e, 0, y, z, sum)
                y = v[0]
                sum -= DELTA
            } while (--rounds != 0)
        }
        return v
    }

    private fun mx(k: IntArray, e: Int, p: Int, y: Int, z: Int, sum: Int): Int =
        ((z ushr 5 xor (y shl 2)) + (y ushr 3 xor (z shl 4))) xor ((sum xor y) + (k[p and 3 xor e] xor z))
}
