package com.jpage4500.devicemanager.crypto

import com.jpage4500.devicemanager.adb.device.*

/**
 * a self-signed X.509 certificate over the app's own adb key.
 *
 * TLS insists on a certificate; adbd does not actually care about one. Its verification callback
 * pulls the **public key** out of whatever certificate arrives and compares it against the keys it
 * already trusts, ignoring the issuer, the subject and the dates entirely. So this is a wrapper
 * whose only job is to carry [AdbKeyPair]'s modulus through a TLS handshake — which is also why it
 * wraps *that* key rather than minting a new one: the key the device ends up trusting is then the
 * same key the plain `AUTH` path signs with, so pairing once makes both routes work.
 *
 * The validity window is **fixed, not derived from the clock**. A phone with a wrong date would
 * otherwise generate a certificate that its own TLS stack rejects as not yet valid, and the far end
 * is not checking dates anyway.
 */
internal object X509Self {

    /**
     * @param commonName what the certificate says it is; adbd never reads it, so this is only for
     *   whoever ends up looking at the certificate in a log
     * @return the DER-encoded certificate, or null when the key wouldn't sign
     */
    fun create(key: AdbKeyPair, commonName: String): ByteArray? {
        val algorithm = Der.sequence(Der.oid(Der.OID_SHA256_WITH_RSA), Der.nullValue())

        // RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }, inside a BIT STRING
        val publicKeyInfo = Der.sequence(
            Der.sequence(Der.oid(Der.OID_RSA_ENCRYPTION), Der.nullValue()),
            Der.bitString(
                Der.sequence(
                    Der.integer(key.modulus),
                    Der.integer(AdbPublicKey.EXPONENT),
                ),
            ),
        )

        val name = Der.sequence(
            Der.set(Der.sequence(Der.oid(Der.OID_COMMON_NAME), Der.utf8String(commonName))),
        )

        val tbs = Der.sequence(
            // [0] EXPLICIT version, 2 meaning v3
            Der.explicit(0, Der.integer(2)),
            Der.integer(SERIAL),
            algorithm,
            name,
            Der.sequence(Der.utcTime(NOT_BEFORE), Der.utcTime(NOT_AFTER)),
            // self-signed: the subject and the issuer are the same name
            name,
            publicKeyInfo,
        )

        val signature = key.signSha256(tbs) ?: return null
        return Der.sequence(tbs, algorithm, Der.bitString(signature))
    }

    /**
     * any positive integer will do — nothing chains to this certificate, so there is no issuer for a
     * serial to be unique within.
     */
    private const val SERIAL = 1

    // UTCTime, `YYMMDDHHMMSSZ`. 2049 because UTCTime's two-digit year is only unambiguous below 2050
    private const val NOT_BEFORE = "200101000000Z"
    private const val NOT_AFTER = "491231235959Z"
}
