package com.maxlass.studio.device.domain.result

/**
 * Result of a copy-pack-to-device operation.
 * [Success] when the pack was copied; [FormatIncompatible] when device and pack formats don't match;
 * [PackNotFound], [DeviceNotPlugged], [PackAlreadyOnDevice], or [Error] otherwise.
 */
sealed class CopyPackToDeviceResult {
    data object Success : CopyPackToDeviceResult()
    data class FormatIncompatible(val message: String) : CopyPackToDeviceResult()
    data object PackNotFound : CopyPackToDeviceResult()
    data object DeviceNotPlugged : CopyPackToDeviceResult()
    data object PackAlreadyOnDevice : CopyPackToDeviceResult()
    data class Error(val message: String) : CopyPackToDeviceResult()
}
