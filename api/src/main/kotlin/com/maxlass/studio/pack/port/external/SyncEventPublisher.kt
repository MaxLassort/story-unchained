package com.maxlass.studio.pack.port.external

import com.maxlass.studio.pack.domain.dto.SyncStatusEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.stereotype.Component

@Component
class SyncEventPublisher {
    private val events = MutableSharedFlow<SyncStatusEvent>(replay = 1)

    val sharedEvents = events.asSharedFlow()

    fun publish(event: SyncStatusEvent) {
        // fire-and-forget toward in-memory subscribers; failures here are not transactional
        kotlinx.coroutines.runBlocking { events.emit(event) }
    }
}
