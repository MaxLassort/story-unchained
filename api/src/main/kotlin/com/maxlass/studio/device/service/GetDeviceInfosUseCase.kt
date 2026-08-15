package com.maxlass.studio.device.service

import com.maxlass.studio.device.domain.model.DeviceInfos
import com.maxlass.studio.device.port.GetDeviceInfosPort
import org.springframework.stereotype.Service

/**
 * Use case: returns current Lunii device infos (plugged or not, firmware, storage, etc.).
 */
@Service
class GetDeviceInfosUseCase(
    private val getDeviceInfosPort: GetDeviceInfosPort
) {

    suspend operator fun invoke(): DeviceInfos = getDeviceInfosPort.getDeviceInfos()
}
