package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * the Device Manager instances this app knows about, and connecting to one.
 *
 * The same shape as [WirelessManager] one level up: that remembers devices reached directly, this
 * remembers *desks*. A phone collects both — see docs/MOBILE-UI.md — and the device list shows the
 * ones it can't currently reach as well as the ones it can.
 *
 * **One at a time, for now.** [DeviceManager] holds a single transport, so connecting to a server
 * switches the whole app onto it. Reading several at once is a later step, and the thing that has to
 * change first is that a serial isn't unique across two of them.
 */
object ServerManager {
    private val log = LoggerConfig.createLogger("ServerManager")

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<SavedServer>> = _servers.asStateFlow()

    /** which saved server the app is reading, or null when it is on an adb server instead. */
    val activeKey: StateFlow<String?> = DeviceManager.transport
        .map { transport -> (transport as? RemoteHttpTransport)?.let { SavedServer.keyOf(it.baseUrl) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * point the app at [server].
     *
     * `/api/info` first: it proves the address and the token before anything is saved, so a typo
     * doesn't leave a dead entry in the list and an empty device table with no explanation. Only
     * once it answers does the transport become the app's.
     */
    suspend fun connect(server: SavedServer): Result<ServerInfo> {
        log.i("connect: ${server.key}")
        val transport = RemoteHttpTransport(server.baseUrl, server.token)
        val result = transport.info()
        result
            .onSuccess { info ->
                log.d("connect: ${server.key} is '${info.serverName}' with ${info.deviceCount} devices")
                remember(server.copy(name = info.serverName ?: server.name, lastSeenMs = DateUtils.nowMs()))
                // what DeviceManager.transportFromSettings() reads at the next app start
                SettingsManager.setString(AppPref.REMOTE_URL, server.baseUrl)
                SettingsManager.setString(AppPref.REMOTE_TOKEN, server.token)
                DeviceManager.useTransport(transport)
            }
            .onFailure {
                log.w("connect: ${server.key}: ${it.message}")
                transport.close()
            }
        return result
    }

    /** stop reading a server and fall back to whatever the settings otherwise describe. */
    fun disconnect() {
        log.i("disconnect: ${activeKey.value}")
        SettingsManager.setString(AppPref.REMOTE_URL, null)
        SettingsManager.setString(AppPref.REMOTE_TOKEN, null)
        DeviceManager.useTransport(DeviceManager.transportFromSettings())
    }

    /** add or update an entry, keyed on where the server is. */
    fun remember(server: SavedServer) {
        val existing = _servers.value.firstOrNull { it.key == server.key }
        if (existing == server) return
        save(_servers.value.filterNot { it.key == server.key } + server)
    }

    fun forget(key: String) {
        log.d("forget: $key")
        if (activeKey.value == key) disconnect()
        save(_servers.value.filterNot { it.key == key })
    }

    fun find(key: String): SavedServer? = _servers.value.firstOrNull { it.key == key }

    private fun save(list: List<SavedServer>) {
        // newest last, oldest dropped — the same rule the wireless list uses
        val trimmed = list.takeLast(SavedServer.MAX_SAVED)
        _servers.value = trimmed
        SettingsManager.setString(AppPref.SAVED_SERVERS, JsonUtils.toJson(trimmed))
    }

    private fun load(): List<SavedServer> =
        JsonUtils.stringToList(SettingsManager.getString(AppPref.SAVED_SERVERS))
}
