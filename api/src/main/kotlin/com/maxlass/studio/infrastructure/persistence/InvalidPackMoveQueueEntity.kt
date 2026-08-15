package com.maxlass.studio.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "invalid_pack_move_queue")
class InvalidPackMoveQueueEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0L,

    @Column(name = "sourcePath", nullable = false, length = 2048)
    var sourcePath: String,

    @Column(name = "targetPath", nullable = false, length = 2048)
    var targetPath: String,

    @Column(name = "reason", nullable = false, length = 512)
    var reason: String,

    @Column(name = "status", nullable = false, length = 32)
    var status: String,

    @Column(name = "error")
    var error: String? = null,

    @Column(name = "createdAtEpochMs", nullable = false)
    var createdAtEpochMs: Long,

    @Column(name = "processedAtEpochMs")
    var processedAtEpochMs: Long? = null,
)
