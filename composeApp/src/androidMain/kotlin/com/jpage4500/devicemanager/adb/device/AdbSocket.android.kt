package com.jpage4500.devicemanager.adb.device

import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.security.*
import java.security.cert.*
import java.security.spec.*
import javax.net.ssl.*

private val log = LoggerConfig.createLogger("AdbSocket")

/**
 * `java.net.Socket`, because that is the only thing `SSLSocketFactory` will upgrade in place.
 *
 * ktor is used everywhere else in this app and deliberately not here: `createSocket(socket, …)`
 * takes a `java.net.Socket` and wraps it, continuing the conversation on the same file descriptor,
 * which is exactly what `A_STLS` requires. Blocking IO hopped to [ioDispatcher] — the same trade the
 * pairing handshake makes.
 *
 * The streams are held in `var`s for one reason: [startTls] replaces them. Everything above reads
 * and writes through this object, so the upgrade is invisible to the protocol code.
 */
internal actual class AdbSocket internal constructor(
    private val socket: Socket,
    private val host: String,
    private val port: Int,
) {
    private var input: InputStream = socket.getInputStream()
    private var output: OutputStream = socket.getOutputStream()

    /** the TLS socket once upgraded, kept so it is the one that gets closed. */
    private var secure: SSLSocket? = null

    actual suspend fun write(bytes: ByteArray) = withContext(ioDispatcher) {
        output.write(bytes)
        output.flush()
    }

    actual suspend fun readFully(length: Int): ByteArray = withContext(ioDispatcher) {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(out, read, length - read)
            if (count < 0) throw EOFException("the device closed the connection")
            read += count
        }
        out
    }

    actual val canStartTls: Boolean get() = true

    /**
     * wrap the live socket and hand over the client certificate.
     *
     * `autoClose = true` so closing the TLS socket closes the one underneath; forgetting that leaks
     * a file descriptor per connection, which on a bench of devices is not a rounding error.
     *
     * The device's certificate is **not** validated, and cannot be: `adbd` generates a fresh
     * self-signed one every time it starts. What authenticates it is that it accepted *our*
     * certificate, which it only does for a key it was paired with — see docs/ADB-TRANSPORTS.md.
     */
    actual suspend fun startTls(key: AdbKeyPair, certificateDer: ByteArray): Boolean =
        withContext(ioDispatcher) {
            try {
                val privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(key.privateKey))
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certificateDer.inputStream())

                val store = KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setKeyEntry("adb", privateKey, EMPTY_PASSWORD, arrayOf(certificate))
                }
                val keyManagers =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                        .apply { init(store, EMPTY_PASSWORD) }
                        .keyManagers

                val context = SSLContext.getInstance("TLSv1.3")
                context.init(keyManagers, arrayOf(AcceptAnyPeer), null)

                val upgraded = context.socketFactory
                    .createSocket(socket, host, port, true) as SSLSocket
                upgraded.useClientMode = true
                upgraded.enabledProtocols = arrayOf("TLSv1.3")
                upgraded.startHandshake()

                secure = upgraded
                input = upgraded.inputStream
                output = upgraded.outputStream
                log.d("startTls: $host:$port upgraded")
                true
            } catch (e: GeneralSecurityException) {
                log.e("startTls: $host:$port: ${e.message}")
                false
            } catch (e: IOException) {
                // the common real failure: adbd rejected our certificate because this key has never
                // been paired with it, and it kicks the connection rather than explaining
                log.w("startTls: $host:$port refused the handshake: ${e.message}")
                false
            }
        }

    actual fun close() {
        runCatching { (secure ?: socket).close() }
    }
}

internal actual suspend fun openAdbSocket(host: String, port: Int, connectMs: Long): AdbSocket? =
    withContext(ioDispatcher) {
        try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectMs.toInt())
            AdbSocket(socket, host, port)
        } catch (e: IOException) {
            // nothing listening is the ordinary case, not an error worth a stack trace
            null
        }
    }

/** the in-memory keystore never reaches disk, so its password only has to exist. */
private val EMPTY_PASSWORD = CharArray(0)

/** see [AdbSocket.startTls] — the device's certificate is unverifiable by construction. */
private object AcceptAnyPeer : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
