package com.jpage4500.devicemanager.adb.device

import com.jpage4500.devicemanager.util.*
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Security.*
import platform.posix.*

private val log = LoggerConfig.createLogger("AdbKey")

/**
 * `SecKey`, which is the Security framework — the only RSA on iOS.
 *
 * The key is held as a retained `SecKeyRef` and never released: there is exactly one adb identity
 * per process and it is wanted for as long as the process lives, so a lifetime any more careful than
 * that would be ceremony around a single object.
 *
 * The awkward part is the modulus. iOS will export a public key but has no accessor for its parts,
 * so the export comes back as PKCS#1 DER and [AdbPublicKey.modulusOfPkcs1] picks the modulus out of
 * it. That is why the parser lives in `commonMain` rather than here.
 */
@OptIn(ExperimentalForeignApi::class)
actual class AdbKeyPair internal constructor(private val key: SecKeyRef) {

    actual val modulus: ByteArray
        get() {
            val public = SecKeyCopyPublicKey(key) ?: run {
                log.e("modulus: the key has no public half")
                return ByteArray(0)
            }
            try {
                val der = export(public) ?: return ByteArray(0)
                return AdbPublicKey.modulusOfPkcs1(der) ?: run {
                    log.e("modulus: the exported key isn't a PKCS#1 RSAPublicKey")
                    ByteArray(0)
                }
            } finally {
                CFRelease(public)
            }
        }

    /** PKCS#1, which is what `SecKeyCopyExternalRepresentation` gives for an RSA private key. */
    actual val privateKey: ByteArray get() = export(key) ?: ByteArray(0)

    /**
     * `kSecKeyAlgorithmRSASignatureDigestPKCS1v15SHA1`, which takes a **digest** rather than a
     * message and wraps it in the SHA-1 `DigestInfo` itself.
     *
     * So adbd's token goes in untouched: adb treats those 20 bytes as if they were a SHA-1 hash, and
     * this is the one Apple algorithm that agrees. The JVM half of this reaches the same bytes the
     * long way round, with `NONEwithRSA` and the header prepended by hand.
     */
    actual fun signToken(token: ByteArray): ByteArray? {
        val data = token.toCFData() ?: return null
        try {
            val signature = SecKeyCreateSignature(
                key,
                kSecKeyAlgorithmRSASignatureDigestPKCS1v15SHA1,
                data,
                null,
            ) ?: run {
                log.e("signToken: the key refused to sign")
                return null
            }
            try {
                return signature.toByteArray()
            } finally {
                CFRelease(signature)
            }
        } finally {
            CFRelease(data)
        }
    }

    private fun export(of: SecKeyRef): ByteArray? {
        val data = SecKeyCopyExternalRepresentation(of, null) ?: run {
            log.e("export: the key can't be exported")
            return null
        }
        return try {
            data.toByteArray()
        } finally {
            CFRelease(data)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun createAdbKeyPair(): AdbKeyPair? = memScoped {
    val bits = alloc<IntVar>()
    bits.value = 2048
    val size = CFNumberCreate(null, kCFNumberIntType, bits.ptr)
    val attributes = cfDictionaryOf(
        kSecAttrKeyType to kSecAttrKeyTypeRSA,
        kSecAttrKeySizeInBits to size,
    )
    try {
        val key = SecKeyCreateRandomKey(attributes, null)
        if (key == null) log.e("createAdbKeyPair: SecKeyCreateRandomKey refused")
        key?.let { AdbKeyPair(it) }
    } finally {
        CFRelease(size)
        CFRelease(attributes)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun restoreAdbKeyPair(der: ByteArray): AdbKeyPair? {
    val data = der.toCFData() ?: return null
    val attributes = cfDictionaryOf(
        kSecAttrKeyType to kSecAttrKeyTypeRSA,
        kSecAttrKeyClass to kSecAttrKeyClassPrivate,
    )
    return try {
        val key = SecKeyCreateWithData(data, attributes, null)
        if (key == null) log.w("restoreAdbKeyPair: the stored key couldn't be read back")
        key?.let { AdbKeyPair(it) }
    } finally {
        CFRelease(attributes)
        CFRelease(data)
    }
}

/** a `CFDictionary` of CoreFoundation values, which is what every Security call takes. */
@OptIn(ExperimentalForeignApi::class)
private fun cfDictionaryOf(vararg entries: Pair<CFStringRef?, COpaquePointer?>): CFDictionaryRef? {
    val dictionary = CFDictionaryCreateMutable(
        null,
        entries.size.convert(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )
    entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
    return dictionary
}

/** @return null for an empty array, which no caller here has and `addressOf(0)` would refuse */
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): CFDataRef? {
    if (isEmpty()) return null
    return usePinned { pinned ->
        CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.convert())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length <= 0) return ByteArray(0)
    val bytes = CFDataGetBytePtr(this) ?: return ByteArray(0)
    val out = ByteArray(length)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length.convert()) }
    return out
}
