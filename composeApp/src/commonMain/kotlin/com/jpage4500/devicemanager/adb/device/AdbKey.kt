package com.jpage4500.devicemanager.adb.device

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlin.io.encoding.*

/**
 * the RSA-2048 key this install signs `AUTH` challenges with — the app's own `~/.android/adbkey`.
 *
 * Behind an `expect` because key generation is the one thing no socket library provides and no
 * amount of `commonMain` can fake: the JVM has `KeyPairGenerator`, iOS has `SecKeyCreateRandomKey`,
 * and Kotlin/Native has no big-integer type to do it by hand with. Everything *around* the key —
 * adb's blob encoding, the auth handshake — is common, which is why only this much is platform code.
 */
expect class AdbKeyPair {
    /** the modulus, big-endian, as [AdbPublicKey.blob] wants it. */
    val modulus: ByteArray

    /**
     * the private key as DER, which is how [AdbIdentity] keeps it between launches.
     *
     * **Whichever DER the platform round-trips**, not a chosen format: the JVM hands out PKCS#8 and
     * `SecKeyCopyExternalRepresentation` hands out PKCS#1. Only the same platform ever reads this
     * back, so making them agree would be work for nobody's benefit.
     */
    val privateKey: ByteArray

    /**
     * sign adbd's challenge.
     *
     * **The token is not hashed.** adbd sends 20 bytes and expects PKCS#1 v1.5 over a SHA-1
     * `DigestInfo` whose digest field is those bytes verbatim — it is treating the token *as* a
     * SHA-1 hash. Signing `sha1(token)` instead produces a well-formed signature that adbd rejects,
     * which is the single easiest thing to get wrong here.
     *
     * @return null when the platform refused to sign
     */
    fun signToken(token: ByteArray): ByteArray?
}

/** a fresh pair, or null when the platform refused. */
internal expect fun createAdbKeyPair(): AdbKeyPair?

/** the pair [der] describes, or null when it can't be read as one. */
internal expect fun restoreAdbKeyPair(der: ByteArray): AdbKeyPair?

/**
 * the one adb key this install owns, made on first use and kept.
 *
 * **Kept, not regenerated.** Authorising a key is a dialog someone has to physically tap on the
 * device, and adbd remembers the key rather than the app — so a new key every launch would ask
 * again every launch, and would leave a trail of dead keys in the device's `adb_keys`.
 */
object AdbIdentity {
    private val log = LoggerConfig.createLogger("AdbIdentity")

    private var cached: AdbKeyPair? = null

    // generating RSA-2048 takes long enough that two devices connecting at once would otherwise
    // both make a key, and the second would overwrite the first after the device had authorised it
    private val mutex = Mutex()

    /** the key, loading or creating it on first call. */
    suspend fun keyPair(): AdbKeyPair? {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: (load() ?: create())?.also { cached = it }
        }
    }

    /**
     * what an `AUTH RSAPUBLICKEY` message carries, ready to send.
     *
     * @return null when there is no key to send, which is a platform that can't make one
     */
    suspend fun authPayload(): ByteArray? {
        val pair = keyPair() ?: return null
        val blob = AdbPublicKey.blob(pair.modulus) ?: run {
            log.e("authPayload: the key isn't RSA-2048")
            return null
        }
        return AdbPublicKey.authPayload(blob, name())
    }

    /** what the device's "Allow debugging?" dialog will show beside the key. */
    fun name(): String = "${Branding.analyticsId}@${PlatformUtils.getDeviceModel()}"

    private fun load(): AdbKeyPair? {
        val stored = StorageManager.readFile(FOLDER, FILE)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val bytes = runCatching { Base64.decode(stored) }.getOrNull() ?: run {
            log.w("load: the stored key isn't base64; a new one will be made")
            return null
        }
        val pair = restoreAdbKeyPair(bytes)
        if (pair == null) log.w("load: the stored key couldn't be read; a new one will be made")
        return pair
    }

    // RSA-2048 generation is hundreds of milliseconds of arithmetic, so never on the caller's thread
    private suspend fun create(): AdbKeyPair? {
        val pair = withContext(ioDispatcher) { createAdbKeyPair() } ?: run {
            log.e("create: this platform can't generate an adb key")
            return null
        }
        // base64 rather than raw bytes, so it goes through the same text write everything else uses
        StorageManager.writeFile(FOLDER, FILE, Base64.encode(pair.privateKey))
        log.i("create: made a new adb key as ${name()}")
        return pair
    }

    /** its own folder, so a key can never be caught by a "delete all logs". */
    private const val FOLDER = "keys"
    private const val FILE = "adbkey"
}
