package com.maxlass.studio.device.port

import com.maxlass.studio.device.domain.result.DeletePackFromDeviceResult

/** Port for deleting a pack from the connected Lunii device. */
fun interface DeletePackFromDevicePort {
    suspend fun deletePackFromDevice(packId: String): DeletePackFromDeviceResult
}
