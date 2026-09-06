package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.AppPref
import com.jpage4500.devicemanager.manager.HttpLog.LOG_BODY_SIZE
import com.jpage4500.devicemanager.manager.HttpLog.shouldLogBody
import com.jpage4500.devicemanager.util.LoggerConfig
import com.jpage4500.devicemanager.util.SettingsManager
import com.jpage4500.devicemanager.util.Utils
import io.ktor.client.*
import io.ktor.client.plugins.api.*
import io.ktor.client.plugins.observer.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException

/**
 * single place that logs ALL HTTP requests/responses. every client built by [createHttpClient]
 * routes through here (via [installHttpLog]), so filtering logs by the "HttpLog" tag shows the whole
 * network conversation with consistent formatting.
 *
 * correlate a request with its response by the `(N)` number. request/response lines are always
 * logged; request/response bodies are logged only when the DEBUG_MODE setting is on.
 *
 * bodies are only captured for small, textual responses — see [shouldLogBody]. that matters because
 * the same client streams screenshots and pulled files; the ResponseObserver filter runs BEFORE
 * ktor splits the response channel, so those are never buffered.
 */
object HttpLog {
    private val log = LoggerConfig.createLogger("HttpLog")

    // per-request id (put on the request, read back on the response) for req/resp correlation
    private val reqNumKey = AttributeKey<Int>("HttpLogReqNum")

    // request/response bodies are truncated to this many chars in the log
    private const val LOG_BODY_SIZE = 5000

    // only capture response bodies at/under this size (skip large payloads even when textual)
    private const val MAX_BODY_BYTES = 20_000L

    // query-param names whose values are redacted in a logged URL (case-insensitive). a Device
    // Manager instance's bearer token can arrive either way, and an uploaded log is read by someone
    // who shouldn't end up holding it
    private val sensitiveParams = setOf("access_token", "token", "client_id", "key", "apikey", "api_key", "password", "secret")

    private var counter = 0
    private fun nextNum(): Int = ++counter

    // bodies are logged only in debug mode (they're verbose and can carry sensitive payloads)
    private fun logBodies(): Boolean = SettingsManager.getBoolean(AppPref.DEBUG_MODE)

    /** assign + record this request's id, then log it. [content] is the rendered request body. */
    fun logRequest(request: HttpRequestBuilder, content: Any?) {
        val n = nextNum()
        request.attributes.put(reqNumKey, n)
        log.d("($n) ${request.method.value} ${sanitizeUrl(request.url.buildString())}")
        if (logBodies()) {
            // at onRequest time a String body set via setBody() may still be a raw String (not yet a
            // TextContent) — handle both. this is the outgoing body, so reading it consumes nothing.
            val text = when (content) {
                is TextContent -> content.text
                is String -> content
                else -> null
            }
            if (!text.isNullOrEmpty()) logBody(">> ($n)", text)
        }
    }

    /** log response metadata only (status/timing/size) — never reads the body. */
    fun logResponse(response: HttpResponse) {
        val tag = tag(response.call.attributes.getOrNull(reqNumKey))
        val ms = response.responseTime.timestamp - response.requestTime.timestamp
        val size = response.contentLength()?.let { " ${Utils.bytesToDisplayString(it)}" } ?: ""
        if (response.status.isSuccess() || response.status == HttpStatusCode.SwitchingProtocols) {
            log.d("DONE: $tag ${ms}ms$size")
        } else {
            log.w("$tag HTTP_ERROR ${response.status.value} ${ms}ms ${sanitizeUrl(response.call.request.url.toString())}")
        }
    }

    fun logFailure(request: HttpRequestBuilder, cause: Throwable) {
        // a cancelled request isn't a failure and isn't logged at all: switching transports cancels
        // whatever the old one had in flight, and a line per cancellation buries what does matter
        if (cause is CancellationException) return
        log.e("!! ${tag(request.attributes.getOrNull(reqNumKey))} FAILED ${sanitizeUrl(request.url.buildString())} : ${cause.message}")
    }

    // only observe a response body for logging when debug mode is on and it's textual (see class doc
    // — this gate runs before the channel is split, so streaming/binary is left untouched). servers
    // often reply chunked with no Content-Length; allow those, and cap only when a length is actually
    // known — the textual content-type check keeps big binary downloads out.
    fun shouldLogBody(response: HttpResponse): Boolean {
        if (!logBodies()) return false
        // never read a websocket/upgrade body — doing so consumes the live socket and closes it
        if (response.status == HttpStatusCode.SwitchingProtocols) return false
        if (!isServerHost(response.call.request.url.host)) return false
        if (!isTextual(response.contentType())) return false
        // no Content-Length (chunked) — can't cap by size up front; logResponseBody caps the read instead
        val len = response.contentLength() ?: return true
        return len in 1..MAX_BODY_BYTES
    }

    // hosts whose traffic is logged verbosely. everything this app talks to is ours or the user's,
    // so this starts as "log everything"; narrow it if a fleet refresh makes the log unreadable.
    private fun isServerHost(@Suppress("UNUSED_PARAMETER") host: String): Boolean = true

    suspend fun logResponseBody(response: HttpResponse) {
        val tag = tag(response.call.attributes.getOrNull(reqNumKey))
        val body = runCatching { readLoggablePrefix(response) }.getOrNull()
        if (!body.isNullOrEmpty()) logBody("<< $tag", body)
    }

    // retain only the first [MAX_BODY_BYTES] for the log, but keep draining the rest: the observer
    // reads a split copy of the channel, so leaving bytes unread would backpressure-stall the real
    // consumer. chunked responses carry no Content-Length, so shouldLogBody
    // can't cap them by size — this is where the cap is actually enforced for those.
    private suspend fun readLoggablePrefix(response: HttpResponse): String {
        val channel = response.bodyAsChannel()
        val head = ByteArray(MAX_BODY_BYTES.toInt())
        val buf = ByteArray(16 * 1024)
        var kept = 0
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buf, 0, buf.size)
            if (read <= 0) break
            if (kept < head.size) {
                val take = minOf(read, head.size - kept)
                buf.copyInto(head, kept, 0, take)
                kept += take
            }
        }
        return head.decodeToString(0, kept)
    }

    private fun tag(n: Int?): String = if (n != null) "($n)" else "(?)"

    /**
     * log a request/response body line: "<prefix> <size> <body>", with the body truncated to
     * [LOG_BODY_SIZE] and newlines flattened to "⏎ " so each body is one log line.
     */
    private fun logBody(prefix: String, body: String) {
        val shown = if (body.length <= LOG_BODY_SIZE) body else body.take(LOG_BODY_SIZE) + "…"
        log.v("$prefix ${Utils.bytesToDisplayString(body.length.toLong())} ${shown.replace("\n", "⏎ ")}")
    }

    // only log bodies for textual content types (skip images/binary that also flow through here)
    private fun isTextual(ct: ContentType?): Boolean {
        if (ct == null) return true
        return ct.match(ContentType.Text.Any) ||
            ct.match(ContentType.Application.Json) ||
            ct.match(ContentType.Application.Xml) ||
            ct.match(ContentType.Application.FormUrlEncoded)
    }

    /**
     * redact secrets from a URL before logging: any sensitive query-param value (api keys, etc).
     */
    private fun sanitizeUrl(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx == -1) return url
        val base = url.substring(0, qIdx + 1)
        val query = url.substring(qIdx + 1)
        val redacted = query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq == -1) return@joinToString pair
            val name = pair.substring(0, eq)
            if (name.lowercase() in sensitiveParams) "$name=***" else pair
        }
        return base + redacted
    }
}

/**
 * install the shared [HttpLog] into a client config: the request line + response metadata (status,
 * timing, size) and failures via a small plugin, plus — only in debug mode, for small textual
 * responses — the response body via [ResponseObserver] (which reads a split copy so the caller's
 * body isn't consumed).
 */
fun HttpClientConfig<*>.installHttpLog() {
    install(httpLogPlugin)
    install(ResponseObserver) {
        filter { call -> HttpLog.shouldLogBody(call.response) }
        onResponse { response -> HttpLog.logResponseBody(response) }
    }
}

private val httpLogPlugin = createClientPlugin("HttpLog") {
    onRequest { request, content ->
        HttpLog.logRequest(request, content)
    }
    on(Send) { request ->
        try {
            val call = proceed(request)
            HttpLog.logResponse(call.response)
            call
        } catch (cause: Throwable) {
            HttpLog.logFailure(request, cause)
            throw cause
        }
    }
}
