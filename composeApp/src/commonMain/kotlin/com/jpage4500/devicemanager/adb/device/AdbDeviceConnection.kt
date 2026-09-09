package com.jpage4500.devicemanager.adb.device

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.crypto.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.sync.*
import kotlin.concurrent.*

private val log = LoggerConfig.createLogger("AdbDeviceConnection")

/**
 * one authenticated conversation with `adbd` itself, with many services multiplexed over it.
 *
 * This is the object that does the adb **server's** job without being one. The server exists to
 * enumerate USB, hold the RSA key, do the auth handshake, and keep a single connection per device
 * that many clients share; on a phone only the first of those needs anything the OS won't hand an
 * app, so what is left is this — see docs/ADB-TRANSPORTS.md.
 *
 * **Held open, and multiplexed.** The server protocol gives a socket to one service and closes it
 * when that service ends ([com.jpage4500.devicemanager.adb.AdbConnection]); here the connection
 * outlives every service on it, and streams are told apart by an id pair. That is not a nicety: the
 * handshake costs a round trip and an RSA signature, and a mirror polling `screencap` four times a
 * second would otherwise pay both on every frame.
 *
 * One reader coroutine owns the socket's read side and hands each message to the stream it belongs
 * to; writes go through [writeLock], because a `WRTE` and an `OKAY` interleaved halfway would put
 * two headers' bytes into one message.
 */
class AdbDeviceConnection private constructor(
    private val socket: AdbSocket,
    val address: String,
) {
    /** what the device said about itself in its `CNXN` — `device::ro.product.name=…;features=…`. */
    var banner: String = ""
        private set

    /** the features the banner advertises, which is what picks between v1 and v2 sync. */
    val features: Set<String>
        get() = banner.substringAfter("features=", "").split(',').filter { it.isNotEmpty() }.toSet()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeLock = Mutex()
    private val streams = mutableMapOf<Int, AdbDeviceStream>()
    private val streamLock = Mutex()

    // adb ids start at 1: zero is "no stream", which is what an unsolicited CLSE carries
    private var nextLocalId = 1

    /** the smaller of the two ends' limits, which is what a single `WRTE` may carry. */
    private var maxPayload = AdbMessage.MAX_PAYLOAD

    @Volatile
    private var closed = false

    /**
     * open a service on this connection: `shell:`, `exec:…`, `sync:` — the same strings the server
     * takes, because the service name is the one part of adb that is identical on both protocols.
     */
    suspend fun open(service: String): AdbOutcome<AdbDeviceStream> {
        if (closed) return AdbOutcome.Err(AdbError.DISCONNECTED, "the device connection is closed")

        val localId = streamLock.withLock { nextLocalId++ }
        val opened = AdbDeviceStream(this, localId, maxPayload)
        streamLock.withLock { streams[localId] = opened }

        // adbd reads the service as a C string, so the NUL is part of the payload
        val payload = (service + "\u0000").encodeToByteArray()
        val sent = send(AdbMessage(AdbMessage.A_OPEN, localId, 0, payload))
        if (sent is AdbOutcome.Err) {
            forget(localId)
            return sent
        }

        // OKAY carries the device's own id for this stream; CLSE means it refused the service
        return when (val ready = opened.awaitReady()) {
            is AdbOutcome.Err -> {
                forget(localId)
                ready
            }
            is AdbOutcome.Ok -> AdbOutcome.Ok(opened)
        }
    }

    /** open a service and read everything it prints, which is what `exec:` is for. */
    suspend fun exec(service: String, readMs: Long = AdbStream.EXEC_MS): AdbOutcome<ByteArray> {
        val opened = when (val outcome = open(service)) {
            is AdbOutcome.Err -> return outcome
            is AdbOutcome.Ok -> outcome.value
        }
        return try {
            val bytes = withTimeoutOrNull(readMs) { opened.readAll() }
            if (bytes == null) AdbOutcome.Err(AdbError.TIMEOUT, "the device didn't answer")
            else AdbOutcome.Ok(bytes)
        } finally {
            opened.close()
        }
    }

    internal suspend fun send(message: AdbMessage): AdbOutcome<Unit> = try {
        writeLock.withLock {
            // one write per message where possible: a header and payload sent separately are two
            // TLS records for one message, and the far end reads them fine but pays for both
            val header = message.header()
            if (message.payload.isEmpty()) {
                socket.write(header)
            } else {
                socket.write(header + message.payload)
            }
        }
        AdbOutcome.Ok(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AdbOutcome.Err(AdbError.DISCONNECTED, e.message ?: "the connection dropped")
    }

    internal suspend fun forget(localId: Int) {
        streamLock.withLock { streams.remove(localId) }
    }

    /**
     * read messages until the socket ends, handing each to the stream it names.
     *
     * Started once, after the handshake. Every message a device sends past that point carries the
     * *receiver's* id in `arg1`, which is what makes the routing a map lookup.
     */
    private fun startReader() {
        scope.launch {
            try {
                while (isActive) {
                    val header = readHeader() ?: break
                    val payload =
                        if (header.length > 0) socket.readFully(header.length) else ByteArray(0)
                    route(header, payload)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d("startReader: $address: ${e.message}")
            } finally {
                log.d("startReader: $address: stopped")
                closeAll()
            }
        }
    }

    private suspend fun route(header: AdbHeader, payload: ByteArray) {
        val target = streamLock.withLock { streams[header.arg1] }
        when (header.command) {
            AdbMessage.A_OKAY -> target?.onReady(header.arg0)

            AdbMessage.A_WRTE -> {
                target?.onData(payload)
                // adbd will not send another WRTE on this stream until it is acknowledged, so a
                // missing OKAY doesn't drop data — it stalls the stream silently
                send(AdbMessage(AdbMessage.A_OKAY, header.arg1, header.arg0))
            }

            AdbMessage.A_CLSE -> {
                target?.onClosed()
                forget(header.arg1)
            }

            // a device can re-announce itself; nothing above cares, but the banner is worth keeping
            AdbMessage.A_CNXN -> banner = payload.decodeToString().trimEnd('\u0000')

            else -> log.d("route: $address: unexpected ${header.name}")
        }
    }

    private suspend fun readHeader(): AdbHeader? {
        val bytes = socket.readFully(AdbMessage.HEADER_SIZE)
        val header = AdbMessage.parseHeader(bytes)
        if (header == null) {
            // there is no framing to resynchronise to, so a bad magic ends the connection
            log.w("readHeader: $address: not an adb message header")
            return null
        }
        return header
    }

    private suspend fun closeAll() {
        closed = true
        val open = streamLock.withLock {
            val all = streams.values.toList()
            streams.clear()
            all
        }
        open.forEach { it.onClosed() }
    }

    fun close() {
        closed = true
        scope.cancel()
        socket.close()
    }

    companion object {
        /** what this client tells the device it can do; `host::` alone is accepted too. */
        private const val BANNER = "host::features=shell_v2,cmd,stat_v2,ls_v2"

        /**
         * connect to `adbd` and authenticate, or say why not.
         *
         * The handshake has three shapes and the third is the one worth knowing about:
         *
         * 1. `CNXN` straight back — the device already trusts this key
         * 2. `AUTH` with a token, which is signed; then `CNXN`
         * 3. the signature is rejected, another token arrives, and the **public key** is sent. That
         *    is what raises "Allow debugging?" on the device, and nothing happens until somebody
         *    physically taps it — so [authMs] is generous and the wait is the normal case, not a hang
         *
         * A device with wireless debugging on answers `STLS` instead, and that needs a TLS client
         * certificate this doesn't have yet — reported as [AdbError.UNAUTHORIZED] with a reason
         * saying so, rather than as a dropped connection.
         */
        suspend fun open(
            host: String,
            port: Int,
            connectMs: Long = CONNECT_MS,
            authMs: Long = AUTH_MS,
        ): AdbOutcome<AdbDeviceConnection> {
            val address = "$host:$port"
            val socket = openAdbSocket(host, port, connectMs)
                ?: return AdbOutcome.Err(AdbError.DISCONNECTED, "nothing is listening on $address")

            val connection = AdbDeviceConnection(socket, address)
            val outcome = try {
                withTimeoutOrNull(authMs) { connection.handshake() }
                    ?: AdbOutcome.Err(AdbError.TIMEOUT, "$address didn't finish the handshake")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AdbOutcome.Err(AdbError.DISCONNECTED, e.message ?: "the handshake failed")
            }

            return when (outcome) {
                is AdbOutcome.Err -> {
                    connection.close()
                    outcome
                }
                is AdbOutcome.Ok -> {
                    connection.startReader()
                    log.i("open: $address: connected, ${connection.banner}")
                    AdbOutcome.Ok(connection)
                }
            }
        }

        const val CONNECT_MS = 3_000L

        /** long, because a rejected key waits for someone to tap a dialog on the device. */
        const val AUTH_MS = 60_000L
    }

    /**
     * CNXN, then whatever the device asks for, until it says CNXN back.
     *
     * Runs before the reader loop starts, so it reads the socket directly — there is nothing to
     * route to yet, and the messages here are strictly one at a time.
     */
    private suspend fun handshake(): AdbOutcome<Unit> {
        send(AdbMessage(AdbMessage.A_CNXN, AdbMessage.A_VERSION, maxPayload, banner()))
            .let { if (it is AdbOutcome.Err) return it }

        var signed = false
        var sentPublicKey = false
        while (true) {
            val header = readHeader()
                ?: return AdbOutcome.Err(AdbError.DISCONNECTED, "$address closed the connection")
            val payload = if (header.length > 0) socket.readFully(header.length) else ByteArray(0)

            when (header.command) {
                AdbMessage.A_CNXN -> {
                    banner = payload.decodeToString().trimEnd('\u0000')
                    // arg1 is the device's own limit; the smaller of the two is what a WRTE may carry
                    if (header.arg1 in 1..AdbMessage.MAX_PAYLOAD) maxPayload = header.arg1
                    return AdbOutcome.Ok(Unit)
                }

                AdbMessage.A_AUTH -> {
                    if (header.arg0 != AdbMessage.AUTH_TOKEN) {
                        return AdbOutcome.Err(
                            AdbError.UNAUTHORIZED,
                            "$address sent an AUTH this client can't answer",
                        )
                    }
                    // a token arriving *after* the key was offered means the dialog was dismissed;
                    // one arriving after a signature means the device doesn't hold this key yet
                    if (sentPublicKey) {
                        return AdbOutcome.Err(
                            AdbError.UNAUTHORIZED,
                            "$address refused this app's key",
                        )
                    }
                    if (signed || !answerToken(payload)) {
                        val offer = AdbIdentity.authPayload()
                            ?: return AdbOutcome.Err(
                                AdbError.UNAUTHORIZED,
                                "there is no adb key to authenticate with",
                            )
                        log.i("handshake: $address: offering the key; the device will ask to allow it")
                        val out = AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_RSAPUBLICKEY, 0, offer)
                        send(out).let { if (it is AdbOutcome.Err) return it }
                        sentPublicKey = true
                    } else {
                        signed = true
                    }
                }

                // a wireless-debugging port, which is TLS-only and accepts *no* new key: adbd
                // compares the client certificate against the keys it already trusts and kicks the
                // connection when it doesn't match, with no dialog anywhere. So this is not
                // something the user can allow their way out of — see docs/ADB-TRANSPORTS.md
                /*
                 * a wireless-debugging port. The upgrade happens on this same connection: answer
                 * with A_STLS, hand over a certificate over our own key, and then **wait** — adbd
                 * sends CNXN once the handshake succeeds, so the loop simply carries on rather than
                 * asking again.
                 *
                 * adbd accepts the certificate only for a key it has been paired with, and rejects
                 * it by dropping the connection with nothing said. So a failure here reads as
                 * "not paired", which is the true reason far more often than not.
                 */
                AdbMessage.A_STLS -> {
                    if (!socket.canStartTls) {
                        return AdbOutcome.Err(
                            AdbError.UNAUTHORIZED,
                            "$address needs TLS, which this platform can't do; run " +
                                "`adb tcpip 5555` on the device and use port 5555 instead",
                        )
                    }
                    val key = AdbIdentity.keyPair() ?: return AdbOutcome.Err(
                        AdbError.UNAUTHORIZED,
                        "there is no adb key to present",
                    )
                    val certificate = X509Self.create(key, AdbIdentity.name())
                        ?: return AdbOutcome.Err(AdbError.IO, "couldn't build a certificate")

                    log.d("handshake: $address wants TLS; upgrading")
                    send(AdbMessage(AdbMessage.A_STLS, AdbMessage.A_STLS_VERSION))
                        .let { if (it is AdbOutcome.Err) return it }

                    if (!socket.startTls(key, certificate)) {
                        return AdbOutcome.Err(
                            AdbError.UNAUTHORIZED,
                            "$address refused this app's certificate — pair with it first",
                        )
                    }
                }

                else -> log.d("handshake: $address: ignoring ${header.name}")
            }
        }
    }

    /** sign the token and send it; false when there is no key or it wouldn't sign. */
    private suspend fun answerToken(token: ByteArray): Boolean {
        val key = AdbIdentity.keyPair() ?: return false
        val signature = key.signToken(token) ?: return false
        return send(AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_SIGNATURE, 0, signature)) is
            AdbOutcome.Ok
    }

    private fun banner(): ByteArray = (BANNER + "\u0000").encodeToByteArray()
}
