package com.maxlass.studio.device.domain.model

import kotlinx.serialization.Serializable

/** Snapshot of a known device: its UUID, the most recent time we saw it,
 *  how many packs it had, and the full pack list (with metadata when available). */
@Serializable
data class DeviceSnapshot(
    val uuid: String,
    val lastSeenAtEpochMs: Long,
    val packCount: Int,
    val packs: List<DevicePack>,
)
