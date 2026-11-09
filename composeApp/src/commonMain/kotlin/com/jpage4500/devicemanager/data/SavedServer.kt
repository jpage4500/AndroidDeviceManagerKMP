package com.jpage4500.devicemanager.data

import com.jpage4500.devicemanager.util.*
import kotlinx.serialization.*
import kotlin.io.encoding.*

/**
 * a Device Manager instance this app has been pointed at.
 *
 * the mirror of the Swing app's `RemoteServerConfig`, minus the colour and the UUID: a server is
 * identified by *where* it is, so `host:port` is the key and nothing has to mint an id. Kept in this
 * app's own settings, because the whole point of the entry is to reach a server that isn't
 * connected yet — the same reason [WirelessDevice] is stored rather than derived.
 */
@Serializable
data class SavedServer(
    val host: String,
    val port: Int = DEFAULT_PORT,
    /** what the server calls itself — `/api/info`'s `serverName`, not something anyone types. */
    val name: String? = null,
    val token: String = "",
    val lastSeenMs: Long = 0,
    /**
     * whether the app was reading this server when it last closed.
     *
     * saved rather than derived because it is what brings a desk back at the next start: several
     * servers can be attached at once, so "which ones" is a real question the settings have to
     * answer. See [com.jpage4500.devicemanager.manager.ServerManager].
     */
    val connected: Boolean = false,
) {
    /** the Swing app's own rule: a bare host gets a scheme, a host that brought one keeps it. */
    val baseUrl: String
        get() = if (host.startsWith("http")) "$host:$port" else "http://$host:$port"

    /**
     * `host:port`, always without a scheme.
     *
     * derived from [baseUrl] rather than from [host] so that someone who typed `http://10.0.0.2`
     * and someone who typed `10.0.0.2` end up with the same entry — otherwise the saved row and the
     * live transport disagree about which server is the connected one.
     */
    val key: String get() = keyOf(baseUrl)

    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: key

    /** what [com.jpage4500.devicemanager.manager.DeviceManager] knows this server's connection as. */
    val connectionId: String get() = connectionIdOf(key)

    companion object {
        fun keyOf(baseUrl: String): String =
            baseUrl.removePrefix("https://").removePrefix("http://")

        /**
         * the id a connection to this server has, without having to build one to ask it.
         *
         * `server://` rather than [PREFIX]'s `adm://`, which already means something else here: a
         * connection string is base64 of the whole entry, and two things that read the same and are
         * not the same is a bug waiting for someone in a log file.
         */
        fun connectionIdOf(key: String): String = "server://$key"

        /** `RemoteServerManager.DEFAULT_PORT` in the Swing app. */
        const val DEFAULT_PORT = 8765

        /** how many are remembered. a phone collects desks, where a dialog's history list didn't. */
        const val MAX_SAVED = 20

        private const val PREFIX = "adm://"

        /**
         * read a "Copy Connection String" string.
         *
         * `adm://` followed by base64 of `{"host","port","token","name"}` — the Swing app's
         * `RemoteConnectionUtils` format, and the reason this class can be filled in from the
         * clipboard. A 16-character auth token typed on a phone keyboard is the worst thing in this
         * app, so paste is the path that matters and typing is the fallback.
         */
        fun fromConnectionString(text: String?): SavedServer? {
            val trimmed = text?.trim()?.takeIf { it.startsWith(PREFIX) } ?: return null
            val json = runCatching {
                // the Swing app writes padded standard base64; be lenient anyway, because this
                // arrives through a clipboard and a chat window may well have eaten the '='
                Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
                    .decode(trimmed.removePrefix(PREFIX))
                    .decodeToString()
            }.getOrNull() ?: return null
            val payload = JsonUtils.fromJson<ConnectionPayload>(json) ?: return null
            val host = payload.host?.takeIf { it.isNotBlank() } ?: return null
            return SavedServer(
                host = host,
                port = payload.port ?: DEFAULT_PORT,
                name = payload.name,
                token = payload.token.orEmpty(),
            )
        }

        /** the same string, written — what Share Devices puts in its QR code and on the clipboard. */
        fun toConnectionString(server: SavedServer): String {
            val json = JsonUtils.toJson(
                ConnectionPayload(server.host, server.port, server.token, server.name)
            ).orEmpty()
            return PREFIX + Base64.encode(json.encodeToByteArray())
        }
    }
}

/**
 * the connection string's payload, which belongs to the Swing app rather than to us.
 *
 * every field is nullable: Gson wrote it from a `HashMap`, and a field appearing or changing there
 * must not stop a paste working here.
 */
@Serializable
private data class ConnectionPayload(
    val host: String? = null,
    val port: Int? = null,
    val token: String? = null,
    val name: String? = null,
)

/**
 * what `/api/info` answers.
 *
 * the cheapest call the server has, and the only one that proves a token without asking for a device
 * list — which is what makes it the right thing to hit when someone presses Connect.
 */
@Serializable
data class ServerInfo(
    val version: String? = null,
    val deviceCount: Int = 0,
    val serverName: String? = null,
)
