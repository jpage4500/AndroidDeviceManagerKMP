package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * the Device Manager instances this app knows about, and connecting to them.
 *
 * The same shape as [WirelessManager] one level up: that remembers devices reached directly, this
 * remembers *desks*. A phone collects both — see docs/MOBILE-UI.md — and the device list shows the
 * ones it can't currently reach as well as the ones it can.
 *
 * **As many at once as someone saves.** Each server is a [RemoteHttpTransport] of its own in
 * [DeviceManager]'s connection list, so connecting to a second desk adds its devices rather than
 * replacing the first desk's; a device is identified by its connection and its serial, which is what
 * makes two `emulator-5554`s on two desks two rows. Which servers were attached is remembered in
 * [SavedServer.connected], and [restore] brings them back at the next start.
 */
object ServerManager {
    private val log = LoggerConfig.createLogger("ServerManager")

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<SavedServer>> = _servers.asStateFlow()

    /** which saved servers the app is reading right now. */
    val connectedKeys: StateFlow<Set<String>> = DeviceManager.connections
        .map { attached ->
            attached.mapNotNullTo(mutableSetOf()) { transport ->
                (transport as? RemoteHttpTransport)?.let { SavedServer.keyOf(it.baseUrl) }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * start reading [server], alongside whatever else is attached.
     *
     * `/api/info` first: it proves the address and the token before anything is saved, so a typo
     * doesn't leave a dead entry in the list and an empty device table with no explanation. Only
     * once it answers does the connection join the list.
     */
    suspend fun connect(server: SavedServer): Result<ServerInfo> {
        // 0.0.0.0 loads the invented fleet instead of dialling anything — the same hook
        // DeviceManager.connect has, repeated here because this is now the only address field on a
        // phone. See FakeTransport
        if (server.host == FakeTransport.HOST) {
            DeviceManager.addConnection(FakeTransport())
            return Result.success(ServerInfo(serverName = "test devices"))
        }
        log.i("connect: ${server.key}")
        val transport = RemoteHttpTransport(server.baseUrl, server.token)
        val result = transport.info()
        result
            .onSuccess { info ->
                log.d("connect: ${server.key} is '${info.serverName}' with ${info.deviceCount} devices")
                remember(
                    server.copy(
                        name = info.serverName ?: server.name,
                        lastSeenMs = DateUtils.nowMs(),
                        connected = true,
                    )
                )
                // dropped first so a re-connect with a new token takes the new one: a duplicate id
                // is otherwise left alone, which would keep the token that has just been replaced
                DeviceManager.removeConnection(server.connectionId)
                DeviceManager.addConnection(transport)
            }
            .onFailure {
                log.w("connect: ${server.key}: ${it.message}")
                transport.close()
            }
        return result
    }

    /** stop reading one server, leaving every other connection alone. */
    fun disconnect(key: String) {
        log.i("disconnect: $key")
        find(key)?.let { remember(it.copy(connected = false)) }
        DeviceManager.removeConnection(SavedServer.connectionIdOf(key))
        // the same address left in Settings ▸ Connection would bring it back at the next start, so
        // a deliberate disconnect clears it — which is what the single-transport version did too
        if (SettingsManager.getString(AppPref.REMOTE_URL)?.let { SavedServer.keyOf(it) } == key) {
            SettingsManager.setString(AppPref.REMOTE_URL, null)
            SettingsManager.setString(AppPref.REMOTE_TOKEN, null)
            // and put the settings back on what they now describe — an adb server — rather than
            // leaving this install with no connection of its own at all
            DeviceManager.applySettings()
        }
    }

    /**
     * re-attach the servers that were attached when the app last closed.
     *
     * One at a time rather than all at once: each is an `/api/info` to a machine that may well be
     * asleep, and a phone opening five sockets on launch to find that out is the case this app is
     * meant to be careful about. A server that doesn't answer keeps its flag and is tried again next
     * time — a desk being off is not the same as being forgotten.
     */
    fun restore() {
        val attach = _servers.value.filter { it.connected }
        if (attach.isEmpty()) return
        log.d("restore: ${attach.size} servers")
        scope.launch { attach.forEach { connect(it) } }
    }

    /** add or update an entry, keyed on where the server is. */
    fun remember(server: SavedServer) {
        val existing = _servers.value.firstOrNull { it.key == server.key }
        if (existing == server) return
        save(_servers.value.filterNot { it.key == server.key } + server)
    }

    fun forget(key: String) {
        log.d("forget: $key")
        disconnect(key)
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
