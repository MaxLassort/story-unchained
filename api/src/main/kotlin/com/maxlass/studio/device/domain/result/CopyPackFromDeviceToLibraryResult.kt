package com.maxlass.studio.device.domain.result

/**
 * Result of copying a pack from the connected Lunii device to the local library.
 */
sealed class CopyPackFromDeviceToLibraryResult {
    data object Success : CopyPackFromDeviceToLibraryResult()
    data object PackNotFoundOnDevice : CopyPackFromDeviceToLibraryResult()
    data object DeviceNotPlugged : CopyPackFromDeviceToLibraryResult()
    data class Error(val message: String) : CopyPackFromDeviceToLibraryResult()
}
