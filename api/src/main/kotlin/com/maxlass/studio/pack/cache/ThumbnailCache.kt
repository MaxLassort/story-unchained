package com.maxlass.studio.pack.cache

import java.util.concurrent.ConcurrentHashMap

class ThumbnailCache {
    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun get(packId: String): ByteArray? = cache[packId]

    fun put(packId: String, bytes: ByteArray) {
        cache[packId] = bytes
    }

    fun remove(packId: String) {
        cache.remove(packId)
    }
}
