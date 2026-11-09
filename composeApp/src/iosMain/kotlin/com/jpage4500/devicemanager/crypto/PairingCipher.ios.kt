package com.jpage4500.devicemanager.crypto

/**
 * refused, and writing them would be the mistake.
 *
 * CommonCrypto has all three, so these *could* be implemented — but they are only ever called by a
 * pairing handshake, and iOS cannot pair: the TLS keying-material exporter the handshake needs has
 * no equivalent in Network.framework or SecureTransport. Implementing AES-GCM to reach a wall is
 * worse than saying so here. See docs/ADB-TRANSPORTS.md.
 */
internal actual fun hkdfSha256(keyMaterial: ByteArray, info: ByteArray, length: Int): ByteArray? = null

internal actual fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray? = null

internal actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? = null
