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
interface DeviceTransport {
    /** a name for this connection, as a picker would show it. */
    val label: String

    /** the attached set, re-pushed whenever it changes. */
    fun devices(): Flow<List<Device>>

    /** run a shell command and return what it printed. */
    suspend fun exec(serial: String, command: String): Result<String>

    /** everything `getprop` knows. */
    suspend fun properties(serial: String): Result<Map<String, String>>

    /** a PNG of the device's screen. */
    suspend fun screenshot(serial: String): Result<ByteArray>

    /** one folder on the device. */
    suspend fun listFiles(serial: String, path: String): Result<List<DeviceFile>>

    /** stop anything this transport is holding open. */
    fun close() {}
}

/** the input events a mirrored screen sends back, in the one place both transports share. */
object DeviceInput {
    fun tap(x: Int, y: Int) = "input tap $x $y"

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        "input swipe $x1 $y1 $x2 $y2 $durationMs"

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
