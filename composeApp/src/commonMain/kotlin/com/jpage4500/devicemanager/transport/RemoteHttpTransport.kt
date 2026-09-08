package com.jpage4500.devicemanager.transport

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.util.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * devices reached through a running Device Manager instance, over its HTTP API.
 *
 * This is the route the phone app should ship first: bearer-token auth, no adb client on the phone
 * at all, and it reaches the desktop's whole USB fleet. The Swing app already serves these endpoints
 * on port 8765 — `/api/devices`, `/api/execute`, `/api/files/list`, `/api/screenshot` — so a phone
 * speaking this protocol works against today's app before any of it is ported.
 *
 * Polls rather than subscribes. The server also has a `/screen` websocket that pushes JPEG frames,
 * which is what a mirror view should use — see docs/ADB-TRANSPORTS.md.
 */
class RemoteHttpTransport(
    /** `http://host:port`, readable so [com.jpage4500.devicemanager.manager.ServerManager] can say
     *  which saved server the app is currently on. */
    val baseUrl: String,
    private val authToken: String,
    private val clientName: String = "Device Manager",
) : DeviceTransport {

    private val log = LoggerConfig.createLogger("RemoteHttpTransport")

    // the app's client rather than a bare HttpClient(): it accepts the self-signed certificate a
    // Device Manager instance on the LAN will be serving, and its traffic shows up under the
    // "HttpLog" tag with the token redacted out of the URL. see docs/APP-SERVICES.md
    private val client = createHttpClient(useCookies = false)

    override val id: String get() = idOf(baseUrl)

    override val label: String get() = SavedServer.keyOf(baseUrl)

    private fun HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer $authToken")
        header("x-client-name", clientName)
    }

    /**
     * ask the server who it is — `/api/info`, which answers `{version, deviceCount, serverName}`.
     *
     * the call to make when someone presses Connect, rather than `/api/devices`: it is the cheapest
     * endpoint, a wrong token comes back as a plain 401 rather than as an empty list, and the name
     * it hands back is what the saved entry should be labelled with instead of an IP address.
     */
    suspend fun info(): Result<ServerInfo> = runCatching {
        val response = client.get("$baseUrl/api/info") { auth() }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw IllegalStateException("wrong auth token")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("server answered ${response.status.value}")
        }
        val body = response.bodyAsText()
        JsonUtils.fromJson<ServerInfo>(body)
            ?: throw IllegalStateException("that isn't a Device Manager server")
    }

    override suspend fun probe(): Result<String> = info().map { it.serverName ?: label }

    /** everything a row shows is in `/api/devices` already — see [DeviceTransport.hasDeviceDetails]. */
    override val hasDeviceDetails: Boolean get() = true

    /**
     * poll `/api/info`, and only re-read the device list when it is worth reading.
     *
     * `/api/info` is a device count the server already has in memory, so asking every few seconds
     * costs nothing and a device plugged in at the desk shows up almost at once. `/api/devices` is
     * the expensive one — the server assembled it by talking to every device — so it is re-read when
     * the **count changes**, and otherwise on the same slow timer that governs battery and disk
     * everywhere else in the app. That is the cadence the server's own client uses, and the reason
     * this app runs no shell commands of its own against a Device Manager server.
     */
    override fun devices(): Flow<List<Device>> = flow {
        var knownCount = -1
        var lastFetchMs = 0L
        while (currentCoroutineContext().isActive) {
            val refreshMs =
                SettingsManager.getInt(AppPref.DETAILS_REFRESH_SECS).coerceAtLeast(5) * 1000L
            val count = info().getOrNull()?.deviceCount ?: -1
            val now = DateUtils.nowMs()
            if (count != knownCount || now - lastFetchMs >= refreshMs) {
                val devices = fetchDevices().getOrElse { emptyList() }
                emit(devices)
                // what was actually read, not what the count said, so a disagreement retries
                knownCount = devices.size
                lastFetchMs = now
            }
            delay(INFO_POLL_MS)
        }
    }

    private suspend fun fetchDevices(): Result<List<Device>> = runCatching {
        val body = client.get("$baseUrl/api/devices") { auth() }.bodyAsText()
        parseDevices(body)
    }

    /**
     * `/api/execute`, whose two arguments go in a **JSON body** and not on the query string.
     *
     * The server reads the post body and nothing else, so query parameters come back as a 400
     * "Missing serial or command" — which was every tap on a mirrored screen. It answers with its
     * own `{isSuccess, resultList}` rather than the command's raw output, and this interface's
     * contract is what the command printed, so [parseShellResult] joins the lines back up.
     */
    override suspend fun exec(serial: String, command: String): Result<String> = runCatching {
        val body = buildJsonObject {
            put("serial", serial)
            put("command", command)
        }
        val response = client.post("$baseUrl/api/execute") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("$command: server answered ${response.status.value}")
        }
        parseShellResult(response.bodyAsText())
    }

    override suspend fun properties(serial: String): Result<Map<String, String>> = runCatching {
        val body = client.get("$baseUrl/api/properties") {
            auth()
            parameter("serial", serial)
        }.bodyAsText()
        val root = Json.parseToJsonElement(body)
        root.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
    }

    override suspend fun screenshot(serial: String): Result<ByteArray> = runCatching {
        client.get("$baseUrl/api/screenshot") {
            auth()
            parameter("serial", serial)
        }.body<ByteArray>()
    }

    override suspend fun listFiles(serial: String, path: String): Result<List<DeviceFile>> =
        runCatching {
            val body = client.get("$baseUrl/api/files/list") {
                auth()
                parameter("serial", serial)
                parameter("path", path)
            }.bodyAsText()
            parseFiles(path, body)
        }

    override fun close() {
        client.close()
    }

    /**
     * the server's own JSON, read defensively.
     *
     * Hand-read rather than `@Serializable`, because the shape belongs to the Swing app: it is Gson
     * writing whatever `Device` happens to hold, and a field appearing or changing type there must
     * not stop the list rendering here.
     */
    private fun parseDevices(body: String): List<Device> {
        val root = Json.parseToJsonElement(body)
        val array = when {
            root is JsonArray -> root
            root is JsonObject && root["devices"] is JsonArray -> root["devices"]!!.jsonArray
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val serial = obj.text("serial") ?: return@mapNotNull null
            Device(
                serial = serial,
                state = if (obj.bool("isOnline") != false) DeviceState.DEVICE else DeviceState.OFFLINE,
                model = obj.text("model"),
                nickname = obj.text("nickname"),
                os = obj.text("os"),
                sdk = obj.text("sdk"),
                carrier = obj.text("carrier"),
                timezone = obj.text("timezone"),
                phone = obj.text("phone"),
                imei = obj.text("imei"),
                batteryLevel = obj.text("batteryLevel")?.toIntOrNull(),
                powerStatus = obj.powerStatus(),
                freeSpace = obj.text("freeSpace")?.toLongOrNull(),
                totalSpace = obj.text("totalSpace")?.toLongOrNull(),
                customProperties = obj.stringMap("customPropertyMap"),
                bootTimeMs = obj.text("bootTimeMs")?.toLongOrNull(),
                isBooted = obj.bool("isBooted") != false,
                lastUpdateMs = obj.text("lastUpdateMs")?.toLongOrNull(),
            )
        }
    }

    /**
     * what a command printed, out of the server's `{isSuccess, resultList}`.
     *
     * `isSuccess` is whether the server could *run* the command, not the command's exit status, so
     * a false is a failure of this call and whatever it did print is the message. A body that isn't
     * that shape is handed back as-is rather than thrown away.
     */
    private fun parseShellResult(body: String): String {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return body
        val output = (root["resultList"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.joinToString("\n")
            .orEmpty()
        if (root.bool("isSuccess") == false) {
            throw IllegalStateException(output.ifEmpty { "the server couldn't run it" })
        }
        return output
    }

    private fun parseFiles(parent: String, body: String): List<DeviceFile> {
        val root = Json.parseToJsonElement(body)
        val array = when {
            root is JsonArray -> root
            root is JsonObject && root["files"] is JsonArray -> root["files"]!!.jsonArray
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.text("name") ?: return@mapNotNull null
            DeviceFile(
                name = name,
                path = obj.text("path") ?: "$parent/$name",
                size = obj.text("size")?.toLongOrNull() ?: 0,
                modifiedMs = obj.text("date")?.toLongOrNull() ?: 0,
                mode = obj.text("mode")?.toIntOrNull() ?: 0,
            )
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.stringMap(key: String): Map<String, String> =
        (this[key] as? JsonObject)
            ?.mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { name to it }
            }
            ?.toMap()
            .orEmpty()

    /**
     * the server's `POWER_AC` and friends, which are this app's [PowerStatus] with a prefix.
     *
     * read from `batteryInfo` when the top-level field is missing: both carry it, and Gson omits
     * whichever happens to be null.
     */
    private fun JsonObject.powerStatus(): PowerStatus {
        val name = text("powerStatus")
            ?: (this["batteryInfo"] as? JsonObject)?.text("powerStatus")
            ?: return PowerStatus.NONE
        return runCatching { PowerStatus.valueOf(name.removePrefix("POWER_")) }
            .getOrDefault(PowerStatus.NONE)
    }

    companion object {
        /** the connection id a Device Manager instance at [baseUrl] has, without building one. */
        fun idOf(baseUrl: String): String = SavedServer.connectionIdOf(SavedServer.keyOf(baseUrl))

        /**
         * how often the server is asked how many devices it has.
         *
         * cheap enough to do often — it is an in-memory count — and it is what makes a device
         * plugged in at the desk appear here within a few seconds rather than on the slow timer.
         */
        private const val INFO_POLL_MS = 5_000L
    }
}
