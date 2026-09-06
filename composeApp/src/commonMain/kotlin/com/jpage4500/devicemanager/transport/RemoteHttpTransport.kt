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
    private val baseUrl: String,
    private val authToken: String,
    private val clientName: String = "Device Manager",
    private val pollMs: Long = 3_000,
) : DeviceTransport {

    private val log = LoggerConfig.createLogger("RemoteHttpTransport")

    // the app's client rather than a bare HttpClient(): it accepts the self-signed certificate a
    // Device Manager instance on the LAN will be serving, and its traffic shows up under the
    // "HttpLog" tag with the token redacted out of the URL. see docs/APP-SERVICES.md
    private val client = createHttpClient(useCookies = false)

    override val label: String get() = baseUrl.removePrefix("http://").removePrefix("https://")

    private fun HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer $authToken")
        header("x-client-name", clientName)
    }

    override fun devices(): Flow<List<Device>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(fetchDevices().getOrElse { emptyList() })
            delay(pollMs)
        }
    }

    private suspend fun fetchDevices(): Result<List<Device>> = runCatching {
        val body = client.get("$baseUrl/api/devices") { auth() }.bodyAsText()
        parseDevices(body)
    }

    override suspend fun exec(serial: String, command: String): Result<String> = runCatching {
        client.post("$baseUrl/api/execute") {
            auth()
            parameter("serial", serial)
            parameter("command", command)
        }.bodyAsText()
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
                phone = obj.text("phone"),
                batteryLevel = obj.text("batteryLevel")?.toIntOrNull(),
                freeSpace = obj.text("freeSpace")?.toLongOrNull(),
                totalSpace = obj.text("totalSpace")?.toLongOrNull(),
            )
        }
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
}
