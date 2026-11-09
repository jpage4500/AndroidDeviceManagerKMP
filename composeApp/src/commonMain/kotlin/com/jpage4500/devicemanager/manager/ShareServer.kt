package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.crypto.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import kotlin.random.*

/** where Share Devices is in its life — Stopping is a state of its own because open sockets take a moment. */
enum class ShareStatus { STOPPED, STARTING, RUNNING, STOPPING }

data class ShareState(
    val status: ShareStatus = ShareStatus.STOPPED,
    /** what it is listening on; meaningful while [ShareStatus.RUNNING]. */
    val port: Int = 0,
    val token: String = "",
    /** what it calls itself to clients — `/api/info`'s `serverName`, and the connection string's name. */
    val name: String = "",
    /** why the last start failed, until the next one. */
    val error: String? = null,
) {
    val isRunning: Boolean get() = status == ShareStatus.RUNNING
}

/**
 * a computer or phone that has made a request, keyed on its address — the Swing app's
 * `RemoteClientInfo`.
 */
data class ShareClient(
    val ip: String,
    /** the `x-client-name` it sent, if it sent one. */
    val name: String?,
    val connectedMs: Long,
    val lastMs: Long,
    val requests: Int,
)

/** one address this machine has, and what kind of link it is on. */
data class LocalNetwork(
    val ip: String,
    /** the reverse-DNS name, when there is one that isn't just [ip] again. */
    val host: String?,
    /** Wi-Fi, Ethernet, VPN, Virtual — or null when the interface name says nothing. */
    val type: String?,
    /** the interface's own name: `en0`, `utun4`. */
    val label: String,
    /** a private-range address. Together with not being a VPN, what makes one a LAN address. */
    val isSiteLocal: Boolean,
) {
    /** the address other devices on the same Wi-Fi can reach — the one to put in a QR code. */
    val isLocalNetwork: Boolean get() = isSiteLocal && type != TYPE_VPN

    /** `Wi-Fi (en0)`. */
    val typeDesc: String get() = if (type.isNullOrEmpty()) label else "$type ($label)"

    companion object {
        const val TYPE_VPN = "VPN"
    }
}

/**
 * Share Devices: this desktop's devices, served to other copies of the app over HTTP.
 *
 * The protocol is the Swing app's (`AndroidDeviceManager/docs/SERVER_API.md`), endpoint for
 * endpoint, so a phone running this app, a Swing app, or `curl` can all read the same server — and
 * [RemoteHttpTransport] is already its client. The listening and the endpoints themselves are in
 * desktopMain behind [ShareHost]; this holds what the dialog shows and the rules every request
 * shares: the token, which devices are shared, and who has been asking.
 *
 * **Only this machine's devices are shared.** A device read through another server is left out of
 * every answer, as in the Swing app — two desks sharing with each other would otherwise advertise
 * each other's devices back and forth.
 *
 * Left running, it starts again at the next launch with the same port and token, so a phone that
 * scanned the code once keeps working. See docs/SHARE-SERVER.md.
 */
object ShareServer {
    private val log = LoggerConfig.createLogger("ShareServer")

    /** `RemoteHttpServer.VERSION` — what `/api/info` reports. */
    const val VERSION = "1.0"

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    // a start and a stop can be asked for back to back from the dialog; they are one at a time
    private val lifecycle = Mutex()

    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()

    private val _clients = MutableStateFlow<List<ShareClient>>(emptyList())
    val clients: StateFlow<List<ShareClient>> = _clients.asStateFlow()

    /** start it again if it was running when the app last closed. Called once, at launch. */
    fun autoStart() {
        if (!ShareHost.supported || !SettingsManager.getBoolean(AppPref.SHARE_ENABLED)) return
        val token = SettingsManager.getString(AppPref.SHARE_TOKEN)
        if (token.isNullOrBlank()) return
        scope.launch { start(savedPort(), token) }
    }

    /**
     * listen on [port], answering only to [token].
     *
     * Runs on this object's scope, not the caller's: a dialog closed mid-start would otherwise
     * cancel it between binding the port and saying so, and leave the state at Starting for good.
     *
     * @return null once it is running, or why it isn't
     */
    suspend fun start(port: Int, token: String): String? = scope.async { startLocked(port, token) }.await()

    private suspend fun startLocked(port: Int, token: String): String? = lifecycle.withLock {
        if (_state.value.isRunning) return@withLock null
        _state.value = _state.value.copy(status = ShareStatus.STARTING, error = null)
        val name = serverName()
        val error = ShareHost.start(port)
        if (error != null) {
            log.e("start: port $port: $error")
            _state.value = ShareState(error = error)
            return@withLock error
        }
        SettingsManager.setBoolean(AppPref.SHARE_ENABLED, true)
        SettingsManager.setInt(AppPref.SHARE_PORT, port)
        SettingsManager.setString(AppPref.SHARE_TOKEN, token)
        log.i("start: port $port as \"$name\"")
        _state.value = ShareState(status = ShareStatus.RUNNING, port = port, token = token, name = name)
        null
    }

    /** stop, and stay stopped at the next launch — someone pressed Stop Server. */
    suspend fun stop() = scope.async { stopLocked() }.await()

    private suspend fun stopLocked() = lifecycle.withLock {
        if (_state.value.status == ShareStatus.STOPPED) return@withLock
        SettingsManager.setBoolean(AppPref.SHARE_ENABLED, false)
        _state.value = _state.value.copy(status = ShareStatus.STOPPING)
        ShareHost.stop()
        _clients.value = emptyList()
        log.i("stop: stopped")
        _state.value = ShareState()
    }

    fun savedPort(): Int = SettingsManager.getInt(AppPref.SHARE_PORT)

    /** the token last used, so restarting the server doesn't strand a phone that already has it. */
    fun savedOrNewToken(): String =
        SettingsManager.getString(AppPref.SHARE_TOKEN)?.takeIf { it.isNotBlank() } ?: newToken()

    /** the name typed in the dialog, or null for the computer's own. */
    fun savedName(): String? = SettingsManager.getString(AppPref.SHARE_NAME)?.takeIf { it.isNotBlank() }

    fun saveName(name: String) = SettingsManager.setString(AppPref.SHARE_NAME, name.trim().ifEmpty { null })

    /** what this server calls itself: the saved name, else the computer's. */
    suspend fun serverName(): String = savedName() ?: ShareHost.hostName() ?: DEFAULT_NAME

    /**
     * sixteen letters and digits — the Swing app's token, from the secure source rather than
     * `Math.random()`, since it is the only thing between the LAN and every device on the desk.
     */
    fun newToken(): String {
        val bytes = secureRandomBytes(TOKEN_LENGTH) ?: ByteArray(TOKEN_LENGTH) { Random.nextInt().toByte() }
        return bytes.joinToString("") { TOKEN_CHARS[(it.toInt() and 0xff) % TOKEN_CHARS.length].toString() }
    }

    /** the `adm://` string a client pastes or scans to reach this server at [host]. */
    fun connectionString(host: String): String {
        val current = _state.value
        return SavedServer.toConnectionString(
            SavedServer(host = host, port = current.port, name = current.name, token = current.token)
        )
    }

    /** whether a request carrying [token] may be answered — every endpoint asks this first. */
    fun isAuthorized(token: String?): Boolean {
        val expected = _state.value.token
        if (!_state.value.isRunning || expected.isEmpty() || token == null) return false
        // the same work however early it differs, so a timing difference doesn't spell the token out
        if (token.length != expected.length) return false
        var diff = 0
        for (i in token.indices) diff = diff or (token[i].code xor expected[i].code)
        return diff == 0
    }

    /** every device this machine reaches itself — the list the server shares. */
    fun sharedDevices(): List<Device> = DeviceManager.devices.value.filter { isLocal(it) }

    /**
     * the shared device a client means by [serial].
     *
     * A bare serial is all the protocol has. Two of this desk's own connections could both hold an
     * `emulator-5554` — the first is the one a client gets, which is what the Swing app did too.
     */
    fun sharedDevice(serial: String?): Device? =
        serial?.let { s -> sharedDevices().firstOrNull { it.serial == s } }

    private fun isLocal(device: Device): Boolean =
        DeviceManager.connectionFor(device.id)?.let { it !is RemoteHttpTransport } == true

    /** note a request from [ip]; clients quiet for a day are dropped from the list. */
    fun trackClient(ip: String, name: String?) {
        val now = DateUtils.nowMs()
        _clients.update { list ->
            val existing = list.firstOrNull { it.ip == ip }
            val updated = existing?.copy(
                name = existing.name ?: name,
                lastMs = now,
                requests = existing.requests + 1,
            ) ?: ShareClient(ip = ip, name = name, connectedMs = now, lastMs = now, requests = 1)
            if (existing == null) log.i("trackClient: $ip ${name.orEmpty()}")
            (list.filter { it.ip != ip && now - it.lastMs < CLIENT_EXPIRY_MS } + updated)
                .sortedBy { it.connectedMs }
        }
    }

    private const val DEFAULT_NAME = "Device Manager"
    private const val TOKEN_LENGTH = 16
    private const val TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    // the Swing app's: a client gone for a day is not "connected" in any useful sense
    private const val CLIENT_EXPIRY_MS = 24 * 60 * 60 * 1000L
}
