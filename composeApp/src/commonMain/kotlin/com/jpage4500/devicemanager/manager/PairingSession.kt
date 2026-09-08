package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.random.Random

/** where a pairing attempt has got to. */
sealed interface PairState {
    /** asking the adb server whether it is there, before anyone is asked to scan anything. */
    data object Checking : PairState

    /** the QR is up and nothing has scanned it yet. */
    data object Waiting : PairState

    /** a device answered the browse; the adb server is being asked to pair with it. */
    data class Found(val address: String) : PairState

    data class Paired(val message: String) : PairState

    /** [canRetry] when there is a device to try again with, rather than a dead server. */
    data class Failed(val message: String, val canRetry: Boolean = false) : PairState
}

/**
 * one round of Android 11+ wireless pairing, driven from a QR code this app shows.
 *
 * The shape of it, and why it is split the way it is:
 *
 * 1. this app invents a service name and a password and puts them in a QR code
 * 2. the other phone scans it from Settings ▸ Developer options ▸ Wireless debugging
 * 3. that phone starts advertising `_adb-tls-pairing._tcp` on a **random** port
 * 4. this app finds that advertisement over mDNS — the one part that is platform code
 * 5. an **adb server** is handed the address and the password and does the actual handshake
 *
 * Step 5 is the one that decides the design. Pairing is SPAKE2 followed by a TLS 1.3 session with
 * client certificates, and there is no pure-Kotlin SPAKE2 — so the phone cannot do it alone, and
 * this only works while the app is reading an adb server. That is what [DeviceTransport.canPair]
 * says, and it is checked before the button is offered rather than after it fails. The Swing app
 * takes exactly this route through jadb's `host:pair`. See docs/MOBILE-UI.md.
 */
class PairingSession {
    private val log = LoggerConfig.createLogger("PairingSession")

    /**
     * the service name and password the QR carries.
     *
     * `Random.Default` rather than a cryptographic one, which `commonMain` has no access to: this is
     * a 20-character code that exists for the few seconds a dialog is open, on a network an attacker
     * would already have to be inside. The Swing app uses `SecureRandom` here, and a platform seam
     * for one is the fix if this ever guards anything that outlives the dialog.
     */
    private val serviceName = "ADB_WIFI_" + randomString(NAME_LENGTH)
    private val password = randomString(PASSWORD_LENGTH)

    /** what to draw as a QR code — the format Android's own pairing screen expects. */
    val qrContent: String = "WIFI:T:ADB;S:$serviceName;P:$password;;"

    private val _state = MutableStateFlow<PairState>(PairState.Checking)
    val state: StateFlow<PairState> = _state.asStateFlow()

    // one attempt at a time: a device that is slow to answer gets browsed again, and pairing twice
    // with the same code fails the second time and looks like a real failure
    private var attempting = false

    /**
     * the last device the browse resolved, so a failure can be retried.
     *
     * mDNS announces a service once and then goes quiet — `onServiceFound` does not fire again for a
     * device that is already known. Without this, a pairing that failed for any transient reason
     * could only be retried by closing the target's dialog and reopening it.
     */
    private var lastFound: MdnsService? = null

    /**
     * browse until cancelled, pairing with whatever turns up.
     *
     * suspends forever on purpose, so a `LaunchedEffect` owns the lifetime and leaving the screen
     * stops the browse. The browse itself is started on the main thread because iOS schedules
     * `NSNetServiceBrowser` on the calling thread's run loop, and a background thread has none.
     */
    suspend fun run() {
        val scope = CoroutineScope(currentCoroutineContext())

        // ask first. a phone left on the default 127.0.0.1 has no adb server at all, and finding
        // that out after someone has scanned a code is the whole failure this avoids
        val transport = DeviceManager.pairer()
        if (transport == null) {
            log.w("run: nothing attached can pair")
            _state.value = PairState.Failed("no adb server to pair through")
            return
        }
        val reachable = transport.probe()
        if (reachable.isFailure) {
            val why = reachable.exceptionOrNull()?.message ?: "unreachable"
            log.w("run: $why")
            _state.value = PairState.Failed(why)
            return
        }
        log.d("run: ${reachable.getOrNull()} is up; waiting for a scan of $serviceName")
        _state.value = PairState.Waiting

        val browse = withContext(Dispatchers.Main) {
            browseMdns(MDNS_ADB_PAIRING) { service ->
                scope.launch { onFound(service) }
            }
        }
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { browse.close() }
            log.d("run: stopped")
        }
    }

    /**
     * pair with whatever answered, without checking the advertised name against ours.
     *
     * The name is in the QR so a device can be told apart from its neighbours, but the browse can
     * hand back an escaped or truncated version of it and a mismatch there would silently do
     * nothing. Trying is harmless: another session's device rejects our password, and that failure
     * is indistinguishable from any other.
     */
    private suspend fun onFound(service: MdnsService) {
        if (attempting || _state.value is PairState.Paired) return
        attempting = true
        lastFound = service
        _state.value = PairState.Found("${service.host}:${service.port}")
        log.i("onFound: pairing with ${service.name} at ${service.host}:${service.port}")

        val transport = DeviceManager.pairer()
        if (transport == null) {
            _state.value = PairState.Failed("no adb server to pair through")
            attempting = false
            return
        }
        transport.pair(service.host, service.port, password)
            .onSuccess {
                log.i("onFound: paired: $it")
                _state.value = PairState.Paired(it)
            }
            .onFailure {
                log.w("onFound: ${it.message}")
                _state.value = PairState.Failed(it.message ?: "pairing failed", canRetry = true)
                attempting = false
            }
    }

    /** try the device that was found again — see [lastFound] for why the browse can't do it. */
    suspend fun retry() {
        val service = lastFound ?: return
        attempting = false
        onFound(service)
    }

    private fun randomString(length: Int): String =
        CharArray(length) { ALPHABET[Random.nextInt(ALPHABET.length)] }.concatToString()

    private companion object {
        // the Swing app's own lengths, and its alphabet
        const val NAME_LENGTH = 20
        const val PASSWORD_LENGTH = 20
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
