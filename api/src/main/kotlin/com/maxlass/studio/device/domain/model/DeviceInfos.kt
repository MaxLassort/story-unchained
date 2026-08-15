package com.maxlass.studio.device.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain model for Lunii device information when the device is plugged in.
 * Returned by [com.maxlass.studio.device.port.GetDeviceInfosPort]; when no device is present, [plugged] is false.
 */
@Serializable
data class DeviceInfos(
    val plugged: Boolean,
    val uuid: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val driver: String? = null,
    val storage: DeviceStorage? = null,
    val error: Boolean = false
)

/** Storage usage on the device (size, free, taken in bytes). */
@Serializable
data class DeviceStorage(
    val size: Long,
    val free: Long,
    val taken: Long
)
