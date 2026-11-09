package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.adb.device.*
import com.jpage4500.devicemanager.adb.pairing.*
import com.jpage4500.devicemanager.crypto.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
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

    /**
     * paired, and now being dialled.
     *
     * A step of its own because pairing does **not** attach the device — see [connectPaired].
     */
    data object Connecting : PairState

    /**
     * paired *and* connected, which is when the device actually appears in the list.
     *
     * [deviceId] is null on the adb-server route: that connection carries a whole fleet and the
     * device arrives in its next `track-devices` push, so there is no one row to point at.
     */
    data class Paired(val message: String, val deviceId: String? = null) : PairState

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
 * 5. the address and the password go into the actual handshake
 *
 * Step 5 is the one that decides the design, and it has two answers:
 *
 * - **Android does it itself** — [AdbPairing], over `crypto/Spake2` and a TLS keying-material
 *   exporter reached through the platform's own Conscrypt. No server, no computer, nothing bundled.
 * - **everything else hands it to an adb server** through `host:pair`, which is the route the Swing
 *   app takes and the only one iOS can ever have: no Apple TLS API exports keying material.
 *
 * [canPairDirectly] chooses. Either way the choice is made before a code is drawn rather than after
 * a scan of one fails — that is what [DeviceTransport.canPair] is for on the server route. See
 * docs/ADB-TRANSPORTS.md.
 */
class PairingSession {
    private val log = LoggerConfig.createLogger("PairingSession")

    /**
     * the service name and password the QR carries.
     *
     * The password is the **only** secret protecting the exchange, so it comes from the platform's
     * cryptographic generator now that [secureRandomBytes] exists — `Random.Default` is explicitly
     * not one, and a predictable code is a pairing anyone on the network can complete. The name is
     * only a label and is left on the cheap generator.
     */
    private val serviceName = "ADB_WIFI_" + randomString(NAME_LENGTH)
    private val password = secureString(PASSWORD_LENGTH)

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
     * the device's own guid, once pairing has reported it.
     *
     * Also the marker that pairing is *done*: a code can only be paired with once, so a retry after
     * this is set must re-dial rather than start again — see [retry].
     */
    private var pairedGuid: String? = null

    /** the device that was attached, so the screen can open it; null on the adb-server route. */
    private var connectedId: String? = null

    /**
     * browse until cancelled, pairing with whatever turns up.
     *
     * suspends forever on purpose, so a `LaunchedEffect` owns the lifetime and leaving the screen
     * stops the browse. The browse itself is started on the main thread because iOS schedules
     * `NSNetServiceBrowser` on the calling thread's run loop, and a background thread has none.
     */
    suspend fun run() {
        val scope = CoroutineScope(currentCoroutineContext())

        // ask first, whichever route this is: finding out there is nothing to pair through *after*
        // someone has scanned a code is the whole failure this avoids
        if (!checkReady()) return
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
     * whether there is anything to pair through, asked before a code is drawn.
     *
     * The two routes fail for different reasons and neither is worth discovering late: pairing
     * ourselves needs a key, and pairing through a server needs one that answers.
     */
    private suspend fun checkReady(): Boolean {
        if (canPairDirectly) {
            if (AdbIdentity.keyPair() == null) {
                log.e("checkReady: no adb key to pair with")
                _state.value = PairState.Failed("this app has no key to pair with")
                return false
            }
            log.d("checkReady: pairing directly; waiting for a scan of $serviceName")
            return true
        }

        val transport = DeviceManager.pairer()
        if (transport == null) {
            log.w("checkReady: nothing attached can pair")
            _state.value = PairState.Failed("no adb server to pair through")
            return false
        }
        val reachable = transport.probe()
        if (reachable.isFailure) {
            val why = reachable.exceptionOrNull()?.message ?: "unreachable"
            log.w("checkReady: $why")
            _state.value = PairState.Failed(why)
            return false
        }
        log.d("checkReady: ${reachable.getOrNull()} is up; waiting for a scan of $serviceName")
        return true
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

        if (canPairDirectly) pairOurselves(service) else pairThroughServer(service)
    }

    /**
     * pair with **our own key**, so that we are the one the device ends up trusting.
     *
     * That is the whole reason this replaced the route through a bundled adb server rather than
     * sitting beside it: `adbd_tls_verify_cert` matches on the public key inside the certificate, so
     * whoever pairs is the only one who can subsequently connect. A server pairing on this app's
     * behalf hands over *its* key and leaves the app still unable to reach the device.
     */
    private suspend fun pairOurselves(service: MdnsService) {
        val key = AdbIdentity.keyPair()
        if (key == null) {
            _state.value = PairState.Failed("this app has no key to pair with")
            attempting = false
            return
        }
        val outcome = AdbPairing(key, AdbIdentity.name())
            .pair(service.host, service.port, password)
        when (outcome) {
            is AdbOutcome.Err -> {
                log.w("pairOurselves: ${outcome.message}")
                _state.value = PairState.Failed(outcome.message, canRetry = true)
                attempting = false
            }
            is AdbOutcome.Ok -> {
                // the device answers with its own guid, which is exactly what names its connect
                // service over mDNS — so there is no sentence to parse a guid out of here
                pairedGuid = outcome.value.text().takeIf { it.isNotBlank() }
                log.i("pairOurselves: paired with ${pairedGuid ?: "a device"}")
                connectPaired(null)
            }
        }
    }

    private suspend fun pairThroughServer(service: MdnsService) {
        val transport = DeviceManager.pairer()
        if (transport == null) {
            _state.value = PairState.Failed("no adb server to pair through")
            attempting = false
            return
        }
        transport.pair(service.host, service.port, password)
            .onSuccess { reply ->
                log.i("pairThroughServer: paired: $reply")
                pairedGuid = guidOf(reply)
                connectPaired(transport)
            }
            .onFailure {
                log.w("pairThroughServer: ${it.message}")
                _state.value = PairState.Failed(it.message ?: "pairing failed", canRetry = true)
                attempting = false
            }
    }

    /**
     * dial the device that was just paired, which is what actually puts it in the list.
     *
     * **Pairing does not attach anything.** `host:pair` exchanges keys and stops there; the device
     * is then reachable on a *different* random port, the one it advertises as
     * `_adb-tls-connect._tcp`. `adb` on a desktop never has to be told this because the server
     * discovers that service itself and auto-connects — but its mDNS runs inside the server process,
     * and inside an app that needs a multicast lock this app doesn't hold. Since the browse here
     * already works, it is asked directly instead of relying on the server's own.
     *
     * Matched on the guid `host:pair` reported, because that guid *is* the service's name — with two
     * devices paired, connecting to whichever answered first would attach the wrong one.
     */
    private suspend fun connectPaired(transport: DeviceTransport?) {
        _state.value = PairState.Connecting
        val guid = pairedGuid
        log.d("connectPaired: looking for ${guid ?: "any device"} on $MDNS_ADB_CONNECT_TLS")

        val services = awaitConnectServices(guid)
        if (services.isEmpty()) {
            log.w("connectPaired: $guid never advertised a connect port")
            // paired is worth saying: the trust is established and survives, so a retry only has to
            // dial rather than pair again
            _state.value = PairState.Failed("paired, but it didn't answer — try again", canRetry = true)
            attempting = false
            return
        }

        // **every match is tried, not just the first.** One device commonly advertises more than one
        // connect record — a registration it has already abandoned plus the live one — and mDNS
        // disambiguates them by appending ` (2)` to the name rather than replacing anything. The
        // stale one refuses instantly, so working through them costs nothing and dialling only the
        // exact-name match dials the dead one about half the time
        var failure: Throwable? = null
        for (service in services) {
            log.i("connectPaired: trying ${service.host}:${service.port}")
            val result = dial(transport, service)
            result.onSuccess {
                log.i("connectPaired: $it")
                _state.value = PairState.Paired(it, connectedId)
                return
            }
            result.onFailure { error ->
                log.w("connectPaired: ${service.port}: ${error.message}")
                // keep the most informative failure: a refused connect says only that this record
                // was stale, where anything else is the actual reason the device won't have us
                if (failure == null || error.isRefused().not()) failure = error
            }
        }

        _state.value = PairState.Failed(
            failure?.message ?: "paired, but couldn't connect",
            canRetry = true,
        )
        attempting = false
    }

    /**
     * connect to one advertised address.
     *
     * A null [transport] means we paired ourselves, so we hold the key the device now trusts and
     * must be the one to dial it — a server asked to connect would present its own key and be
     * refused. [DirectDeviceTransport] reaches the TLS port through the `STLS` upgrade.
     */
    private suspend fun dial(transport: DeviceTransport?, service: MdnsService): Result<String> {
        if (transport != null) return transport.connect(service.host, service.port)
        val direct = DirectDeviceTransport(service.host, service.port)
        return direct.probe()
            .onSuccess {
                DeviceManager.addConnection(direct)
                connectedId = direct.deviceId
            }
            .onFailure { direct.close() }
    }

    /** a connect that was refused outright, which is what a stale mDNS record looks like. */
    private fun Throwable.isRefused(): Boolean =
        (this as? AdbException)?.reason == AdbError.DISCONNECTED

    /**
     * every `_adb-tls-connect._tcp` service belonging to [guid], in the order they were announced.
     *
     * Waits for the first, then keeps listening for [SETTLE_MS] — a device with a stale record
     * announces both, and the useful one is often the second. A channel rather than a list because
     * the callback arrives on whichever thread the platform resolves on.
     *
     * The browse is started and stopped on the main thread for the same reason the pairing one is:
     * iOS schedules `NSNetServiceBrowser` on the calling thread's run loop.
     */
    private suspend fun awaitConnectServices(
        guid: String?,
        timeoutMs: Long = CONNECT_BROWSE_MS,
    ): List<MdnsService> {
        val announced = Channel<MdnsService>(Channel.UNLIMITED)
        val browse = withContext(Dispatchers.Main) {
            browseMdns(MDNS_ADB_CONNECT_TLS) { service ->
                if (service.belongsTo(guid)) announced.trySend(service)
            }
        }
        return try {
            val first = withTimeoutOrNull(timeoutMs) { announced.receive() } ?: return emptyList()
            val all = mutableListOf(first)
            // drain the rest of the window; the loop only ends because the timeout cancels it
            withTimeoutOrNull(SETTLE_MS) {
                for (service in announced) all += service
            }
            all.distinctBy { "${it.host}:${it.port}" }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { browse.close() }
        }
    }

    /**
     * whether an announced service is this device's.
     *
     * The ` (2)` suffix is mDNS resolving a name collision — the same shape `parseDeviceList` deals
     * with on the adb server's own list — so a suffixed name is *this* device, not another one.
     */
    private fun MdnsService.belongsTo(guid: String?): Boolean =
        guid == null || name == guid || name.startsWith("$guid (")

    /** `Successfully paired to 1.2.3.4:5555 [guid=adb-XXXX]` — the guid is the mDNS service name. */
    private fun guidOf(reply: String): String? = reply
        .substringAfter("[guid=", "")
        .substringBefore(']', "")
        .takeIf { it.isNotBlank() }

    /**
     * try again — which means different things depending on how far it got.
     *
     * Once [pairedGuid] is set the pairing succeeded and only the dial failed, and pairing a second
     * time with the same code would be refused; so that case re-dials. See [lastFound] for why the
     * browse can't drive a retry itself.
     */
    suspend fun retry() {
        if (pairedGuid != null) {
            // paired already, so only the dial failed; null keeps the direct route direct
            connectPaired(if (canPairDirectly) null else DeviceManager.pairer() ?: return)
            return
        }
        val service = lastFound ?: return
        attempting = false
        onFound(service)
    }

    private fun randomString(length: Int): String =
        CharArray(length) { ALPHABET[Random.nextInt(ALPHABET.length)] }.concatToString()

    /**
     * the same alphabet, from the platform's cryptographic generator.
     *
     * The modulo is a bias, and a deliberate one: 62 does not divide 256, so the first 8 characters
     * of the alphabet are very slightly likelier. Over 20 characters that costs a fraction of a bit
     * against a code that lives for seconds, and rejection sampling here would be ceremony. Falls
     * back rather than failing, because a weaker code still beats offering no pairing at all.
     */
    private fun secureString(length: Int): String {
        val bytes = secureRandomBytes(length) ?: return randomString(length)
        return CharArray(length) { ALPHABET[(bytes[it].toInt() and 0xFF) % ALPHABET.length] }
            .concatToString()
    }

    private companion object {
        // the Swing app's own lengths, and its alphabet
        const val NAME_LENGTH = 20
        const val PASSWORD_LENGTH = 20
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** a device re-advertises its connect port right after pairing, but not instantly. */
        const val CONNECT_BROWSE_MS = 10_000L

        /** how long to keep listening after the first match, to catch a live record behind a stale one. */
        const val SETTLE_MS = 1_500L
    }
}
