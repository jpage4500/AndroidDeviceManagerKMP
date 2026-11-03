package com.jpage4500.devicemanager.transport

import com.jpage4500.devicemanager.data.*
import kotlinx.coroutines.flow.*

/**
 * how the app reaches devices.
 *
 * The one seam the whole port turns on. There are four ways in and they differ mainly in what they
 * can reach, not in what they can do — so everything above this interface is written once:
 *
 * | | reaches USB | needs a desktop |
 * |---|---|---|
 * | [AdbServerTransport] against `127.0.0.1:5037` | yes | it *is* the desktop, unless it is this app's own bundled adb |
 * | [AdbServerTransport] against a LAN address | yes | yes |
 * | [RemoteHttpTransport] against a Device Manager instance | yes | yes |
 * | [DirectDeviceTransport] against `adbd` on 5555 | no | no |
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
     * `logcat` as it arrives, a batch at a time.
     *
     * Here rather than in the log window because a batch has to come off *this* connection: with
     * two desks attached, a stream that found its own way to an adb server would read the wrong
     * `emulator-5554`. A transport with no way to stream refuses by saying nothing, which is what a
     * log window draws as an empty buffer.
     *
     * An adb server runs `logcat` on the device and this drains the socket; a Device Manager server
     * has already got the stream open and pushes it over `/ws/logs`, gzipped — see [remoteLogcat].
     * Neither reconnects on its own: a stream that ends is a log that has stopped, and the window
     * says so rather than resuming with a silent gap in the middle.
     */
    fun logcat(serial: String): Flow<LogBatch> = emptyFlow()

    /** whether [logcat] will produce anything — what a log window checks before it says "no logs". */
    val canStreamLogs: Boolean get() = false

    /**
     * whether [logcat] resolves pids to process names itself.
     *
     * logcat carries a pid and nothing else, so the App column's names have to come from somewhere;
     * with an adb server that is `ps` on a slow timer, run by the log window. A Device Manager
     * server has the map already and sends it down the log socket, so asking for it would be a
     * shell command on someone else's machine for an answer that arrived with the lines — the same
     * argument as [hasDeviceDetails].
     */
    val hasProcessNames: Boolean get() = false

    /** everything `getprop` knows. */
    suspend fun properties(serial: String): Result<Map<String, String>>

    /** a PNG of the device's screen. */
    suspend fun screenshot(serial: String): Result<ByteArray>

    /**
     * a still for a preview that refreshes on its own: the screen, or [ScreenStill.Off] when the
     * display is asleep, so a timer doesn't keep paying for PNGs of a black screen.
     *
     * by default a plain [screenshot] — a transport that can't ask about the display in the same
     * round trip never reports it off. The adb routes run [CMD_PREVIEW_STILL].
     */
    suspend fun previewStill(serial: String): Result<ScreenStill> =
        screenshot(serial).map { ScreenStill.Shot(it) }

    /**
     * the same screen, pushed rather than asked for, with input going back the same way.
     *
     * null where the only way to see a screen is to ask for one frame at a time, which is what
     * [com.jpage4500.devicemanager.manager.MirrorSource] falls back to. A Device Manager server has
     * a websocket for exactly this, and over a phone's link it is the whole difference between a
     * mirror and a slideshow: one connection instead of a request per frame, JPEG instead of a
     * full-size PNG, and a tap that costs no round trip of its own.
     *
     * [intervalMs] is what the far end is *asked* for — it keeps the clock, so frames arrive when
     * it has one rather than when this end next gets round to asking.
     */
    fun screenStream(serial: String, intervalMs: Long): ScreenStream? = null

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

/**
 * one batch off a log stream: the lines, and anything the far end has just said about the processes
 * that wrote them.
 *
 * A batch rather than a line, because a busy device prints thousands of lines a second and an
 * emission each would be thousands of recompositions — an adb server's socket is drained on a 100ms
 * flush, a Device Manager server sends every 500ms.
 *
 * [processes] is pid -> process name. Empty is the normal case and means "nothing new, keep what you
 * have"; a batch with names and no lines is the map arriving on its own, which is how `/ws/logs`
 * sends it.
 */
class LogBatch(
    val lines: List<String> = emptyList(),
    val processes: Map<String, String> = emptyMap(),
)

/**
 * one frame of a mirrored screen.
 *
 * [deviceWidth] and [deviceHeight] are the **device's** own pixels, which is not necessarily the
 * image's size: a Device Manager server scales the frame down at anything below its top quality and
 * says so in the frame header rather than in the JPEG. So a gesture is mapped against these, not
 * against the decoded bitmap. 0 means "whatever the image itself is", which is the answer a polled
 * `screencap` gives — that PNG is the screen, at size.
 */
class ScreenFrame(
    val image: ByteArray,
    val deviceWidth: Int = 0,
    val deviceHeight: Int = 0,
)

/**
 * a mirrored screen held open on one connection: frames out, input back in.
 *
 * Two directions on one socket, which is the point of it. The polled alternative asks for a whole
 * screenshot on a timer and posts every tap as a request of its own, so the tap queues behind a
 * frame nobody is waiting for any more — see [DeviceTransport.screenStream].
 */
interface ScreenStream {
    /** frames, for as long as this is collected; it ends when the far end does. */
    val frames: Flow<ScreenFrame>

    /**
     * one input event, up the connection the frames come down.
     *
     * Fire-and-forget, the way the far end treats it: nothing is acknowledged, and an event sent
     * while the socket is down is dropped rather than held — by the time it reconnected, whatever
     * was under the finger has moved.
     */
    suspend fun send(event: DeviceInput.Event)

    /**
     * how much picture to send, from here on.
     *
     * The far end keeps the clock and does the scaling, so this is a request rather than a setting:
     * a stream that has nothing to offer below its top quality keeps sending what it sends.
     */
    suspend fun setQuality(quality: ScreenQuality) = Unit
}

/**
 * how much of a device's screen a pushed frame carries.
 *
 * The three the Swing window's combo box has, by the names the server reads on the wire: the frame
 * is scaled to 1, ¾ or ½ and its JPEG re-encoded at 0.70, 0.50 or 0.30. The header still says the
 * *device's* size at every one of them, which is what keeps a tap landing where it was aimed — see
 * [ScreenFrame].
 *
 * Only a pushed stream has this. A polled `screencap` answers a full-size PNG and takes no argument
 * about it, so a mirror falling back to polling is a mirror at [HIGH] whatever is asked for.
 */
enum class ScreenQuality(val wire: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
}

/** the input events a mirrored screen sends back, in the one place both transports share. */
object DeviceInput {
    /**
     * a gesture or a key, as a value.
     *
     * A value rather than a command string because the two routes want different things: an adb
     * server takes an `input tap` line in a shell, a Device Manager server takes
     * `{"action":"input","type":"tap"}` on the socket its frames arrive on. Text differs by more
     * than syntax — the shell needs its spaces as `%s` and the server does that escaping itself, so
     * a string escaped for one arrives as literal `%s` on the other. So a gesture stays a value
     * until whatever is carrying it renders it.
     */
    sealed interface Event

    data class Tap(val x: Int, val y: Int) : Event

    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Int,
    ) : Event

    data class Text(val value: String) : Event

    data class Key(val keyCode: Int) : Event

    /**
     * a press held in place.
     *
     * adb has no long press of its own: it is a swipe that goes nowhere, slowly. [LONG_PRESS_MS] is
     * the Swing window's own figure — comfortably past Android's ~500ms threshold, so the gesture
     * still registers when the shell is slow to start it.
     */
    fun longPress(x: Int, y: Int, durationMs: Int = LONG_PRESS_MS) = Swipe(x, y, x, y, durationMs)

    const val LONG_PRESS_MS = 650

    /** [event] as the `adb shell` line that performs it — all the adb route can carry. */
    fun shell(event: Event): String = when (event) {
        is Tap -> "input tap ${event.x} ${event.y}"
        is Swipe -> "input swipe ${event.x1} ${event.y1} ${event.x2} ${event.y2} ${event.durationMs}"
        is Text -> "input text \"${shellText(event.value)}\""
        is Key -> "input keyevent ${event.keyCode}"
    }

    /**
     * a string as the argument to `input text`, inside the double quotes [shell] puts around it.
     *
     * A space has to go as `%s`, because `input text` splits its argument on whitespace. The other
     * four are the ones a shell still reads *inside* double quotes — and with a keyboard typing
     * straight through, a quote in what someone types would otherwise end the argument and leave
     * the rest of it as a command of its own.
     */
    private fun shellText(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("`", "\\`")
        .replace(" ", "%s")

    // the key codes the mirror window's buttons send
    const val KEY_HOME = 3
    const val KEY_BACK = 4
    const val KEY_POWER = 26
    const val KEY_APP_SWITCH = 187
    const val KEY_VOLUME_UP = 24
    const val KEY_VOLUME_DOWN = 25

    // and the two a keyboard sends that aren't text: `input text` carries neither
    const val KEY_ENTER = 66
    const val KEY_DEL = 67

    // the rest of the Input page's keys
    const val KEY_DPAD_UP = 19
    const val KEY_DPAD_DOWN = 20
    const val KEY_DPAD_LEFT = 21
    const val KEY_DPAD_RIGHT = 22
    const val KEY_TAB = 61
    const val KEY_ESCAPE = 111
    const val KEY_WAKEUP = 224
}

/** what [DeviceTransport.previewStill] saw: a PNG, or a display that's asleep. */
sealed interface ScreenStill {
    class Shot(val png: ByteArray) : ScreenStill
    data object Off : ScreenStill
}

private const val SCREEN_OFF_MARK = "SCREEN_OFF"

/**
 * one exec that asks the power service before paying for a capture: `SCREEN_OFF` when the display
 * is asleep or dozing, the `screencap` PNG otherwise.
 *
 * it matches *off* rather than *on*, so a build whose `dumpsys power` words it differently — or one
 * with no `grep` at all, before Android 6 — falls through to the screenshot instead of reporting a
 * lit screen as dark. `mWakefulness` is 4.2 and later, `mScreenOn` the older field. The grep runs on
 * the device so `dumpsys`'s tens of KB never cross the wire, and skipping the capture saves the PNG
 * encode, which is most of a `screencap -p`'s time.
 *
 * **not `grep -q`**: it stops reading at the first match, `dumpsys` then fails on the closed pipe
 * and prints "Failed to write while dumping service power: Broken pipe" — and `exec:` carries
 * stderr in the same stream, so the marker arrived behind that line (seen on a dozing Pixel 9).
 * A plain grep reads to the end, and `dumpsys`'s stderr is discarded besides.
 */
const val CMD_PREVIEW_STILL =
    "dumpsys power 2>/dev/null | grep -E 'mWakefulness=(Asleep|Dozing)|mScreenOn=false' >/dev/null" +
        " && echo $SCREEN_OFF_MARK || screencap -p"

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

/**
 * read what [CMD_PREVIEW_STILL] printed: a PNG by its signature, the marker on a line of its own,
 * and anything else a failure that says what came back instead.
 */
fun parsePreviewStill(bytes: ByteArray): Result<ScreenStill> {
    if (bytes.size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) {
        return Result.success(ScreenStill.Shot(bytes))
    }
    // short enough to be text and not a picture: look for the marker on any line
    val text = if (bytes.size < 4096) bytes.decodeToString() else ""
    if (text.lineSequence().any { it.trim() == SCREEN_OFF_MARK }) return Result.success(ScreenStill.Off)
    val what = text.trim().take(120).ifEmpty { "${bytes.size} bytes" }
    return Result.failure(IllegalStateException("not a screenshot: $what"))
}
