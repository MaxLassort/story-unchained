package com.maxlass.studio.device.domain.result

/** Result of deleting a pack from the Lunii device. */
sealed class DeletePackFromDeviceResult {
    data object Success : DeletePackFromDeviceResult()
    data object PackNotFoundOnDevice : DeletePackFromDeviceResult()
    data object DeviceNotPlugged : DeletePackFromDeviceResult()
    data object NotSupported : DeletePackFromDeviceResult()
    data class Error(val message: String) : DeletePackFromDeviceResult()
}
