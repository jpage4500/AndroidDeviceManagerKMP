package com.jpage4500.devicemanager.util

import kotlin.math.*

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
}
