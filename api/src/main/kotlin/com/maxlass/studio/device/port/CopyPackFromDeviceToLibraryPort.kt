package com.maxlass.studio.device.port

import com.maxlass.studio.device.domain.result.CopyPackFromDeviceToLibraryResult

/**
 * Port for copying a pack from the connected Lunii device to the local library.
 * [libraryPath] is the root directory where the pack will be written (e.g. FS: libraryPath/uuid, Raw: libraryPath/uuid.pack).
 */
fun interface CopyPackFromDeviceToLibraryPort {
    suspend fun copyFromDeviceToLibrary(packId: String, libraryPath: String): CopyPackFromDeviceToLibraryResult
}
