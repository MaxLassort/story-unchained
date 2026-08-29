package com.maxlass.studio.device.driver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.usb4java.Context
import org.usb4java.Device
import org.usb4java.DeviceDescriptor
import org.usb4java.DeviceList
import org.usb4java.HotplugCallback
import org.usb4java.HotplugCallbackHandle
import org.usb4java.LibUsb
import org.usb4java.LibUsbException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext

/** Callback fired when a Lunii device of a given kind is plugged or unplugged. */
fun interface DeviceHotplugListener {
    fun onDeviceChanged(plugged: Boolean, device: Device?)
}

/**
 * Single-libusb-context hotplug manager for Lunii devices, running the libusb event loop
 * in a coroutine (replaces the shadowed `studio.driver.LibUsbDetectionHelper` and the
 * thread-based `LibUsbAsyncEventsWorker`). Falls back to active polling when hotplug is
 * not supported by the platform.
 */
class LuniiUsb(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    companion object {
        private val log = LoggerFactory.getLogger(LuniiUsb::class.java)

        private const val FW1_VID = 0x0c45
        private const val FW1_PID = 0x6820
        private const val FW2_VID = 0x0c45
        private const val FW2_PID = 0x6840
        private const val V2_VID = 0x0483
        private const val V2_PID = 0xa341

        private const val POLL_DELAY_MS = 5_000L
        private const val EVENT_LOOP_TIMEOUT_US = 250_000L
    }

    private val context = Context()
    private var started = false

    private val rawListeners = CopyOnWriteArrayList<DeviceHotplugListener>()
    private val fsListeners = CopyOnWriteArrayList<DeviceHotplugListener>()

    private var eventLoopJob: Job? = null
    private var pollingJob: Job? = null

    fun registerListener(kind: LuniiDeviceKind, listener: DeviceHotplugListener) {
        when (kind) {
            LuniiDeviceKind.RAW -> rawListeners += listener
            LuniiDeviceKind.FS -> fsListeners += listener
        }
    }

    /** Initializes libusb once and starts hotplug handling. Call after registering listeners. */
    fun start() {
        if (started) return
        started = true
        val result = LibUsb.init(context)
        if (result != LibUsb.SUCCESS) {
            throw StoryTellerException("Unable to initialize libusb", LibUsbException(result))
        }
        eventLoopJob = scope.launch { runEventLoop() }
        if (LibUsb.hasCapability(LibUsb.CAP_HAS_HOTPLUG)) {
            registerHotplugCallbacks()
        } else {
            log.warn("libusb hotplug is not supported: scheduling active polling")
            pollingJob = scope.launch { pollingLoop() }
        }
    }

    /** Cancels the event loop / polling and exits libusb. */
    fun stop() {
        eventLoopJob?.cancel()
        pollingJob?.cancel()
        runBlocking { listOfNotNull(eventLoopJob, pollingJob).joinAll() }
        scope.cancel()
        LibUsb.exit(context)
    }

    private suspend fun runEventLoop() {
        while (coroutineContext.isActive) {
            val result = LibUsb.handleEventsTimeout(context, EVENT_LOOP_TIMEOUT_US)
            if (result != LibUsb.SUCCESS && result != LibUsb.ERROR_INTERRUPTED) {
                log.warn("libusb handleEvents failed: {}", result)
            }
        }
    }

    private fun registerHotplugCallbacks() {
        val events = LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED or LibUsb.HOTPLUG_EVENT_DEVICE_LEFT
        for ((kind, vid, pid) in listOf(
            Triple(LuniiDeviceKind.RAW, FW1_VID, FW1_PID),
            Triple(LuniiDeviceKind.FS, FW2_VID, FW2_PID),
            Triple(LuniiDeviceKind.FS, V2_VID, V2_PID),
        )) {
            val callback = HotplugCallback { _, device, event, _ ->
                val plugged = event == LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED
                log.info("Hotplug event ({}:{}): {}", Integer.toHexString(vid), String.format("%04x", pid), if (plugged) "plugged" else "unplugged")
                dispatch(kind, plugged, device)
                0
            }
            LibUsb.hotplugRegisterCallback(
                context,
                events,
                LibUsb.HOTPLUG_ENUMERATE,
                vid,
                pid,
                LibUsb.HOTPLUG_MATCH_ANY,
                callback,
                null,
                HotplugCallbackHandle(),
            )
        }
    }

    private suspend fun pollingLoop() {
        var previous = enumerateKinds()
        fireChanges(LuniiDeviceKind.RAW, previous, previous)
        fireChanges(LuniiDeviceKind.FS, previous, previous)
        while (coroutineContext.isActive) {
            delay(POLL_DELAY_MS)
            val current = enumerateKinds()
            fireChanges(LuniiDeviceKind.RAW, previous, current)
            fireChanges(LuniiDeviceKind.FS, previous, current)
            previous = current
        }
    }

    private fun fireChanges(kind: LuniiDeviceKind, previous: Set<LuniiDeviceKind>, current: Set<LuniiDeviceKind>) {
        val wasPlugged = kind in previous
        val isPlugged = kind in current
        if (wasPlugged != isPlugged) {
            log.info("Polling: Lunii {} {}", kind, if (isPlugged) "plugged" else "unplugged")
            dispatch(kind, isPlugged, null)
        }
    }

    private fun enumerateKinds(): Set<LuniiDeviceKind> {
        val devices = DeviceList()
        if (LibUsb.getDeviceList(context, devices) < 0) return emptySet()
        return try {
            val found = mutableSetOf<LuniiDeviceKind>()
            for (device in devices) {
                val descriptor = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(device, descriptor) != LibUsb.SUCCESS) continue
                val vid = descriptor.idVendor().toInt() and 0xFFFF
                val pid = descriptor.idProduct().toInt() and 0xFFFF
                when {
                    vid == FW1_VID && pid == FW1_PID -> found += LuniiDeviceKind.RAW
                    (vid == FW2_VID && pid == FW2_PID) || (vid == V2_VID && pid == V2_PID) -> found += LuniiDeviceKind.FS
                }
            }
            found
        } finally {
            LibUsb.freeDeviceList(devices, false)
        }
    }

    private fun dispatch(kind: LuniiDeviceKind, plugged: Boolean, device: Device?) {
        val listeners = when (kind) {
            LuniiDeviceKind.RAW -> rawListeners
            LuniiDeviceKind.FS -> fsListeners
        }
        listeners.forEach { it.onDeviceChanged(plugged, device) }
    }
}
