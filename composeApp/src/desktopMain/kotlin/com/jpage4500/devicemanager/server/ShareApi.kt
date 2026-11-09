package com.jpage4500.devicemanager.server

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*

private val log = LoggerConfig.createLogger("ShareApi")

/**
 * the Swing app's server API, endpoint for endpoint, answered from [DeviceManager].
 *
 * The reference is `AndroidDeviceManager/docs/SERVER_API.md`; anything that reads a Swing server
 * reads this one — this app's own [RemoteHttpTransport], a Swing app pointed at this desk, `curl`.
 * Where that doc lists a Swing quirk under "Gotchas" this follows the documented contract rather
 * than the quirk: `/api/install`'s per-device marks are right, and every device-scoped endpoint
 * refuses a serial this machine doesn't reach itself.
 *
 * The JSON is written out by hand rather than serialized from [Device], for the reason
 * [RemoteHttpTransport] reads it by hand: the shape belongs to the Swing app's Gson. Null fields
 * are left out, as Gson leaves them out.
 */
internal object ShareApi {
    private const val API_INFO = "/api/info"
    private const val API_DEVICES = "/api/devices"
    private const val API_PROPERTIES = "/api/properties"
    private const val API_SET_PROPERTY = "/api/setproperty"
    private const val API_EXECUTE = "/api/execute"
    private const val API_INSTALL = "/api/install"
    private const val API_FILES_LIST = "/api/files/list"
    private const val API_FILES_DOWNLOAD = "/api/files/download"
    private const val API_FILES_UPLOAD = "/api/files/upload"
    private const val API_SCREENSHOT = "/api/screenshot"
    private const val WS_LOGS = "/ws/logs"
    private const val WS_SCREEN = "/ws/screen"

    private const val HEADER_NAME = "x-client-name"
    private const val BEARER = "Bearer "

    /** where an uploaded APK waits for `pm`: a fixed name, so nothing a client sends becomes a path. */
    private const val INSTALL_NAME = "share-install.apk"

    suspend fun handle(request: ShareRequest): ShareResponse {
        if (!ShareServer.isAuthorized(tokenOf(request))) {
            log.w("handle: ${request.remoteIp} ${request.path}: unauthorized")
            return ShareResponse.text("Unauthorized", HttpStatusCode.Unauthorized)
        }
        ShareServer.trackClient(request.remoteIp, clientName(request))
        log.v("handle: ${request.method.value} ${request.path} from ${request.remoteIp}")
        return when (request.path) {
            API_INFO -> info()
            API_DEVICES -> devices()
            API_PROPERTIES -> withDevice(request) { properties(it) }
            API_SET_PROPERTY -> setProperty(request)
            API_EXECUTE -> execute(request)
            API_INSTALL -> install(request)
            API_FILES_LIST -> withDevice(request) { listFiles(it, request) }
            API_FILES_DOWNLOAD -> withDevice(request) { download(it, request) }
            API_FILES_UPLOAD -> withDevice(request) { upload(it, request) }
            API_SCREENSHOT -> withDevice(request) { screenshot(it) }
            else -> notFound("Not found")
        }
    }

    /** a websocket, accepted: authorized and pointed at a shared device, or told why not. */
    suspend fun socket(request: ShareRequest, socket: ShareSocket) {
        if (!ShareServer.isAuthorized(tokenOf(request))) {
            log.w("socket: ${request.remoteIp} ${request.path}: unauthorized")
            return refuse(socket, "Unauthorized")
        }
        ShareServer.trackClient(request.remoteIp, clientName(request))
        if (request.path != WS_LOGS && request.path != WS_SCREEN) return refuse(socket, "Unknown endpoint")
        val serial = request.param("serial") ?: return refuse(socket, "Missing serial parameter")
        val device = ShareServer.sharedDevice(serial) ?: return refuse(socket, "Device not found")

        if (request.path == WS_LOGS) {
            val filterText = request.param("filter")
            val filter = filterText?.takeIf { it.isNotBlank() }?.let { LogFilter(name = "", expression = it) }
            log.i("socket: logs for ${device.serial} to ${request.remoteIp}")
            ShareLogStream(device, filter).run(socket)
        } else {
            // JPEG only when asked for — PNG is the default the Swing API documents
            val compress = request.param("compress").equals("true", ignoreCase = true)
            log.i("socket: screen of ${device.serial} to ${request.remoteIp}, compress: $compress")
            ShareScreenStream(device, compress).run(socket)
        }
    }

    /** the error message and a 1008 close — after the upgrade, which is where a Swing client looks. */
    private suspend fun refuse(socket: ShareSocket, reason: String) {
        socket.sendText(buildJsonObject {
            put("type", "error")
            put("message", reason)
        }.toString())
        socket.closeReason = CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason)
    }

    // ---- endpoints ----

    private suspend fun info(): ShareResponse = ShareResponse.json(buildJsonObject {
        put("version", ShareServer.VERSION)
        put("deviceCount", ShareServer.sharedDevices().size)
        put("serverName", ShareServer.state.value.name)
    }.toString())

    private fun devices(): ShareResponse =
        ShareResponse.json(JsonArray(ShareServer.sharedDevices().map { deviceJson(it) }).toString())

    private suspend fun properties(device: Device): ShareResponse {
        val transport = DeviceManager.connectionFor(device.id) ?: return notFound("Device not found")
        return transport.properties(device.serial).fold(
            onSuccess = { map -> ShareResponse.json(JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()) },
            onFailure = { notFound("Error: ${it.message}") },
        )
    }

    /** an app custom property, not `setprop` — see the API doc. */
    private suspend fun setProperty(request: ShareRequest): ShareResponse {
        val body = bodyJson(request)
        val serial = body?.field("serial")
        val key = body?.field("key")
        val value = body?.field("value")
        if (serial == null || key == null || value == null) return badRequest("Missing data")
        val device = ShareServer.sharedDevice(serial) ?: return notFound("Device not found")
        val ok = DeviceManager.setCustomProperty(device.id, key, value).isSuccess
        return ShareResponse.json(buildJsonObject { put("success", ok) }.toString())
    }

    /**
     * a shell command, as `{isSuccess, resultList}`.
     *
     * `isSuccess` is whether the command could be *run*, not its exit status — the Swing contract,
     * and what [RemoteHttpTransport.exec] reads it as.
     */
    private suspend fun execute(request: ShareRequest): ShareResponse {
        val body = bodyJson(request)
        val serial = body?.field("serial")
        val command = body?.field("command")
        if (serial == null || command == null) return badRequest("Missing serial or command")
        val device = ShareServer.sharedDevice(serial) ?: return notFound("Device not found")
        val result = DeviceManager.exec(device.id, command)
        val lines = result.fold(
            onSuccess = { output -> if (output.isEmpty()) emptyList() else output.trimEnd('\r', '\n').lines().map { it.trimEnd('\r') } },
            onFailure = { listOf(it.message ?: "couldn't run it") },
        )
        return ShareResponse.json(buildJsonObject {
            put("isSuccess", result.isSuccess)
            put("resultList", JsonArray(lines.map { JsonPrimitive(it) }))
        }.toString())
    }

    /**
     * one APK on every `serial` given — the only endpoint that takes several.
     *
     * One at a time, as the Swing server does: it is one request, and a bench of devices each
     * pulling a 100 MB push through the same adb server at once is slower than taking turns.
     */
    private suspend fun install(request: ShareRequest): ShareResponse {
        val devices = request.query.getAll("serial").orEmpty().mapNotNull { ShareServer.sharedDevice(it) }
        if (devices.isEmpty()) return notFound("Device not found")
        val bytes = uploadedBytes(request)
        if (bytes.isEmpty()) return badRequest("No file data received")
        var allOk = true
        val marks = mutableListOf<String>()
        for (device in devices) {
            val result = InstallManager.installBytes(device.id, bytes, INSTALL_NAME)
            if (result.isFailure) {
                allOk = false
                log.w("install: ${device.serial}: ${result.exceptionOrNull()?.message}")
            }
            marks += "${if (result.isSuccess) "✅" else "❌"} ${device.serial}"
        }
        val summary = marks.joinToString(", ")
        log.d("install: $summary")
        return ShareResponse.text(summary, if (allOk) HttpStatusCode.OK else HttpStatusCode.BadRequest)
    }

    private suspend fun listFiles(device: Device, request: ShareRequest): ShareResponse {
        val path = request.param("path")?.takeIf { it.isNotEmpty() } ?: "/"
        return DeviceManager.listFiles(device.id, path).fold(
            onSuccess = { files -> ShareResponse.json(JsonArray(files.map { fileJson(it) }).toString()) },
            onFailure = { notFound("Error: ${it.message}") },
        )
    }

    private suspend fun download(device: Device, request: ShareRequest): ShareResponse {
        val path = request.param("path")
        val name = request.param("file")
        if (path == null || name == null) return badRequest("Missing path, or file parameter")
        val bytes = DeviceManager.pullFile(device.id, joinPath(path, name)).getOrNull()
        if (bytes == null || bytes.isEmpty()) return notFound("File not found on device")
        val type = ContentType.defaultForFileExtension(name.substringAfterLast('.', "")).toString()
        return ShareResponse(HttpStatusCode.OK, type, bytes, mapOf("Content-Disposition" to attachment(name)))
    }

    private suspend fun upload(device: Device, request: ShareRequest): ShareResponse {
        val path = request.param("path")
        val name = request.param("file")
        if (path == null || name == null) return badRequest("Missing path, or file parameter")
        val bytes = uploadedBytes(request)
        if (bytes.isEmpty()) return badRequest("No file data received")
        val target = joinPath(path, name)
        return DeviceManager.pushFile(device.id, bytes, target).fold(
            onSuccess = {
                ShareResponse.json(buildJsonObject {
                    put("success", true)
                    put("message", "File uploaded successfully")
                    put("path", target)
                }.toString())
            },
            onFailure = { ShareResponse.text("Error uploading file: ${it.message}", HttpStatusCode.InternalServerError) },
        )
    }

    private suspend fun screenshot(device: Device): ShareResponse {
        wake(device)
        return DeviceManager.screenshot(device.id).fold(
            onSuccess = { png ->
                ShareResponse(HttpStatusCode.OK, "image/png", png, mapOf("Content-Disposition" to attachment("screenshot_${device.serial}.png")))
            },
            onFailure = { ShareResponse.text("Error processing screenshot: ${it.message}", HttpStatusCode.InternalServerError) },
        )
    }

    /** turn the screen on. WAKEUP rather than the Swing app's POWER, which would turn an awake one off. */
    suspend fun wake(device: Device) {
        DeviceManager.exec(device.id, "input keyevent ${DeviceInput.KEY_WAKEUP}")
    }

    // ---- helpers ----

    private suspend fun withDevice(request: ShareRequest, block: suspend (Device) -> ShareResponse): ShareResponse {
        val device = ShareServer.sharedDevice(request.param("serial")) ?: return notFound("Device not found")
        return block(device)
    }

    /** `?token=` wins over the header, as on the Swing server. */
    private fun tokenOf(request: ShareRequest): String? =
        request.param("token") ?: request.header("authorization")?.let {
            if (it.startsWith(BEARER, ignoreCase = true)) it.substring(BEARER.length) else it
        }

    /** the client's own name for itself, trimmed of anything that would break a log line. */
    private fun clientName(request: ShareRequest): String? =
        request.header(HEADER_NAME)?.replace(Regex("[\r\n]"), "")?.trim()?.take(128)?.ifEmpty { null }

    private fun bodyJson(request: ShareRequest): JsonObject? = jsonObjectOrNull(request.body.decodeToString())

    /** a field as text, blank included — a blank `value` is how a custom property is cleared. */
    private fun JsonObject.field(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** what was uploaded: the `file` part of a multipart form (the Swing client's way) or the raw body. */
    private fun uploadedBytes(request: ShareRequest): ByteArray {
        val type = request.header("content-type")
        if (type == null || !type.startsWith("multipart/", ignoreCase = true)) return request.body
        val boundary = type.substringAfter("boundary=", "").substringBefore(';').trim().removeSurrounding("\"")
        if (boundary.isEmpty()) return request.body
        return Multipart.filePart(request.body, boundary) ?: ByteArray(0)
    }

    private fun joinPath(dir: String, name: String): String = if (dir.endsWith('/')) dir + name else "$dir/$name"

    private fun attachment(name: String) = "attachment; filename=\"${name.replace("\"", "")}\""

    private fun notFound(message: String) = ShareResponse.text(message, HttpStatusCode.NotFound)

    private fun badRequest(message: String) = ShareResponse.text(message, HttpStatusCode.BadRequest)

    /** a device as the Swing app's Gson writes one — the shape `/api/devices` has always had. */
    private fun deviceJson(device: Device): JsonObject = buildJsonObject {
        put("serial", device.serial)
        putText("nickname", device.nickname)
        putText("phone", device.phone)
        putText("imei", device.imei)
        device.freeSpace?.let { put("freeSpace", it) }
        device.totalSpace?.let { put("totalSpace", it) }
        device.batteryLevel?.let { put("batteryLevel", it) }
        put("powerStatus", swingPowerStatus(device.powerStatus))
        putText("model", device.model)
        putText("os", device.os)
        putText("sdk", device.sdk)
        putText("carrier", device.carrier)
        putText("timezone", device.timezone)
        // the Swing `status` is the line that says what's wrong; a device that isn't online is that
        if (!device.isOnline) put("status", device.state.label)
        put("isOnline", device.isOnline)
        put("isBooted", device.isBooted)
        device.lastUpdateMs?.let { put("lastUpdateMs", it) }
        device.bootTimeMs?.let { put("bootTimeMs", it) }
        if (device.customProperties.isNotEmpty()) {
            put("customPropertyMap", JsonObject(device.customProperties.mapValues { JsonPrimitive(it.value) }))
        }
    }

    /**
     * the Swing names, which have a `POWER_` in front and no CHARGING.
     *
     * CHARGING (plugged in, plug unknown) goes as AC: a Swing client reads an unknown name as null
     * and then calls `name()` on it, and AC is the nearest thing it knows that still says charging.
     */
    private fun swingPowerStatus(status: PowerStatus): String = when (status) {
        PowerStatus.CHARGING -> "POWER_AC"
        else -> "POWER_${status.name}"
    }

    /**
     * a file in both shapes at once: the Swing `DeviceFile` fields (`dateMs`, `permissions`,
     * `isDirectory`) and the `path`, `date` and `mode` this app's own client reads. Gson ignores
     * the fields it has no use for, so one list serves both.
     */
    private fun fileJson(file: DeviceFile): JsonObject = buildJsonObject {
        put("name", file.name)
        put("path", file.path)
        put("size", file.size)
        put("dateMs", file.modifiedMs)
        put("date", file.modifiedMs)
        put("mode", file.mode)
        put("permissions", permissions(file.mode))
        put("isDirectory", file.isDirectory)
        put("isSymbolicLink", file.isSymlink)
        put("isReadOnly", false)
    }

    /** `drwxr-xr-x` out of a mode — what `ls -l` would have printed for it. */
    private fun permissions(mode: Int): String = buildString {
        append(
            when {
                AdbFileMode.isDir(mode) -> 'd'
                AdbFileMode.isLink(mode) -> 'l'
                else -> '-'
            }
        )
        val bits = "rwxrwxrwx"
        for (i in 0 until 9) append(if (mode and (1 shl (8 - i)) != 0) bits[i] else '-')
    }

    private fun JsonObjectBuilder.putText(key: String, value: String?) {
        if (!value.isNullOrEmpty()) put(key, value)
    }
}

/** just enough multipart/form-data to take one file out of an upload. */
internal object Multipart {
    /** the part named `file` (or failing that, the first with a filename, or the first at all). */
    fun filePart(body: ByteArray, boundary: String): ByteArray? {
        val delimiter = "--$boundary".encodeToByteArray()
        val headerEnd = "\r\n\r\n".encodeToByteArray()
        val parts = mutableListOf<Pair<String, ByteArray>>()
        var at = indexOf(body, delimiter, 0)
        while (at >= 0) {
            val start = at + delimiter.size
            // "--" straight after a delimiter is the end of the form
            if (start + 1 < body.size && body[start] == '-'.code.toByte() && body[start + 1] == '-'.code.toByte()) break
            val headersAt = indexOf(body, headerEnd, start)
            if (headersAt < 0) break
            val dataAt = headersAt + headerEnd.size
            val next = indexOf(body, delimiter, dataAt)
            if (next < 0) break
            // the CRLF before the next delimiter belongs to the delimiter, not the data
            val dataEnd = (next - 2).coerceAtLeast(dataAt)
            parts += body.decodeToString(start, headersAt) to body.copyOfRange(dataAt, dataEnd)
            at = next
        }
        return (parts.firstOrNull { it.first.contains("name=\"file\"") }
            ?: parts.firstOrNull { it.first.contains("filename=") }
            ?: parts.firstOrNull())?.second
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        if (pattern.isEmpty()) return from
        val first = pattern[0]
        var i = from
        val last = data.size - pattern.size
        while (i <= last) {
            if (data[i] == first) {
                var j = 1
                while (j < pattern.size && data[i + j] == pattern[j]) j++
                if (j == pattern.size) return i
            }
            i++
        }
        return -1
    }
}
