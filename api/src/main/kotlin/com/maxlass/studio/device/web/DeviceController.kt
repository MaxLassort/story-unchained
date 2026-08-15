package com.maxlass.studio.device.web

import com.maxlass.studio.device.adapter.DriverDeviceConnector
import com.maxlass.studio.device.api.CopyPackRequest
import com.maxlass.studio.device.api.CopyPackResponse
import com.maxlass.studio.device.domain.dto.DeviceEvent
import com.maxlass.studio.device.domain.model.DeviceInfos
import com.maxlass.studio.device.domain.model.DeviceSnapshot
import com.maxlass.studio.device.domain.result.CopyPackFromDeviceToLibraryResult
import com.maxlass.studio.device.domain.result.CopyPackToDeviceResult
import com.maxlass.studio.device.domain.result.DeletePackFromDeviceResult
import com.maxlass.studio.device.service.CopyPackFromDeviceToLibraryUseCase
import com.maxlass.studio.device.service.CopyPackToDeviceUseCase
import com.maxlass.studio.device.service.DeletePackFromDeviceUseCase
import com.maxlass.studio.device.service.GetDeviceInfosUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

private val json = Json { encodeDefaults = true }

@RestController
@RequestMapping("/devices")
class DeviceController(
    private val getDeviceInfos: GetDeviceInfosUseCase,
    private val copyToDevice: CopyPackToDeviceUseCase,
    private val deleteFromDevice: DeletePackFromDeviceUseCase,
    private val copyToLibrary: CopyPackFromDeviceToLibraryUseCase,
    private val driverDeviceConnector: DriverDeviceConnector,
) {
    private val sseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @GetMapping
    suspend fun getDeviceInfos(): DeviceInfos =
        getDeviceInfos.invoke()

    @GetMapping("/snapshots")
    suspend fun getDeviceSnapshots(): List<DeviceSnapshot> =
        driverDeviceConnector.getDeviceSnapshots()

    @GetMapping("/packs")
    suspend fun getDevicePacks(
        @RequestParam(required = false) deviceUuid: String?,
    ): ResponseEntity<*> {
        if (deviceUuid != null) {
            return ResponseEntity.ok(driverDeviceConnector.getDevicePacksByUuid(deviceUuid))
        }
        val state = driverDeviceConnector.deviceState.value
        if (!state.plugged) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(CopyPackResponse(ok = false, error = "DEVICE_NOT_PLUGGED"))
        }
        return ResponseEntity.ok(driverDeviceConnector.getDevicePacks())
    }

    @PostMapping("/packs")
    suspend fun copyPackToDevice(@RequestBody body: CopyPackRequest): ResponseEntity<CopyPackResponse> =
        handleCopyPackToDevice(body)

    @DeleteMapping("/packs/{packId}")
    suspend fun deletePackFromDevice(@PathVariable packId: String): ResponseEntity<CopyPackResponse> =
        handleDeletePackFromDevice(packId)

    @PostMapping("/packs/{packId}/copy-to-library")
    suspend fun copyPackToLibrary(@PathVariable packId: String): ResponseEntity<CopyPackResponse> =
        handleCopyPackToLibrary(packId)

    @GetMapping(value = ["/events"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun deviceEvents(): SseEmitter {
        val emitter = SseEmitter(0L) // no timeout, server-driven
        sseScope.launch {
            try {
                emitter.send(
                    SseEmitter.event()
                        .data(json.encodeToString(DeviceEvent.serializer(), buildDeviceEvent()))
                )
                driverDeviceConnector.onDeviceChanged.collect {
                    runCatching {
                        emitter.send(
                            SseEmitter.event()
                                .data(json.encodeToString(DeviceEvent.serializer(), buildDeviceEvent()))
                        )
                    }.onFailure {
                        emitter.completeWithError(it)
                    }
                }
            } catch (e: Exception) {
                emitter.completeWithError(e)
            }
        }
        return emitter
    }

    private suspend fun buildDeviceEvent(): DeviceEvent {
        val infos = driverDeviceConnector.deviceState.value
        val packs = if (infos.plugged) {
            runCatching { driverDeviceConnector.getDevicePacks() }.getOrElse {
                org.slf4j.LoggerFactory.getLogger(DeviceController::class.java)
                    .warn("Failed to read device packs for SSE event: {}", it.message)
                null
            }
        } else {
            null
        }
        return DeviceEvent(device = infos, packs = packs, conversion = driverDeviceConnector.lastConversion.value)
    }

    private suspend fun handleCopyPackToDevice(body: CopyPackRequest): ResponseEntity<CopyPackResponse> =
        when (val result = copyToDevice(body.packId)) {
            is CopyPackToDeviceResult.Success -> {
                driverDeviceConnector.notifyDeviceChanged()
                ResponseEntity.ok(CopyPackResponse(ok = true))
            }
            is CopyPackToDeviceResult.FormatIncompatible ->
                ResponseEntity.badRequest().body(CopyPackResponse(ok = false, error = "FORMAT_INCOMPATIBLE", message = result.message))
            is CopyPackToDeviceResult.PackNotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(CopyPackResponse(ok = false, error = "PACK_NOT_FOUND"))
            is CopyPackToDeviceResult.DeviceNotPlugged ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(CopyPackResponse(ok = false, error = "DEVICE_NOT_PLUGGED"))
            is CopyPackToDeviceResult.PackAlreadyOnDevice ->
                ResponseEntity.badRequest().body(CopyPackResponse(ok = false, error = "PACK_ALREADY_ON_DEVICE", message = "Pack déjà présent"))
            is CopyPackToDeviceResult.Error ->
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(CopyPackResponse(ok = false, error = "ERROR", message = result.message))
        }

    private suspend fun handleDeletePackFromDevice(packId: String): ResponseEntity<CopyPackResponse> =
        when (val result = deleteFromDevice(packId)) {
            is DeletePackFromDeviceResult.Success -> {
                driverDeviceConnector.notifyDeviceChanged()
                ResponseEntity.ok(CopyPackResponse(ok = true))
            }
            is DeletePackFromDeviceResult.PackNotFoundOnDevice ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(CopyPackResponse(ok = false, error = "PACK_NOT_FOUND_ON_DEVICE", message = "Pack non présent sur la Lunii"))
            is DeletePackFromDeviceResult.DeviceNotPlugged ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(CopyPackResponse(ok = false, error = "DEVICE_NOT_PLUGGED"))
            is DeletePackFromDeviceResult.NotSupported ->
                ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(CopyPackResponse(ok = false, error = "NOT_SUPPORTED", message = "Suppression sur la Lunii non supportée"))
            is DeletePackFromDeviceResult.Error ->
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(CopyPackResponse(ok = false, error = "ERROR", message = result.message))
        }

    private suspend fun handleCopyPackToLibrary(packId: String): ResponseEntity<CopyPackResponse> =
        when (val result = copyToLibrary(packId)) {
            is CopyPackFromDeviceToLibraryResult.Success ->
                ResponseEntity.ok(CopyPackResponse(ok = true))
            is CopyPackFromDeviceToLibraryResult.PackNotFoundOnDevice ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(CopyPackResponse(ok = false, error = "PACK_NOT_FOUND_ON_DEVICE", message = "Pack non présent sur la Lunii"))
            is CopyPackFromDeviceToLibraryResult.DeviceNotPlugged ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(CopyPackResponse(ok = false, error = "DEVICE_NOT_PLUGGED"))
            is CopyPackFromDeviceToLibraryResult.Error ->
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(CopyPackResponse(ok = false, error = "ERROR", message = result.message))
        }
}
