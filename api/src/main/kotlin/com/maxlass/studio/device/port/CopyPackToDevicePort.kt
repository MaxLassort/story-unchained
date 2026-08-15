package com.maxlass.studio.device.port

import com.maxlass.studio.device.domain.result.CopyPackToDeviceResult
import com.maxlass.studio.pack.domain.model.Pack

/**
 * Port for copying a pack from the library to the connected Lunii device.
 * Format must match: Raw device accepts only RAW packs; FS device accepts only FS (folder) packs.
 */
fun interface CopyPackToDevicePort {
    suspend fun copyPackToDevice(pack: Pack): CopyPackToDeviceResult
}
