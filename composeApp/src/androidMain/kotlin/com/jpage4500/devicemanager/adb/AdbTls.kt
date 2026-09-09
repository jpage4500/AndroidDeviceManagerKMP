package com.jpage4500.devicemanager.adb

import com.jpage4500.devicemanager.adb.device.*
import java.net.*
import java.security.*
import java.security.cert.*
import java.security.spec.*
import javax.net.ssl.*

/**
 * the TLS material both adb TLS paths need: pairing, and the `STLS` upgrade on a device connection.
 *
 * Shared because getting either half wrong fails in a way that reads like something else — see
 * [ForcedKeyManager] in particular, which is the fix for a `certificate_required` alert that arrives
 * *after* a handshake has apparently succeeded.
 */
internal object AdbTls {

    /**
     * key managers that will always present [certificateDer], whatever the peer asks for.
     *
     * **Not the default key manager, and this is the whole point.** adbd sends a
     * `certificate_authorities` list built from the public keys it already trusts
     * (`adbd_tls_client_ca_list` in `daemon/auth.cpp`), and JSSE's default manager only offers a key
     * whose issuer appears in that list. A self-signed certificate's issuer never does, so it
     * declines to send anything at all — and in TLS 1.3 the client's certificate travels after the
     * server's Finished, so the handshake *completes* and the peer's `certificate_required` alert
     * only surfaces on the first read. The symptom looks like a protocol error rather than a missing
     * certificate.
     *
     * adbd is not really doing PKI here: it compares the public key inside whatever certificate
     * arrives against the keys it knows. So presenting ours unconditionally is both correct and the
     * only thing that works.
     */
    fun keyManagers(key: AdbKeyPair, certificateDer: ByteArray): Array<KeyManager> {
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(key.privateKey))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateDer.inputStream()) as X509Certificate
        return arrayOf(ForcedKeyManager(privateKey, certificate))
    }

    /**
     * accepts every peer certificate, deliberately.
     *
     * `adbd` generates a fresh self-signed certificate whenever it starts, so there is nothing to
     * validate it against and no meaning in trying — adb's own client does exactly this, commented
     * "Allow any peer certificate". What authenticates the peer on the pairing port is the code, with
     * this session's exported keying material mixed in; on the connect port it is that the device
     * accepted a key it was paired with.
     */
    val acceptAnyPeer: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** one key, one certificate, offered to anyone who asks — see [keyManagers]. */
    private class ForcedKeyManager(
        private val key: PrivateKey,
        private val certificate: X509Certificate,
    ) : X509ExtendedKeyManager() {

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) =
            arrayOf(ALIAS)

        // both ignore `issuers` on purpose: filtering by it is what produces no certificate at all
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ) = ALIAS

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ) = ALIAS

        override fun getCertificateChain(alias: String?) = arrayOf(certificate)

        override fun getPrivateKey(alias: String?) = key

        // this side is never a server
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ) = null

        private companion object {
            const val ALIAS = "adb"
        }
    }
}
