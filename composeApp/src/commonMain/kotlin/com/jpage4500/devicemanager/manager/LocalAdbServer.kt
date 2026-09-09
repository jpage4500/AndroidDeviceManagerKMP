package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*

/**
 * an adb server running **inside this app**, from a binary shipped in the APK.
 *
 * The point of it is pairing, and pairing only. Android 11+ wireless pairing is SPAKE2 over a TLS
 * connection whose exporter output is mixed into the password, and neither the JDK nor Apple exposes
 * that exporter — so a pure-Kotlin pairing implementation cannot be written at all (see
 * docs/ADB-TRANSPORTS.md). Google's own `adb` binary has BoringSSL compiled into it and does the
 * whole handshake, so shipping *that* sidesteps the blocker rather than working around it.
 *
 * **Nothing new had to be written above this.** The binary is started with
 * `-L tcp:127.0.0.1:5037 server nodaemon`, which is an ordinary adb server on loopback — so
 * `AdbServerTransport` talks to it exactly as it talks to a desk's, and `PairingSession` sends it
 * `host:pair` exactly as before. The only difference the rest of the app sees is that a phone's
 * loopback address is no longer a dead end.
 *
 * Android only. iOS cannot execute a bundled binary, and a desktop already has a real adb — running
 * a second server there would fight the first one for every USB device, which is the whole workload
 * that matters.
 */
object LocalAdbServer {
    private val log = LoggerConfig.createLogger("LocalAdbServer")

    sealed interface State {
        data object Stopped : State

        data object Starting : State

        /** [version] is what `host:version` answered, so it is proof the socket is really up. */
        data class Running(val version: String) : State

        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value is State.Running

    /** whether starting one is worth offering at all — see the class comment for why not elsewhere. */
    val isAvailable: Boolean get() = AdbDaemon.isSupported && AdbDaemon.isBundled

    /** why [isAvailable] is false, for a screen that has to say something. */
    val unavailableReason: String
        get() = when {
            !AdbDaemon.isSupported -> "this platform can't run a bundled adb server"
            !AdbDaemon.isBundled -> "this build has no adb binary in it"
            else -> ""
        }

    /** the address it listens on: loopback only, so nothing off the phone can reach it. */
    val server = AdbServer.LOCAL

    private val mutex = Mutex()

    /**
     * start it and wait until it actually answers, then attach it as a connection.
     *
     * The wait is the point. `server nodaemon` returns nothing on success — it just runs — so the
     * only way to know it came up is to ask it, and the only useful answer is `host:version`. A
     * binary that exits immediately (wrong ABI, no exec permission) would otherwise look like a
     * server that was merely slow.
     */
    suspend fun start(): Result<String> = mutex.withLock {
        (_state.value as? State.Running)?.let { return@withLock Result.success(it.version) }

        if (!isAvailable) {
            val why = unavailableReason
            log.w("start: $why")
            _state.value = State.Failed(why)
            return@withLock Result.failure(IllegalStateException(why))
        }

        log.i("start: launching the bundled adb server on $server")
        _state.value = State.Starting
        if (!AdbDaemon.launch()) {
            val why = "the adb binary wouldn't start"
            log.e("start: $why")
            _state.value = State.Failed(why)
            return@withLock Result.failure(IllegalStateException(why))
        }

        val version = awaitVersion()
        if (version == null) {
            AdbDaemon.kill()
            val why = "the adb server didn't come up"
            log.e("start: $why")
            _state.value = State.Failed(why)
            return@withLock Result.failure(IllegalStateException(why))
        }

        log.i("start: up, protocol $version")
        _state.value = State.Running(version)
        // bundled = true is what makes this loopback address able to pair; see AdbServerTransport
        DeviceManager.addConnection(AdbServerTransport(bundled = true))
        Result.success(version)
    }

    /** stop it, and drop the connection that was reading it. */
    fun stop() {
        if (_state.value is State.Stopped) return
        log.i("stop")
        DeviceManager.removeConnection("adb://$server")
        AdbDaemon.kill()
        _state.value = State.Stopped
    }

    /** ask until it answers, the process dies, or [timeoutMs] is up. */
    private suspend fun awaitVersion(timeoutMs: Long = START_MS): String? =
        withTimeoutOrNull(timeoutMs) {
            var version: String? = null
            // a process that exited is the one failure not worth waiting out: a wrong-ABI or
            // non-executable binary would otherwise look like a server that was merely slow
            while (version == null && AdbDaemon.isAlive) {
                version = adbVersion(server).valueOrNull()
                if (version == null) delay(POLL_MS)
            }
            if (version == null) log.w("awaitVersion: the adb process exited")
            version
        }

    /** generous: the first run writes a fresh RSA key, which is hundreds of milliseconds of work. */
    private const val START_MS = 10_000L
    private const val POLL_MS = 250L
}

/**
 * the bundled `adb` process, per platform.
 *
 * Deliberately tiny — launch, kill, is it alive. Everything that can be decided without a process
 * lives in [LocalAdbServer], so the platform code is a `ProcessBuilder` and nothing else.
 */
expect object AdbDaemon {
    /** whether this platform can execute a bundled binary at all. */
    val isSupported: Boolean

    /** whether this particular build actually shipped one. */
    val isBundled: Boolean

    /** true once the process is running; false if it exited or was never started. */
    val isAlive: Boolean

    /** @return false when the process couldn't be launched — not when the server later fails */
    fun launch(): Boolean

    fun kill()
}
