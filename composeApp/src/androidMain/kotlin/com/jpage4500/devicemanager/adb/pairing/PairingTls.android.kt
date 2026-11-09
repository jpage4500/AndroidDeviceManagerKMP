package com.jpage4500.devicemanager.adb.pairing

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.adb.device.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.conscrypt.Conscrypt
import java.io.*
import java.security.*
import java.security.cert.*
import java.security.spec.*
import javax.net.ssl.*

private val log = LoggerConfig.createLogger("PairingTls")

/**
 * `SSLSocket` from a **bundled** Conscrypt, which is what makes pairing possible on Android at all.
 *
 * Android's own TLS *is* Conscrypt and its `Conscrypt` class has the exporter this needs — but it
 * lives in `com.android.org.conscrypt`, and hidden-API enforcement makes a blocked member throw
 * `NoSuchMethodException` exactly as though it did not exist. Reflection onto it was tried and fails
 * that way on a modern `targetSdk`. Bundling the same code puts the class in `org.conscrypt`, where
 * it is ours to call directly — no reflection, nothing to be denied, and a compile error rather than
 * a runtime surprise if the API ever moves.
 *
 * The provider is **not** installed globally. `Security.insertProviderAt` would change TLS for every
 * socket in the app, including the http client, to fix one handshake; it is passed to this
 * `SSLContext` and nowhere else.
 *
 * Blocking IO, hopped to [ioDispatcher] — `SSLSocket` has no non-blocking mode worth the trouble for
 * a handshake that exchanges four messages and closes.
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
     * RFC 5705 keying material, straight from the bundled Conscrypt.
     *
     * A null context, not an empty one — Conscrypt's own documentation says the two are treated
     * differently, and adb passes `nullptr`.
     */
    actual fun exportKeyingMaterial(label: String, length: Int): ByteArray? = try {
        Conscrypt.exportKeyingMaterial(socket, label, null, length)
    } catch (e: SSLException) {
        log.e("exportKeyingMaterial: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        // thrown when the socket isn't one of Conscrypt's own, which would mean the provider below
        // was ignored — worth saying plainly rather than reporting it as a handshake problem
        log.e("exportKeyingMaterial: not a Conscrypt socket (${e.message})")
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
        // the bundled provider, passed here and not installed globally — see the class comment
        val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider())
        // the same forcing key manager the STLS upgrade uses: the pairing server happens not to send
        // a certificate_authorities list, so the default one works here, but relying on that
        // difference between two adbd ports would be a trap
        context.init(AdbTls.keyManagers(key, certificateDer), arrayOf(AdbTls.acceptAnyPeer), null)

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

/**
 * whether the bundled Conscrypt actually loaded.
 *
 * Not a constant `true`: the app ships one ABI, so an emulator or a device outside it has no
 * `libconscrypt_jni.so` and `Conscrypt.isAvailable()` says so. Asking here means the Connect panel
 * disables the QR and says pairing needs an adb server, instead of offering a code that would fail
 * at the exporter — the whole reason this is checked before anything is drawn.
 */
internal actual val canPairDirectly: Boolean
    get() = try {
        Conscrypt.isAvailable()
    } catch (e: Throwable) {
        // UnsatisfiedLinkError and NoClassDefFoundError both land here, and both mean the same thing
        log.w("canPairDirectly: Conscrypt didn't load (${e.message})")
        false
    }
