package com.jpage4500.devicemanager.crypto

/**
 * bytes from the platform's cryptographic generator.
 *
 * `kotlin.random.Random` is explicitly not one, and the difference matters in exactly one place
 * here: SPAKE2's private scalar is an ephemeral Diffie–Hellman secret, so anyone who can predict it
 * and captured the exchange can derive the session key without ever guessing the pairing code. The
 * pairing code itself wants the same treatment for the same reason.
 *
 * @return null where the platform refused, which a caller must treat as "cannot pair" rather than
 *   falling back to something weaker
 */
internal expect fun secureRandomBytes(size: Int): ByteArray?
