package com.jpage4500.devicemanager.util

/**
 * extension functions
 */

// ------------------------------------------------------------------------
// STRING EXTENSION FUNCTIONS
// ------------------------------------------------------------------------
/**
 * check if this string equals another string, ignoring case
 * @param other the string to compare to
 * @return true if strings are equal ignoring case
 */
fun String?.equalsIgnoreCase(other: String?): Boolean {
    return this?.equals(other, ignoreCase = true) ?: (other == null)
}

/**
 * check if this string equals any of the provided strings, ignoring case
 * @param values strings to compare to
 * @return true if this string equals any of the provided strings
 */
fun String?.equalsIgnoreCaseAny(vararg values: String): Boolean {
    if (this == null) return false
    return values.any { this.equals(it, ignoreCase = true) }
}

/**
 * check if this string starts with the prefix, ignoring case
 * @param prefix the prefix to check
 * @return true if string starts with prefix ignoring case
 */
fun String?.startsWithIgnoreCase(prefix: String): Boolean {
    return this?.startsWith(prefix, ignoreCase = true) ?: false
}

/**
 * check if this string contains the substring, ignoring case
 * @param substring the substring to check
 * @return true if string contains substring ignoring case
 */
fun String?.containsIgnoreCase(substring: String): Boolean {
    return this?.contains(substring, ignoreCase = true) ?: false
}

/**
 * Reports whether this string contains any of the specified substrings.
 */
fun String?.containsAny(ignoreCase: Boolean = false, vararg values: String): Boolean {
    if (this == null) return false
    if (values.isEmpty()) return false
    values.forEach { value ->
        if (contains(value, ignoreCase)) return true
    }
    return false
}

/**
 * Reports whether this string ends with any of the specified substrings.
 */
fun String?.endsWithAny(ignoreCase: Boolean = false, vararg values: String): Boolean {
    if (this == null) return false
    if (values.isEmpty()) return false
    values.forEach { value ->
        if (endsWith(value, ignoreCase)) return true
    }
    return false
}

/**
 * Reports whether this string starts with any of the specified substrings.
 */
fun String?.startsWithAny(ignoreCase: Boolean = false, vararg values: String): Boolean {
    if (this == null) return false
    if (values.isEmpty()) return false
    values.forEach { value ->
        if (startsWith(value, ignoreCase)) return true
    }
    return false
}

/**
 * first line of a multi-line string, trimmed. useful for logging exception messages -- some
 * (eg. ktor's NoTransformationFoundException) carry a multi-line dump that floods the log
 */
fun String?.firstLine(): String? {
    return this?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: this
}

/**
 * mask string with "*"'s -- all but the first and last characters
 * ie: "abcdef" -> "a****f"
 */
fun String.obfuscate(): String {
    val len = length
    if (len <= 2) return this

    val sb = StringBuilder(len)
    for (i in 0 until len) {
        val c = this[i]
        when {
            i == 0 || i == len - 1 -> sb.append(c)
            c.isWhitespace() -> sb.append(c)
            else -> sb.append('*')
        }
    }
    return sb.toString()
}

// ------------------------------------------------------------------------
// ENUM EXTENSION FUNCTIONS
// ------------------------------------------------------------------------

/**
 * checks if this enum value is contained in any of the provided values
 */
fun <T : Enum<T>> T.isOneOf(vararg values: T): Boolean {
    return this in values
}

// ------------------------------------------------------------------------
// NUMBER EXTENSION FUNCTIONS
// ------------------------------------------------------------------------

/**
 * format a double to specified decimal places
 */
fun Double.formatDecimal(places: Int): String {
    if (places < 0) return this.toString()

    val multiplier = 10.0.pow(places)
    val rounded = (this * multiplier).toLong() / multiplier

    val formatted = rounded.toString()
    val parts = formatted.split(".")

    return if (parts.size == 2) {
        val intPart = parts[0]
        val fracPart = parts[1].padEnd(places, '0').take(places)
        "$intPart.$fracPart"
    } else {
        formatted.padEnd(formatted.length + places + 1, '0').replaceRange(formatted.length, formatted.length, ".")
    }
}

/**
 * calculate power for KMP compatibility
 */
private fun Double.pow(n: Int): Double {
    var result = 1.0
    repeat(n) {
        result *= this
    }
    return result
}
