package com.maxlass.studio.device.service

import com.maxlass.studio.device.domain.result.DeletePackFromDeviceResult
import com.maxlass.studio.device.port.DeletePackFromDevicePort
import org.springframework.stereotype.Service

@Service
class DeletePackFromDeviceUseCase(
    private val deletePackFromDevicePort: DeletePackFromDevicePort
) {
    suspend operator fun invoke(packId: String): DeletePackFromDeviceResult =
        deletePackFromDevicePort.deletePackFromDevice(packId)
}
