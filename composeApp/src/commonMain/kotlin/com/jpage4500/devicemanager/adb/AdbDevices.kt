package com.jpage4500.devicemanager.adb

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

private val log = LoggerConfig.createLogger("AdbDevices")

/**
 * the attached devices, and a flow that re-pushes them whenever one is plugged in or out.
 *
 * `host:track-devices-l` is the primitive the whole app rests on. One long-lived socket; the server
 * pushes the entire long-form list on every change, so hot-plug detection and the readable `model:`
 * name arrive together. No polling, and none of the per-device `getprop` round trips the Swing app
 * pays just to fill in a name.
 */
object AdbDevices {

    /** one shot, for a caller that doesn't want to hold a subscription open. */
    suspend fun list(server: AdbServer): AdbOutcome<List<Device>> {
        val connection = AdbConnection.openOrNull(server)
            ?: return AdbOutcome.Err(AdbError.NO_SERVER, "adb isn't running")
        return connection.use {
            when (val outcome = it.request("host:devices-l")) {
                is AdbOutcome.Err -> outcome
                is AdbOutcome.Ok -> try {
                    AdbOutcome.Ok(parseDeviceList(it.readFrame()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AdbOutcome.Err(AdbError.IO, e.message ?: "the device list couldn't be read")
                }
            }
        }
    }

    /**
     * every change to the attached set, for as long as the flow is collected.
     *
     * the socket is meant to sit idle for hours between pushes, so there is deliberately no read
     * timeout on it. when the server goes away the flow emits an empty list and reconnects on the
     * [retryMs] timer — a desktop where adb was never started is the normal case, not an error.
     */
    fun track(server: AdbServer, retryMs: Long = 3_000): Flow<List<Device>> = channelFlow {
        while (currentCoroutineContext().isActive) {
            val connection = AdbConnection.openOrNull(server)
            if (connection == null) {
                send(emptyList())
                delay(retryMs)
                continue
            }
            try {
                when (connection.request("host:track-devices-l")) {
                    is AdbOutcome.Err -> {
                        send(emptyList())
                        delay(retryMs)
                    }
                    is AdbOutcome.Ok -> {
                        // every frame is the whole list, so nothing has to be merged
                        while (currentCoroutineContext().isActive) {
                            val devices = parseDeviceList(connection.readFrame())
                            AdbFeatures.retain(server, devices.map { it.serial }.toSet())
                            send(devices)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d("track: ${e.message}")
                send(emptyList())
                delay(retryMs)
            } finally {
                connection.close()
            }
        }
    }
}

/**
 * one `host:devices-l` payload.
 *
 * **the state is found by name and the serial is whatever precedes it.** A serial can contain
 * spaces — two phones registering the same mDNS name get ` (2)` appended — and adb pads the column
 * with spaces rather than a tab, so there is no separator to cut on. Splitting on whitespace and
 * taking column one reads `adb-RFCX20NB5TY-DBjIg4` as the serial and `(2)._adb-tls-connect._tcp` as
 * the state, and the phone sits in the list marked offline while `adb -s` works perfectly.
 *
 * The fields *after* the state can't be used to find it either — they're only mostly `key:value`,
 * since a USB device's devpath (`usb:1-1`) carries no key. See docs/ADB-TRANSPORTS.md.
 */
fun parseDeviceList(payload: String): List<Device> = payload.lineSequence()
    .map { it.trimEnd() }
    .filter { it.isNotBlank() }
    .mapNotNull { parseDeviceLine(it) }
    .toList()

internal fun parseDeviceLine(line: String): Device? {
    // longest label first so `no permissions` isn't shadowed by a shorter one
    val state = DeviceState.byLength.firstOrNull { state ->
        val at = line.indexOf(" ${state.label}")
        at > 0 && (line.length == at + state.label.length + 1 || line[at + state.label.length + 1] == ' ')
    } ?: return null

    val at = line.indexOf(" ${state.label}")
    val serial = line.substring(0, at).trim()
    if (serial.isEmpty()) return null

    val fields = line.substring(at + state.label.length + 1)
        .split(' ')
        .filter { it.isNotBlank() && it.contains(':') }
        .associate { field -> field.substringBefore(':') to field.substringAfter(':') }

    return Device(
        serial = serial,
        state = state,
        // adb writes a model with underscores where the real value has spaces
        model = fields["model"]?.replace('_', ' '),
        product = fields["product"],
        deviceName = fields["device"],
        transportId = fields["transport_id"],
    )
}
