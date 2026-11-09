package com.jpage4500.devicemanager.util

import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.time.Duration

object Utils {
    /** base-1024 short form for logs: `6k`, `1.3M`. */
    fun bytesToDisplayString(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "-"
        val units = arrayOf("b", "k", "M", "G", "T")
        val order = min((floor(log10(bytes.toDouble()) / 3)).toInt(), units.size - 1)
        val value = bytes / 1024.0.pow(order.toDouble())
        return if (order == 0 || value >= 10) "${value.roundToInt()}${units[order]}"
        else "${(round(value * 10) / 10)}${units[order]}"
    }

    /** `%04x`, which every adb host command is prefixed with. */
    fun hexLength(length: Int): String {
        val hex = length.toString(16)
        return "0".repeat((4 - hex.length).coerceAtLeast(0)) + hex
    }

    /** wrap a path so a space or a quote in it can't split the shell command it lands in. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** a coarse duration for a person to read: "30 secs", "2 hours". */
    fun formatDuration(duration: Duration): String = when {
        duration.inWholeHours > 0 -> "${duration.inWholeHours} ${if (duration.inWholeHours == 1L) "hour" else "hours"}"
        duration.inWholeMinutes > 0 -> "${duration.inWholeMinutes} ${if (duration.inWholeMinutes == 1L) "min" else "mins"}"
        else -> "${duration.inWholeSeconds} secs"
    }

    /**
     * the same for a measured elapsed time, where the milliseconds matter: "500ms", "1m 30s".
     *
     * days are a tier of their own because a device's uptime reaches them and "148h 12m" is a number
     * nobody reads as six days — the Swing app's `formatTime` makes the same cut.
     */
    fun formatDuration(durationMs: Long): String {
        if (durationMs < 1000) return "${durationMs}ms"

        val seconds = durationMs / 1000
        if (seconds < 60) {
            val ms = durationMs % 1000
            return if (ms > 0) "${seconds}s ${ms}ms" else "${seconds}s"
        }

        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        if (minutes < 60) {
            return if (remainingSeconds > 0) "${minutes}m ${remainingSeconds}s" else "${minutes}m"
        }

        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        if (hours < 24) {
            return if (remainingMinutes > 0) "${hours}h ${remainingMinutes}m" else "${hours}h"
        }

        val days = hours / 24
        val remainingHours = hours % 24
        return if (remainingHours > 0) "${days}d ${remainingHours}h" else "${days}d"
    }

    /** the first URL in some text — a crash report, a shell command's output. */
    fun findUrl(text: String?): String? {
        if (text == null) return null
        // excludes the delimiters a URL is usually quoted or bracketed with, so a trailing " or >
        // isn't taken as part of the address
        return Regex("(?:https?|ftp|rtsp)://[^\\s\"'<>)\\]]*").find(text)?.value
    }

    /** a stored name back to its enum constant, or null if it names nothing. */
    inline fun <reified T : Enum<T>> enumFromName(name: String?): T? {
        if (name == null) return null
        return runCatching { enumValueOf<T>(name) }.getOrNull()
    }

    fun isPlatform(vararg platformArr: PlatformType): Boolean =
        platformArr.contains(PlatformUtils.getPlatform())

    fun isMobilePlatform(): Boolean = isPlatform(PlatformType.IOS, PlatformType.ANDROID)

    fun isMainThread(): Boolean = !Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext)

    fun runOnMainThread(function: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch { function() }
    }
}
