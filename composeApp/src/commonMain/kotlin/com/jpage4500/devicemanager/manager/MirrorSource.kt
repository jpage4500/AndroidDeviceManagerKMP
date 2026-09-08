package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.*

/**
 * a mirrored screen: frames out, taps in.
 *
 * This is tier II of the three in docs/ADB-TRANSPORTS.md — poll `screencap`, send `input tap`. It
 * needs no video decoder on either platform and works over every transport, which is what makes it
 * the one worth having first. The Swing app's built-in mirror window is exactly this at a 250 ms
 * interval, and its `/screen` websocket ships the same frames to a remote instance.
 *
 * Real-time mirroring means running scrcpy's server on the device and decoding H.264 — MediaCodec on
 * Android, VideoToolbox on iOS. Deliberately not attempted here.
 */
class MirrorSource(
    private val deviceId: String,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val log = LoggerConfig.createLogger("MirrorSource")

    /** the newest frame, as PNG bytes. null until the first one lands. */
    private val _frame = MutableStateFlow<ByteArray?>(null)
    val frame: StateFlow<ByteArray?> = _frame.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * capture until the scope is cancelled.
     *
     * A frame is asked for only once the previous one has arrived, rather than on a fixed timer: a
     * device that takes 400 ms to answer would otherwise build a queue of requests it can never
     * catch up with.
     */
    suspend fun run() {
        log.d("run: $deviceId every ${intervalMs}ms")
        while (currentCoroutineContext().isActive) {
            val started = TimeSource.Monotonic.markNow()
            DeviceManager.screenshot(deviceId)
                .onSuccess {
                    _frame.value = it
                    _error.value = null
                }
                .onFailure { _error.value = it.message }
            val spent = started.elapsedNow().inWholeMilliseconds
            if (spent < intervalMs) delay(intervalMs - spent)
        }
    }

    suspend fun tap(x: Int, y: Int) = send(DeviceInput.tap(x, y))

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 200) =
        send(DeviceInput.swipe(x1, y1, x2, y2, durationMs))

    suspend fun text(value: String) = send(DeviceInput.text(value))

    suspend fun key(keyCode: Int) = send(DeviceInput.keyEvent(keyCode))

    private suspend fun send(command: String) {
        DeviceManager.sendInput(deviceId, command)
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 250L
    }
}
