package com.maxlass.studio.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "sync_jobs")
class SyncJobEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0L,

    @Column(name = "status", nullable = false, length = 32)
    var status: String,

    @Column(name = "startedAtEpochMs", nullable = false)
    var startedAtEpochMs: Long,

    @Column(name = "finishedAtEpochMs")
    var finishedAtEpochMs: Long? = null,

    @Column(name = "totalEntries", nullable = false)
    var totalEntries: Int = 0,

    @Column(name = "processedEntries", nullable = false)
    var processedEntries: Int = 0,

    @Column(name = "synchronizedCount", nullable = false)
    var synchronizedCount: Int = 0,

    @Column(name = "invalidQueuedCount", nullable = false)
    var invalidQueuedCount: Int = 0,

    @Column(name = "failedCount", nullable = false)
    var failedCount: Int = 0,

    @Column(name = "message")
    var message: String? = null,

    @Column(name = "batchSize", nullable = false)
    var batchSize: Int,

    @Column(name = "parallelism", nullable = false)
    var parallelism: Int,
)
