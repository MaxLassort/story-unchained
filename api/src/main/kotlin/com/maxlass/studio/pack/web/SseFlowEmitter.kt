package com.maxlass.studio.pack.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException

object SseFlowEmitter {
    private val json = Json { encodeDefaults = true }

    fun <T> fromFlow(
        events: Flow<T>,
        serializer: KSerializer<T>,
        scope: CoroutineScope,
    ): SseEmitter {
        val emitter = SseEmitter(0L) // server-driven, no timeout
        var collectorJob: Job? = null
        collectorJob = scope.launch {
            try {
                events.collect { event ->
                    emitter.send(SseEmitter.event().data(json.encodeToString(serializer, event)))
                }
            } catch (e: IOException) {
                log.debug("SSE client disconnected: {}", e.message)
            } catch (e: Exception) {
                log.debug("SSE stream ended: {}", e.message)
            } finally {
                collectorJob?.cancel()
            }
        }
        // If the container detects the disconnect first, cancel the collecting
        // coroutine so it doesn't leak waiting for the next event (and then fail to send).
        emitter.onCompletion { collectorJob?.cancel() }
        emitter.onTimeout { collectorJob?.cancel() }
        emitter.onError { collectorJob?.cancel() }
        return emitter
    }

    private val log = LoggerFactory.getLogger(SseFlowEmitter::class.java)
}
