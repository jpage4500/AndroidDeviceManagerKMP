package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * whether anyone is looking at the app — the one signal everything that holds a socket hangs off.
 *
 * A backgrounded phone keeps running its coroutines. Compose does not stop composing when the screen
 * goes off, a `LaunchedEffect` is not cancelled, and a manager's own `scope` has never heard of the
 * activity — so the device tracker went on polling a Device Manager server, and a mirror went on
 * asking for a `screencap`, for a screen nobody was looking at. That is what this stops.
 *
 * Everything below it collects [isForeground] through [whileForeground] rather than reading the flag
 * for itself, so "stop on the way out, start again on the way back" is written once.
 *
 * **Permanently true on the desktop.** A window that is minimised — or gone entirely, which is what
 * `MINIMIZE_TO_TRAY` is for — is still managing 45 devices. See the lifecycle section of
 * docs/MOBILE-UI.md for what each transport does on the way out and back.
 */
object AppLifecycle {
    private val log = LoggerConfig.createLogger("AppLifecycle")

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _isForeground = MutableStateFlow(true)

    /** false while the app is in the background, when nothing should be opening a socket. */
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private var resumeJob: Job? = null

    /**
     * back on screen: drop what is left of the old sockets, *then* let the collectors go.
     *
     * The order is the point. [evictIdleConnections] is what keeps the first request of the new
     * session off a half-open keep-alive socket — one the wifi re-associate left looking perfectly
     * healthy, which stalls for ~14s while TCP exhausts its retransmits. Evicting alongside the flag
     * rather than before it is a race the first request loses.
     */
    fun onForeground() {
        if (_isForeground.value) return
        log.i("onForeground: reopening connections")
        resumeJob?.cancel()
        resumeJob = scope.launch {
            evictIdleConnections()
            _isForeground.value = true
        }
    }

    /**
     * off screen: the flag first, so every collector is on its way down before the sockets close.
     *
     * The eviction is best-effort — it only closes *idle* connections, so a poll that was already in
     * flight keeps its socket and hands it back to the pool a moment later. Which is the other half
     * of why [onForeground] evicts as well: this one releases what it can now, that one guarantees a
     * clean pool before anything is asked of it.
     */
    fun onBackground() {
        resumeJob?.cancel()
        if (!_isForeground.value) return
        log.i("onBackground: closing connections")
        _isForeground.value = false
        scope.launch { evictIdleConnections() }
    }
}

/**
 * register the platform's foreground/background notifications. called once, from `initApp`.
 *
 * A function rather than an `expect object` holding the state: the state is the same everywhere and
 * only the plumbing differs, so [AppLifecycle] stays in `commonMain` where it can be read.
 */
expect fun observeAppLifecycle()

/**
 * run [block] while the app is on screen, cancelling it on the way out and starting it again on the
 * way back.
 *
 * Suspends forever, so a `launch` or a `LaunchedEffect` owns the lifetime exactly as it did before
 * this existed — the only change at a call site is the wrapper around the body. On the desktop the
 * flag never moves, so [block] is entered once and never cancelled.
 */
suspend fun whileForeground(block: suspend () -> Unit) {
    AppLifecycle.isForeground.collectLatest { foreground ->
        if (foreground) block()
    }
}
