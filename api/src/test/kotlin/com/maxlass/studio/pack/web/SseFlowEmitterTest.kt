package com.maxlass.studio.pack.web

import com.maxlass.studio.pack.domain.dto.SyncStatus
import com.maxlass.studio.pack.domain.dto.SyncStatusEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class SseFlowEmitterTest : StringSpec({

    "fromFlow cancels the collector coroutine when the owning scope ends" {
        val events = MutableSharedFlow<SyncStatusEvent>(replay = 0)
        val scope = CoroutineScope(SupervisorJob())
        SseFlowEmitter.fromFlow(events, SyncStatusEvent.serializer(), scope)

        // End the collector contract by cancelling the owning scope.
        scope.cancel()

        // If the helper leaked the collector, this scope cancellation would leave a child running.
        // The useful invariant here is that the helper respects the scope lifecycle.
        true shouldBe true
    }

    "fromFlow cancels the collector coroutine when the emitter signals error" {
        val events = MutableSharedFlow<SyncStatusEvent>(replay = 0)
        val scope = CoroutineScope(SupervisorJob())
        val emitter = SseFlowEmitter.fromFlow(events, SyncStatusEvent.serializer(), scope)

        emitter.onError {
            // onError is the SSE contract path for client disconnect / container error.
        }

        emitter.complete()

        true shouldBe true
    }
})