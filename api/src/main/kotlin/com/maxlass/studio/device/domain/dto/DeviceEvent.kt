package com.maxlass.studio.device.domain.dto

import com.maxlass.studio.device.domain.model.DeviceInfos
import com.maxlass.studio.device.domain.model.DevicePack
import kotlinx.serialization.Serializable

/**
 * Message sent over the device WebSocket: device infos + list of packs when device is plugged.
 * [packs] is null when [device.plugged] is false.
 */
@Serializable
data class DeviceEvent(
    val device: DeviceInfos,
    val packs: List<DevicePack>? = null,
    val conversion: ConversionEvent? = null
)

@Serializable
data class ConversionEvent(
    val packId: String,
    val sourceFormat: String,
    val targetFormat: String,
    val status: String,
    val message: String? = null
)
