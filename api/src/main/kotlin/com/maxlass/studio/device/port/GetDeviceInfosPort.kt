package com.maxlass.studio.device.port

import com.maxlass.studio.device.domain.model.DeviceInfos

/**
 * Port for detecting whether a Lunii device is plugged in and reading its infos.
 * Implementations may use the Studio drivers (raw/FS) in-process or delegate to an external device backend.
 */
fun interface GetDeviceInfosPort {

    /**
     * Returns device infos. [DeviceInfos.plugged] is false when no device is configured or detected.
     */
    suspend fun getDeviceInfos(): DeviceInfos
}
