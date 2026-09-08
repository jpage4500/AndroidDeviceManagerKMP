package com.jpage4500.devicemanager.transport

import com.jpage4500.devicemanager.data.*
import kotlinx.coroutines.flow.*

/**
 * how the app reaches devices.
 *
 * The one seam the whole port turns on. There are three ways in and they differ mainly in what they
 * can reach, not in what they can do — so everything above this interface is written once:
 *
 * | | reaches USB | needs a desktop |
 * |---|---|---|
 * | [AdbServerTransport] against `127.0.0.1:5037` | yes | it *is* the desktop |
 * | [AdbServerTransport] against a LAN address | yes | yes |
 * | [RemoteHttpTransport] against a Device Manager instance | yes | yes |
 * | direct to `adbd` on 5555 (not yet written) | no | no |
 *
 * See docs/ADB-TRANSPORTS.md for why the desktop build must keep the 5037 path: `adbd` is reachable
 * over TCP from anywhere, but a **USB** device is claimed exclusively by whichever adb server is
 * running, and 45 devices on hubs is the workload that matters.
 */
/**
 * which of the routes above the app is told to take.
 *
 * [AUTO] is what every install starts on and is the behaviour that predates this setting: a remote
 * URL, if one is filled in, otherwise the adb server. The other two are an override, for a desktop
 * that has both configured and wants to switch between them without clearing a field to do it.
 *
 * Stored in `AppPref.TRANSPORT`. It does not add a route — a choice with nothing filled in behind
 * it still falls back to the adb server, since a transport that can't reach anything is worse than
 * the wrong one.
 */
enum class TransportChoice { AUTO, ADB_SERVER, REMOTE }

interface DeviceTransport {
    /**
     * what this connection *is*, as a URI: `adb://10.0.0.4:5037`, `adm://imac.local:8765`.
     *
     * The app can hold several connections at once, so this is what tells them apart — a device's
     * identity is this plus its serial ([com.jpage4500.devicemanager.data.Device.id]), because a
     * serial alone is not unique across two desks. Derived from the address rather than minted, so
     * the same server reached twice is one connection and not two.
     *
     * **No `/` past the scheme**: it is the separator a device id is split back apart on.
     */
    val id: String

    /** a name for this connection, as a picker would show it. */
    val label: String

    /** the attached set, re-pushed whenever it changes. */
    fun devices(): Flow<List<Device>>

    /**
     * whether [devices] already carries everything a row shows.
     *
     * An adb server's `host:devices-l` carries a model and nothing else, so battery, disk, carrier
     * and the rest have to be asked for device by device — that is what
     * [com.jpage4500.devicemanager.manager.DeviceManager.fetchDetails] is for. A Device Manager
     * server has **already done that work** and puts the answers in `/api/devices`, so asking again
     * is two shell commands per device per refresh for data that arrived with the list, run on
     * someone else's machine. False here, true there.
     */
    val hasDeviceDetails: Boolean get() = false

    /** run a shell command and return what it printed. */
    suspend fun exec(serial: String, command: String): Result<String>

    /**
     * `logcat` as it arrives, a batch of lines at a time.
     *
     * Here rather than in the log window because a batch has to come off *this* connection: with
     * two desks attached, a stream that found its own way to an adb server would read the wrong
     * `emulator-5554`. A transport with no way to stream refuses by saying nothing, which is what a
     * log window draws as an empty buffer.
     */
    fun logcat(serial: String): Flow<List<String>> = emptyFlow()

    /** whether [logcat] will produce anything — what a log window checks before it says "no logs". */
    val canStreamLogs: Boolean get() = false

    /** everything `getprop` knows. */
    suspend fun properties(serial: String): Result<Map<String, String>>

    /** a PNG of the device's screen. */
    suspend fun screenshot(serial: String): Result<ByteArray>

    /** one folder on the device. */
    suspend fun listFiles(serial: String, path: String): Result<List<DeviceFile>>

    /**
     * one file off the device, whole.
     *
     * whole rather than streamed on purpose: the explorer downloads to the app's own folder and
     * nothing else pulls a file yet, so a `ByteArray` is enough and a channel would be ceremony.
     * Revisit when something needs to pull a video.
     */
    suspend fun pullFile(serial: String, path: String): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("$label can't download a file"))

    /** put a file onto the device. [mode] is the POSIX permission bits it lands with. */
    suspend fun pushFile(
        serial: String,
        bytes: ByteArray,
        path: String,
        mode: Int = 0b110_100_100,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("$label can't upload a file"))

    /**
     * pair with a device over the network.
     *
     * only an adb server can do this — a remote instance manages its own devices — so the default
     * refuses rather than pretending.
     */
    suspend fun connect(host: String, port: Int): Result<String> =
        Result.failure(UnsupportedOperationException("$label can't connect to a device"))

    suspend fun disconnect(serial: String): Result<String> =
        Result.failure(UnsupportedOperationException("$label can't disconnect a device"))

    /**
     * whether [connect] will do anything — which connection an address typed into Connect goes to.
     *
     * Only an adb server can dial a device: it holds the RSA key and does the handshake. A Device
     * Manager instance manages its own devices and has no endpoint for it, which is why this is a
     * question worth asking rather than something to find out from a failure.
     */
    val canConnect: Boolean get() = false

    /**
     * pair with a device showing its wireless-debugging code, at the address it advertises.
     *
     * Only an adb server can do this, which is why it is a transport method rather than something
     * the pairing screen does for itself: the handshake is SPAKE2 plus a TLS 1.3 client certificate
     * and no pure-Kotlin implementation of it exists, so the phone finds the device and the server
     * does the cryptography. A transport with no server behind it refuses rather than pretending.
     */
    suspend fun pair(host: String, port: Int, code: String): Result<String> =
        Result.failure(UnsupportedOperationException("$label can't pair with a device"))

    /** whether [pair] will do anything — what the pairing screen checks before offering itself. */
    val canPair: Boolean get() = false

    /**
     * is whatever is at the other end actually answering, and what does it call itself.
     *
     * Cheap by contract: a screen calls this to fail *before* asking someone to do something, rather
     * than after. The pairing screen is the reason it exists — finding out there is no adb server
     * only once a QR has been scanned and a device found is a minute of someone's time for a failure
     * that was knowable up front.
     */
    suspend fun probe(): Result<String> = Result.success(label)

    /** reboot the device. */
    suspend fun reboot(serial: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("$label can't reboot a device"))

    /** stop anything this transport is holding open. */
    fun close() {}
}

/** the input events a mirrored screen sends back, in the one place both transports share. */
object DeviceInput {
    fun tap(x: Int, y: Int) = "input tap $x $y"

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        "input swipe $x1 $y1 $x2 $y2 $durationMs"

    /**
     * a press held in place.
     *
     * adb has no long press of its own: it is a swipe that goes nowhere, slowly. [LONG_PRESS_MS] is
     * the Swing window's own figure — comfortably past Android's ~500ms threshold, so the gesture
     * still registers when the shell is slow to start it.
     */
    fun longPress(x: Int, y: Int, durationMs: Int = LONG_PRESS_MS) = swipe(x, y, x, y, durationMs)

    const val LONG_PRESS_MS = 650

    /** a space has to be sent as `%s`; `input text` splits its argument on whitespace. */
    fun text(value: String) = "input text \"${value.replace(" ", "%s")}\""

    fun keyEvent(keyCode: Int) = "input keyevent $keyCode"

    // the key codes the mirror window's buttons send
    const val KEY_HOME = 3
    const val KEY_BACK = 4
    const val KEY_POWER = 26
    const val KEY_APP_SWITCH = 187
    const val KEY_VOLUME_UP = 24
    const val KEY_VOLUME_DOWN = 25
}
