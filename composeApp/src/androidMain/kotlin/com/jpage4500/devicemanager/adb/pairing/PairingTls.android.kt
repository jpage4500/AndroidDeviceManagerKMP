package com.jpage4500.devicemanager.adb.pairing

import com.jpage4500.devicemanager.adb.device.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import java.io.*
import java.security.*
import java.security.cert.*
import java.security.spec.*
import javax.net.ssl.*

private val log = LoggerConfig.createLogger("PairingTls")

/**
 * `SSLSocket`, plus the one reflective call that makes pairing possible on Android at all.
 *
 * Everything here is ordinary JSSE except [exportKeyingMaterial]. Blocking IO, hopped to
 * [ioDispatcher] — `SSLSocket` has no non-blocking mode worth the trouble for a handshake that
 * exchanges four messages and closes.
 */
internal actual class PairingTls internal constructor(private val socket: SSLSocket) {

    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream

    actual suspend fun write(bytes: ByteArray) = withContext(ioDispatcher) {
        output.write(bytes)
        output.flush()
    }

    actual suspend fun readFully(length: Int): ByteArray = withContext(ioDispatcher) {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(out, read, length - read)
            if (count < 0) throw EOFException("the peer closed the connection")
            read += count
        }
        out
    }

    /**
     * `Conscrypt.exportKeyingMaterial`, reached by reflection.
     *
     * Android's TLS provider *is* Conscrypt, and the platform copy lives in
     * `com.android.org.conscrypt` — a hidden package, but a stable one, and the same call
     * `libadb-android` makes. There is no public equivalent: the JDK never exposed RFC 5705.
     *
     * TLS 1.3 is required for it and arrived on API 29; below that the platform provider hands back
     * a socket that cannot export, which is reported rather than worked around.
     */
    actual fun exportKeyingMaterial(label: String, length: Int): ByteArray? = try {
        val conscrypt = Class.forName("com.android.org.conscrypt.Conscrypt")
        val export = conscrypt.getMethod(
            "exportKeyingMaterial",
            SSLSocket::class.java,
            String::class.java,
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
        )
        export.invoke(null, socket, label, null, length) as? ByteArray
    } catch (e: ReflectiveOperationException) {
        log.e("exportKeyingMaterial: this platform's Conscrypt won't export (${e.message})")
        null
    }

    actual fun close() {
        runCatching { socket.close() }
    }
}

internal actual suspend fun openPairingTls(
    host: String,
    port: Int,
    key: AdbKeyPair,
    certificateDer: ByteArray,
): PairingTls? = withContext(ioDispatcher) {
    try {
        // rebuilt from the DER rather than reaching into the platform half of AdbKeyPair, so this
        // file needs nothing of that class beyond its common API
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(key.privateKey))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateDer.inputStream())

        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("adb", privateKey, PASSWORD, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, PASSWORD) }
            .keyManagers

        val context = SSLContext.getInstance("TLSv1.3")
        context.init(keyManagers, arrayOf(AcceptAnyPeer), null)

        val socket = context.socketFactory.createSocket(host, port) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.useClientMode = true
        socket.startHandshake()
        log.d("openPairingTls: handshake done with $host:$port")
        PairingTls(socket)
    } catch (e: GeneralSecurityException) {
        log.e("openPairingTls: ${e.message}")
        null
    } catch (e: IOException) {
        log.w("openPairingTls: $host:$port: ${e.message}")
        null
    }
}

/** the in-memory keystore never touches disk, so its password only has to exist. */
private val PASSWORD = CharArray(0)

/**
 * accepts every peer certificate, deliberately.
 *
 * The device's certificate is self-signed and regenerated whenever `adbd` restarts, so there is
 * nothing to validate it against and no meaning in trying — adb's own client does exactly this,
 * commented "Allow any peer certificate". What authenticates the peer is the pairing code, and the
 * keying material exported from *this* session is mixed into it, so a relayed connection derives a
 * different key and the exchange fails.
 */
private object AcceptAnyPeer : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Conscrypt is the platform's own TLS provider, and its exporter is reachable. */
internal actual val canPairDirectly: Boolean = true
