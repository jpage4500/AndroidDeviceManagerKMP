package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.concurrent.Volatile
import kotlin.time.*

/**
 * a mirrored screen: frames out, taps in.
 *
 * This is tier II of the three in docs/ADB-TRANSPORTS.md — a still image at a few frames a second,
 * with `input tap` going back the other way. It needs no video decoder on either platform and works
 * over every transport, which is what makes it the one worth having first.
 *
 * There are two ways to get the frames and this picks whichever the device's connection has:
 *
 * - **pushed**, over a Device Manager server's `/ws/screen` — one connection, JPEG at an interval
 *   the server keeps, and input going back up the same socket. See [ScreenStream].
 * - **polled**, by asking for a screenshot and asking again, which is all an adb server can do:
 *   `screencap` is a command that answers a PNG, and there is nothing to subscribe to.
 *
 * Real-time mirroring is the tier above both — scrcpy's server on the device and H.264 decoded by
 * MediaCodec on Android and VideoToolbox on iOS. Deliberately not attempted here.
 */
class MirrorSource(
    private val deviceId: String,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val log = LoggerConfig.createLogger("MirrorSource")

    /** the newest frame. null until the first one lands. */
    private val _frame = MutableStateFlow<ScreenFrame?>(null)
    val frame: StateFlow<ScreenFrame?> = _frame.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // the pushed stream while there is one, so a gesture goes back up the socket its frames came
    // down rather than out as a shell command of its own
    @Volatile
    private var stream: ScreenStream? = null

    /** capture until the scope is cancelled. */
    suspend fun run() {
        val pushed = DeviceManager.screenStream(deviceId, intervalMs)
        if (pushed == null) {
            poll()
            return
        }
        stream = pushed
        try {
            collect(pushed)
        } finally {
            stream = null
        }
    }

    /**
     * frames as the connection pushes them, reconnecting for as long as the mirror is open.
     *
     * A dropped stream is dialled again: a phone changing wifi cell, or a laptop lid, is the usual
     * reason a live one goes, and it was working a moment ago.
     *
     * **A stream that delivers nothing at all, [FRAMELESS_TRIES] times running, is one to stop
     * trying.** The API completes the upgrade and *then* refuses — an unknown path, an unknown
     * serial and a bad token all arrive that way — so a server too old to have `/ws/screen` looks
     * exactly like a connection that dropped. Polling still works against that server, and a mirror
     * that works slowly beats one that reconnects forever. More than one try because the first
     * failure could as easily have been the network as the endpoint.
     */
    private suspend fun collect(stream: ScreenStream) {
        var frameless = 0
        while (currentCoroutineContext().isActive) {
            var frames = 0L
            try {
                stream.frames.collect { frame ->
                    frames++
                    _frame.value = frame
                    _error.value = null
                }
                log.d("collect: $deviceId closed after $frames frames")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("collect: $deviceId ${e.message}")
                _error.value = e.message
            }
            frameless = if (frames > 0L) 0 else frameless + 1
            if (frameless >= FRAMELESS_TRIES) {
                log.i("collect: $deviceId sent no frames in $frameless tries — polling instead")
                this.stream = null
                poll()
                return
            }
            delay(RECONNECT_MS)
        }
    }

    /**
     * ask for a frame, and ask again once it arrives.
     *
     * Once the previous one has landed rather than on a fixed timer: a device that takes 400ms to
     * answer would otherwise build a queue of requests it can never catch up with.
     */
    private suspend fun poll() {
        log.d("poll: $deviceId every ${intervalMs}ms")
        while (currentCoroutineContext().isActive) {
            val started = TimeSource.Monotonic.markNow()
            DeviceManager.screenshot(deviceId)
                .onSuccess {
                    // the PNG is the screen at size, so it is its own measure of the device
                    _frame.value = ScreenFrame(it)
                    _error.value = null
                }
                .onFailure { _error.value = it.message }
            val spent = started.elapsedNow().inWholeMilliseconds
            if (spent < intervalMs) delay(intervalMs - spent)
        }
    }

    suspend fun tap(x: Int, y: Int) = send(DeviceInput.Tap(x, y))

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 200) =
        send(DeviceInput.Swipe(x1, y1, x2, y2, durationMs))

    suspend fun longPress(x: Int, y: Int) = send(DeviceInput.longPress(x, y))

    suspend fun text(value: String) = send(DeviceInput.Text(value))

    suspend fun key(keyCode: Int) = send(DeviceInput.Key(keyCode))

    /** up the open socket where there is one, and as a shell command where there isn't. */
    private suspend fun send(event: DeviceInput.Event) {
        val stream = stream
        if (stream != null) stream.send(event) else DeviceManager.sendInput(deviceId, event)
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 250L

        /** how long to leave a stream that has dropped before dialling it again. */
        private const val RECONNECT_MS = 2_000L

        /** how many empty attempts say this connection won't stream at all — see [collect]. */
        private const val FRAMELESS_TRIES = 2
    }
}
