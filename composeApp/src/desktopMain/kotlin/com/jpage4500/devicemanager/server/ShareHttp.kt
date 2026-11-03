package com.jpage4500.devicemanager.server

import com.jpage4500.devicemanager.util.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
// explicit: ktor's io package declares a CancellationException typealias too, and two wildcard
// imports make the name ambiguous
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.*
// explicit: java.net's InetSocketAddress would collide with ktor's under a wildcard
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64

private val log = LoggerConfig.createLogger("ShareHttp")

/** one HTTP request, read off the socket whole — the body included. */
internal class ShareRequest(
    val method: HttpMethod,
    val path: String,
    val query: Parameters,
    /** names lower-cased: HTTP's are case-blind, and a lookup here shouldn't have to be. */
    val headers: Map<String, String>,
    /** the socket's own address, not anything the client claimed. */
    val remoteIp: String,
    val body: ByteArray = ByteArray(0),
) {
    fun param(name: String): String? = query[name]
    fun header(name: String): String? = headers[name.lowercase()]
}

internal class ShareResponse(
    val status: HttpStatusCode,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        // errors are text/plain, not JSON — the Swing API's convention, which clients rely on
        fun text(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            ShareResponse(status, "text/plain; charset=utf-8", text.encodeToByteArray())

        fun json(json: String) = ShareResponse(HttpStatusCode.OK, "application/json", json.encodeToByteArray())
    }
}

/**
 * an open websocket, as a handler sees it: the client's text messages in, and frames back out.
 *
 * Pings, pongs and the close handshake are dealt with underneath; [incoming] closing is how a
 * handler learns the client went. A handler returning is what closes the socket, with [closeReason].
 */
internal class ShareSocket(private val session: WebSocketSession, val incoming: ReceiveChannel<String>) {
    var closeReason = CloseReason(CloseReason.Codes.NORMAL, "")

    suspend fun sendText(text: String) = session.outgoing.send(Frame.Text(text))

    suspend fun sendBinary(bytes: ByteArray) = session.outgoing.send(Frame.Binary(true, bytes))
}

/**
 * a small HTTP/1.1 server with websockets, over the socket and protocol code the app already ships.
 *
 * **Not ktor-server.** That would have been the obvious choice and measured 4.5 MB of new jar —
 * server-core, Typesafe Config and the 3 MB kotlin-reflect it drags in — which is more than the
 * adb libraries this project turned down for weighing too much. Everything a server needs is
 * already here for the client: ktor-network's sockets, ktor-http-cio's request parser and
 * ktor-websockets' frame codec. What is left is this file — accept, parse, answer, upgrade.
 *
 * Coroutines throughout, so 45 log sockets are 45 suspended coroutines rather than 45 threads.
 * The protocol on top is in [ShareApi]; nothing here knows what an endpoint means.
 */
internal class ShareHttpServer(
    private val port: Int,
    private val onRequest: suspend (ShareRequest) -> ShareResponse,
    private val onSocket: suspend (ShareRequest, ShareSocket) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var server: ServerSocket? = null

    /** bind, or throw why not; requests are answered from then until [stop]. */
    suspend fun start() {
        // every interface, as the Swing server did — the LAN, a VPN, and localhost all reach it
        val bound = aSocket(selector).tcp().bind(hostname = "0.0.0.0", port = port) { reuseAddress = true }
        server = bound
        scope.launch { acceptLoop(bound) }
    }

    /** close the listener and every connection on it, mid-stream or not. */
    fun stop() {
        runCatching { server?.close() }
        server = null
        scope.cancel()
    }

    private suspend fun acceptLoop(listener: ServerSocket) {
        while (currentCoroutineContext().isActive) {
            val socket = try {
                listener.accept()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (listener.isClosed) break
                log.w("acceptLoop: ${e.message}")
                continue
            }
            scope.launch { serve(socket) }
        }
    }

    /** one connection: requests one after another while the client keeps it alive, or one upgrade. */
    private suspend fun serve(socket: Socket) {
        val ip = remoteIp(socket)
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = false)
        try {
            while (true) {
                val parsed = withTimeoutOrNull(IDLE_MS) { parseRequest(input) } ?: break
                // copied out and released at once: the parser's buffers are pooled, and every
                // CharSequence it handed back is a view into them
                val http10 = parsed.version.toString() == "HTTP/1.0"
                val request = try {
                    val uri = parsed.uri.toString()
                    ShareRequest(
                        method = parsed.method,
                        path = uri.substringBefore('?'),
                        query = parseQueryString(uri.substringAfter('?', "")),
                        headers = parsed.headers.offsets()
                            .associate { parsed.headers.nameAtOffset(it).toString().lowercase() to parsed.headers.valueAtOffset(it).toString() },
                        remoteIp = ip,
                    )
                } finally {
                    parsed.release()
                }

                if (request.header("upgrade").equals("websocket", ignoreCase = true)) {
                    upgrade(request, input, output)
                    return
                }

                val body = try {
                    readBody(request, input)
                } catch (e: BodyTooLargeException) {
                    write(output, ShareResponse.text("Request too large", HttpStatusCode.PayloadTooLarge), keepAlive = false)
                    return
                }
                val response = try {
                    onRequest(ShareRequest(request.method, request.path, request.query, request.headers, ip, body))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e("serve: ${request.path}: ${e.message}")
                    ShareResponse.text("Error: ${e.message}", HttpStatusCode.InternalServerError)
                }
                val keepAlive = !http10 && !request.header("connection").equals("close", ignoreCase = true)
                write(output, response, keepAlive)
                if (!keepAlive) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a client that hung up mid-request is the usual one; nothing to answer
            log.d("serve: $ip: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun readBody(request: ShareRequest, input: ByteReadChannel): ByteArray {
        val chunked = request.header("transfer-encoding")?.contains("chunked", ignoreCase = true) == true
        if (chunked) {
            return coroutineScope {
                val body = ByteChannel()
                launch {
                    parseHttpBody(HttpProtocolVersion.HTTP_1_1, -1, "chunked", null, input, body)
                    body.flushAndClose()
                }
                val bytes = body.toByteArray()
                if (bytes.size > MAX_BODY_BYTES) throw BodyTooLargeException()
                bytes
            }
        }
        val length = request.header("content-length")?.trim()?.toLongOrNull() ?: return ByteArray(0)
        if (length > MAX_BODY_BYTES) throw BodyTooLargeException()
        if (length <= 0) return ByteArray(0)
        return input.readByteArray(length.toInt())
    }

    private suspend fun write(output: ByteWriteChannel, response: ShareResponse, keepAlive: Boolean) {
        val head = buildString {
            append("HTTP/1.1 ${response.status.value} ${response.status.description}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            if (!keepAlive) append("Connection: close\r\n")
            append("\r\n")
        }
        output.writeFully(head.encodeToByteArray())
        output.writeFully(response.body)
        output.flush()
    }

    /**
     * the websocket handshake, then the socket for as long as the handler keeps it.
     *
     * The upgrade always succeeds, even for a bad token or an unknown device: the handler refuses
     * after it, with an `error` message and a 1008 close. That is the Swing server's behaviour and
     * what its clients look for — a refused upgrade would look like a network fault to them.
     */
    private suspend fun upgrade(request: ShareRequest, input: ByteReadChannel, output: ByteWriteChannel) {
        val key = request.header("sec-websocket-key")
        if (key == null) {
            write(output, ShareResponse.text("Missing Sec-WebSocket-Key", HttpStatusCode.BadRequest), keepAlive = false)
            return
        }
        val head = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n"
        output.writeFully(head.encodeToByteArray())
        output.flush()

        // a server never masks; the reader unmasks what the client sends
        val session = RawWebSocket(
            input, output, MAX_FRAME_BYTES, false, currentCoroutineContext(), WebSocketChannelsConfig.UNLIMITED,
        )
        val texts = Channel<String>(Channel.UNLIMITED)
        val socket = ShareSocket(session, texts)
        try {
            coroutineScope {
                // the raw session hands up every frame; the control ones are answered here so no
                // handler has to know about them
                val pump = launch {
                    try {
                        for (frame in session.incoming) {
                            when (frame) {
                                is Frame.Text -> texts.send(frame.readText())
                                is Frame.Ping -> session.outgoing.send(Frame.Pong(frame.data))
                                is Frame.Close -> break
                                else -> Unit
                            }
                        }
                    } finally {
                        texts.close()
                    }
                }
                // what keeps a quiet log socket from being dropped by a NAT or a phone's radio
                val pinger = launch {
                    while (isActive) {
                        delay(PING_MS)
                        session.outgoing.send(Frame.Ping(PING_PAYLOAD))
                    }
                }
                try {
                    onSocket(request, socket)
                } finally {
                    pump.cancel()
                    pinger.cancel()
                }
            }
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(CLOSE_GRACE_MS) {
                    runCatching {
                        session.outgoing.send(Frame.Close(socket.closeReason))
                        session.flush()
                    }
                }
            }
            session.cancel()
        }
    }

    private class BodyTooLargeException : Exception()

    companion object {
        // the server's own, so starting it never touches the adb client's
        private val selector by lazy { SelectorManager(ioDispatcher) }

        /** the Swing server's socket timeout: long enough to sit between a client's polls. */
        private const val IDLE_MS = 60_000L

        // an APK is the largest thing anyone sends, and it is held in memory to be pushed
        private const val MAX_BODY_BYTES = 1L shl 30
        // the largest message a client sends is an input event or a filter — a few hundred bytes
        private const val MAX_FRAME_BYTES = 1L shl 20

        private const val PING_MS = 30_000L
        private val PING_PAYLOAD = "heartbeat".encodeToByteArray()
        private const val CLOSE_GRACE_MS = 2_000L

        // RFC 6455's fixed GUID, which the accept key is the SHA-1 of the client's key plus
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        private fun acceptKey(key: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1").digest((key.trim() + WEBSOCKET_GUID).toByteArray())
            return Base64.getEncoder().encodeToString(sha1)
        }

        /**
         * the peer's address as digits. Not ktor's `hostname`, which on the JVM is
         * `InetSocketAddress.getHostName()` — a reverse lookup on every connection.
         */
        private fun remoteIp(socket: Socket): String {
            val bytes = (socket.remoteAddress as? InetSocketAddress)?.resolveAddress() ?: return "unknown"
            return runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull() ?: "unknown"
        }
    }
}
