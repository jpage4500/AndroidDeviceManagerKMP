package com.jpage4500.devicemanager.adb

/** why something failed, once it's out of the socket layer. */
enum class AdbError {
    /** nothing is listening on 5037 — no adb server is running. */
    NO_SERVER,

    /** the server is there but the device isn't, or it went away mid-conversation. */
    DISCONNECTED,

    /** plugged in, but "allow USB debugging" hasn't been tapped. */
    UNAUTHORIZED,

    NOT_FOUND,
    PERMISSION_DENIED,

    /** the path is there, it just isn't a folder. */
    NOT_A_DIRECTORY,
    TIMEOUT,
    IO,
}

/**
 * nothing thrown ever leaves the adb package — this is how a failure leaves instead.
 *
 * the layers above turn it into whatever they need, so nothing further up has to know that a device
 * is different from a local disk.
 */
sealed interface AdbOutcome<out T> {
    data class Ok<T>(val value: T) : AdbOutcome<T>
    data class Err(val reason: AdbError, val message: String) : AdbOutcome<Nothing>
}

fun <T> AdbOutcome<T>.valueOrNull(): T? = (this as? AdbOutcome.Ok)?.value

fun <T> AdbOutcome<T>.errorOrNull(): AdbOutcome.Err? = this as? AdbOutcome.Err

inline fun <T, R> AdbOutcome<T>.map(transform: (T) -> R): AdbOutcome<R> = when (this) {
    is AdbOutcome.Ok -> AdbOutcome.Ok(transform(value))
    is AdbOutcome.Err -> this
}

/** map adb's own words onto something the layers above can act on. */
internal fun classifyAdbFailure(reason: String): AdbError {
    val text = reason.lowercase()
    return when {
        "unauthorized" in text -> AdbError.UNAUTHORIZED
        "offline" in text || ("not found" in text && "device" in text) -> AdbError.DISCONNECTED
        "no such file" in text -> AdbError.NOT_FOUND
        "permission denied" in text || "read-only" in text -> AdbError.PERMISSION_DENIED
        else -> AdbError.IO
    }
}
