package com.maxlass.studio.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "pack_scan_index")
class PackScanIndexEntity(
    @Id
    @Column(name = "path", nullable = false, length = 4096)
    var path: String,

    @Column(name = "isDirectory", nullable = false)
    var isDirectory: Boolean,

    @Column(name = "size", nullable = false)
    var size: Long,

    @Column(name = "lastModifiedMs", nullable = false)
    var lastModifiedMs: Long,

    @Column(name = "contentHash", length = 128)
    var contentHash: String? = null,

    @Column(name = "lastSeenAtEpochMs", nullable = false)
    var lastSeenAtEpochMs: Long,

    @Column(name = "lastStatus", nullable = false, length = 32)
    var lastStatus: String,

    @Column(name = "packId", length = 255)
    var packId: String? = null,

    @Column(name = "detectedFormat", length = 50)
    var detectedFormat: String? = null,
)
