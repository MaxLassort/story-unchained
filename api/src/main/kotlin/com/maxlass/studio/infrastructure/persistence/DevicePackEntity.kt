package com.maxlass.studio.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

@Entity
@Table(name = "device_packs")
class DevicePackEntity(
    @EmbeddedId
    var id: DevicePackId = DevicePackId(),

    @Column(name = "version", nullable = false)
    var version: Short = 0,

    @Column(name = "sizeInBytes", nullable = false)
    var sizeInBytes: Long = 0L,

    @Column(name = "lastSeenAtEpochMs", nullable = false)
    var lastSeenAtEpochMs: Long,
)

@Embeddable
class DevicePackId(
    @Column(name = "deviceUuid", nullable = false, length = 255)
    var deviceUuid: String = "",

    @Column(name = "packUuid", nullable = false, length = 255)
    var packUuid: String = "",
) : Serializable
