package com.jpage4500.devicemanager.util

import kotlinx.datetime.*
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * dates without java.time.
 *
 * the pattern language is a deliberate subset of SimpleDateFormat's — enough that a format string
 * carried over from the Swing app still reads the same — implemented over kotlinx-datetime so iOS
 * gets it too. ported from DashboardWeatherKMP.
 */
object DateUtils {
    private val log = LoggerConfig.createLogger("DateUtils")

    private val DAY_NAMES_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val DAY_NAMES_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val MONTHS_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val MONTHS_LONG = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    // Order matters here! Match longest tokens first (e.g., yyyy before yy)
    private val DATE_TOKEN_REGEX = Regex("yyyy|yy|MMMM|MMM|MM|M|dd|d|HH|H|hh|h|mm|m|SSS|ss|s|EEEE|EEE|aa|a|zzz|Z")

    /**
     * @return current LocalDateTime
     */
    fun getNow(): LocalDateTime {
        return Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    }

    fun getDayOfWeekShort(date: LocalDate?): String {
        // returns a short day-of-week label or empty if date is null
        if (date == null) return ""
        return DAY_NAMES_SHORT[date.dayOfWeek.ordinal]
    }

    fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null
        // attempts to parse formats like "Mon, 1/13"
        return try {
            val parts = dateStr.split(", ")
            if (parts.size != 2) return null
            val monthDay = parts[1].split("/")
            if (monthDay.size != 2) return null
            val month = monthDay[0].toIntOrNull() ?: return null
            val day = monthDay[1].toIntOrNull() ?: return null
            val now = getNow().date
            LocalDate(now.year, month, day)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * parse an ISO-8601 date-time (e.g. "2026-06-14T08:56:30-04:00") into a
     * local date-time in the current zone, or null if it isn't valid ISO-8601. the token-based
     * [parseDate] can't handle the 'T' separator or zone offset — use this for ISO sources.
     */
    fun parseIsoDateTime(text: String?): LocalDateTime? {
        if (text.isNullOrBlank()) return null
        return try {
            Instant.parse(text).toLocalDateTime(TimeZone.currentSystemDefault())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * parse a date string using the same simplified pattern language as [formatDate].
     * day-of-week tokens (EEE/EEEE) and timezone tokens (zzz/Z) are matched but ignored —
     * the result is always interpreted as a local date/time. if the pattern uses 12-hour
     * tokens (h/hh) without an AM/PM token (a/aa), the parsed value is taken as-is so a
     * driver outputting 24-hour data into a 12-hour pattern still parses correctly.
     */
    fun parseDate(text: String, pattern: String): LocalDateTime? {
        if (text.isBlank() || pattern.isEmpty()) return null

        // build a regex from the pattern, capturing each token; literal chars are escaped
        val tokens = mutableListOf<String>()
        val regexStr = StringBuilder()
        var lastEnd = 0
        DATE_TOKEN_REGEX.findAll(pattern).forEach { match ->
            if (match.range.first > lastEnd) {
                regexStr.append(Regex.escape(pattern.substring(lastEnd, match.range.first)))
            }
            tokens.add(match.value)
            regexStr.append(tokenToRegex(match.value))
            lastEnd = match.range.last + 1
        }
        if (lastEnd < pattern.length) {
            regexStr.append(Regex.escape(pattern.substring(lastEnd)))
        }

        val matchResult = Regex(regexStr.toString(), RegexOption.IGNORE_CASE).matchEntire(text.trim())
            ?: return null

        return try {
            var year = getNow().year
            var month = 1
            var day = 1
            var hour = 0
            var minute = 0
            var second = 0
            var nanosecond = 0
            var has12Hour = false
            var hasAmPm = false
            var pmOffset = 0

            for ((i, token) in tokens.withIndex()) {
                val value = matchResult.groupValues[i + 1]
                when (token) {
                    "yyyy" -> year = value.toInt()
                    "yy" -> {
                        val yy = value.toInt()
                        year = if (yy >= 70) 1900 + yy else 2000 + yy
                    }
                    "MMMM" -> {
                        val idx = MONTHS_LONG.indexOfFirst { it.equals(value, ignoreCase = true) }
                        if (idx < 0) return null
                        month = idx + 1
                    }
                    "MMM" -> {
                        val idx = MONTHS_SHORT.indexOfFirst { it.equals(value, ignoreCase = true) }
                        if (idx < 0) return null
                        month = idx + 1
                    }
                    "MM", "M" -> month = value.toInt()
                    "dd", "d" -> day = value.toInt()
                    "HH", "H" -> hour = value.toInt()
                    "hh", "h" -> {
                        hour = value.toInt(); has12Hour = true
                    }
                    "mm", "m" -> minute = value.toInt()
                    "ss", "s" -> second = value.toInt()
                    "SSS" -> nanosecond = value.toInt() * 1_000_000
                    "aa", "a" -> {
                        hasAmPm = true
                        if (value.equals("PM", ignoreCase = true)) pmOffset = 12
                    }
                    // EEEE, EEE, zzz, Z — matched and discarded
                }
            }

            // 12-hour -> 24-hour, but only if AM/PM was actually parsed
            if (has12Hour && hasAmPm) {
                if (hour == 12) hour = 0
                hour += pmOffset
            }

            LocalDateTime(year, month, day, hour, minute, second, nanosecond)
        } catch (e: Exception) {
            log.e("parseDate: failed to parse '$text' with pattern '$pattern': ${e.message}")
            null
        }
    }

    private fun tokenToRegex(token: String): String = when (token) {
        "yyyy" -> "(\\d{4})"
        "yy" -> "(\\d{2})"
        "MMMM" -> "(${MONTHS_LONG.joinToString("|")})"
        "MMM" -> "(${MONTHS_SHORT.joinToString("|")})"
        "MM" -> "(\\d{2})"
        "M" -> "(\\d{1,2})"
        "dd" -> "(\\d{2})"
        "d" -> "(\\d{1,2})"
        "HH" -> "(\\d{2})"
        "H" -> "(\\d{1,2})"
        "hh" -> "(\\d{2})"
        "h" -> "(\\d{1,2})"
        "mm" -> "(\\d{2})"
        "m" -> "(\\d{1,2})"
        "ss" -> "(\\d{2})"
        "s" -> "(\\d{1,2})"
        "SSS" -> "(\\d{3})"
        "EEEE" -> "(\\w+)"
        "EEE" -> "(\\w{3})"
        "aa", "a" -> "(AM|PM)"
        "Z" -> "([+-]\\d{4}|Z)"
        "zzz" -> "(\\w+)"
        else -> Regex.escape(token)
    }

    /**
     * convert time (ms) to LocalDateTime
     */
    fun timeToDate(lastModified: Long): LocalDateTime {
        if (lastModified > 0) {
            try {
                val instant = Instant.fromEpochMilliseconds(lastModified)
                return Instant.parse(instant.toString()).toLocalDateTime(TimeZone.currentSystemDefault())
            } catch (_: Exception) {
            }
        }
        return getNow()
    }

    fun formatDate(pattern: String): String {
        return formatDate(getNow(), pattern)
    }

    fun formatDate(localDate: LocalDate, pattern: String): String {
        return formatDate(LocalDateTime(localDate, getNow().time), pattern)
    }

    fun formatDate(localTime: LocalTime, pattern: String): String {
        return formatDate(LocalDateTime(getNow().date, localTime), pattern)
    }

    /**
     * format a LocalDateTime using a custom format pattern
     * supports simplified format patterns:
     * - yy / yyyy = year (26 / 2026)
     * - M / MM / MMM / MMMM = month (3 / 03 / Mar / March)
     * - d / dd = day of month (4 / 04)
     * - h / hh = 12-hour
     * - H / HH = 24-hour
     * - m / mm = minute
     * - s / ss = second
     * - EEE = short day-of-week (Mon, Tue, ...)
     * - EEEE = day-of-week (Monday, Tueday, ...)
     * - a = AM/PM
     * - SSS = Milliseconds (000)
     * - Z = Timezone offset (-0600)
     */
    fun formatDate(localDateTime: LocalDateTime, pattern: String): String {
        val hour24 = localDateTime.hour
        val hour12 = ((hour24 + 11) % 12) + 1
        val minute = localDateTime.minute
        val second = localDateTime.second
        val millisecond = localDateTime.nanosecond / 1_000_000
        val year = localDateTime.year
        val month = localDateTime.month
        val day = localDateTime.day
        val ampm = if (hour24 < 12) "AM" else "PM"

        return try {
            // This does the replacement in one pass, safely ignoring the replaced values.
            DATE_TOKEN_REGEX.replace(pattern) { match ->
                when (match.value) {
                    "yyyy" -> year.toString().padStart(4, '0')
                    "yy" -> (year % 100).toString().padStart(2, '0')
                    "MMMM" -> month.name.lowercase().replaceFirstChar { it.uppercase() } // March
                    "MMM" -> month.name.take(3).lowercase().replaceFirstChar { it.uppercaseChar() } // Mar
                    "MM" -> month.number.toString().padStart(2, '0') // 03
                    "M" -> month.number.toString() // 3
                    "dd" -> day.toString().padStart(2, '0')
                    "d" -> day.toString()
                    "HH" -> hour24.toString().padStart(2, '0')
                    "H" -> hour24.toString()
                    "hh" -> hour12.toString().padStart(2, '0')
                    "h" -> hour12.toString()
                    "mm" -> minute.toString().padStart(2, '0')
                    "m" -> minute.toString()
                    "SSS" -> millisecond.toString().padStart(3, '0')
                    "ss" -> second.toString().padStart(2, '0')
                    "s" -> second.toString()
                    "EEEE" -> DAY_NAMES_LONG[localDateTime.dayOfWeek.ordinal]
                    "EEE" -> DAY_NAMES_SHORT[localDateTime.dayOfWeek.ordinal]
                    "aa", "a" -> ampm
                    "Z" -> {
                        // Calculate TimeZone offset for 'Z'
                        val timeZone = TimeZone.currentSystemDefault()
                        val offset = timeZone.offsetAt(localDateTime.toInstant(timeZone))
                        // UtcOffset toString is usually +01:00, -06:00, or Z.
                        // We strip the colon or replace Z with +0000 to match the standard Z format (-0600).
                        offset.toString().replace(":", "").replace("Z", "+0000")
                    }
                    "zzz" -> "" // timezone abbreviation (e.g. "EDT") — supported on parse only
                    else -> match.value
                }
            }
        } catch (e: Exception) {
            // log.e("formatDatePattern: Exception: ${e.message}")
            val fallbackMinute = minute.toString().padStart(2, '0')
            "$hour12:$fallbackMinute"
        }
    }

    fun nowMs(): Long {
        return Clock.System.now().toEpochMilliseconds()
    }

}
