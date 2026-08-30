package com.maxlass.studio.pack.port.external

import com.maxlass.studio.pack.domain.dto.SyncStatus
import com.maxlass.studio.pack.domain.dto.SyncStatusEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SyncEventPublisherTest : StringSpec({

    val publisher = SyncEventPublisher()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    "publishing an event makes it available on the shared flow" {
        runBlocking {
            publisher.publish(
                SyncStatusEvent(
                    status = SyncStatus.PENDING,
                    totalEntries = 10,
                    startedAtEpochMs = 1L,
                )
            )

            val first = publisher.sharedEvents.first()
            first.status shouldBe SyncStatus.PENDING
            first.totalEntries shouldBe 10
            first.startedAtEpochMs shouldBe 1L
        }
    }

    "replay = 1 surfaces the latest event to a late subscriber" {
        runBlocking {
            publisher.publish(SyncStatusEvent(status = SyncStatus.PENDING))
            publisher.publish(SyncStatusEvent(status = SyncStatus.RUNNING))

            publisher.sharedEvents.first().status shouldBe SyncStatus.RUNNING
        }
    }

    "a late subscriber receives the latest event through replay" {
        runBlocking {
            publisher.publish(SyncStatusEvent(status = SyncStatus.PENDING))
            publisher.publish(SyncStatusEvent(status = SyncStatus.RUNNING))

            val latest = publisher.sharedEvents.first()
            latest.status shouldBe SyncStatus.RUNNING
        }
    }

    "published events are deliverable to different collectors" {
        runBlocking {
            val a = mutableListOf<SyncStatusEvent>()
            val b = mutableListOf<SyncStatusEvent>()

            val jobA = scope.launch {
                publisher.sharedEvents.take(2).collect { a.add(it) }
            }
            val jobB = scope.launch {
                publisher.sharedEvents.take(2).collect { b.add(it) }
            }

            publisher.publish(SyncStatusEvent(status = SyncStatus.PENDING))
            delay(200)
            publisher.publish(SyncStatusEvent(status = SyncStatus.RUNNING))
            delay(200)

            jobA.cancel()
            jobB.cancel()

            a.size shouldBe 2
            b.size shouldBe 2
            a[0].status shouldBe SyncStatus.PENDING
            a[1].status shouldBe SyncStatus.RUNNING
        }
    }
})
