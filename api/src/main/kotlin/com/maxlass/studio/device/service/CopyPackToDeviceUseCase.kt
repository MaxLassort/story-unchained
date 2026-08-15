package com.maxlass.studio.device.service

import com.maxlass.studio.device.domain.result.CopyPackToDeviceResult
import com.maxlass.studio.device.port.CopyPackToDevicePort
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import org.springframework.stereotype.Service

/**
 * Copies a pack from the library to the connected Lunii device.
 * Returns [CopyPackToDeviceResult.PackNotFound] if the pack id is not in the library.
 */
@Service
class CopyPackToDeviceUseCase(
    private val packRepository: PackRepositoryPort,
    private val copyPackToDevicePort: CopyPackToDevicePort
) {
    suspend operator fun invoke(packId: String): CopyPackToDeviceResult {
        val pack = packRepository.getAllPacks().find { it.id == packId }
            ?: return CopyPackToDeviceResult.PackNotFound
        return copyPackToDevicePort.copyPackToDevice(pack)
    }
}
