package com.jpage4500.devicemanager.util

/** mirrors the helper of the same name in the Swing app, so ported code reads the same. */
object TextUtils {
    fun isEmpty(text: String?): Boolean = text.isNullOrEmpty()

    fun notEmpty(text: String?): Boolean = !text.isNullOrEmpty()

    fun equalsIgnoreCase(a: String?, b: String?): Boolean = a.equals(b, ignoreCase = true)

    /** the first non-empty value, or null. */
    fun firstValid(vararg values: String?): String? = values.firstOrNull { notEmpty(it) }

    /**
     * does this look like it has JSON in it?
     *
     * deliberately crude — a single matched `{}` or `[]` is enough. A log line is not a JSON
     * document; it is a sentence with a payload somewhere inside it, and a real parser would reject
     * the whole thing. Ported from the Swing app.
     */
    fun containsJson(text: String): Boolean {
        var brace = false
        var bracket = false
        for (ch in text) {
            when (ch) {
                '{' -> brace = true
                '[' -> bracket = true
                '}' -> if (brace) return true
                ']' -> if (bracket) return true
            }
        }
        return false
    }

    /**
     * pretty-print whatever JSON is in [text], leaving the rest alone.
     *
     * A character walk rather than parse-and-re-serialise, for the reason above: the input is a log
     * line with a payload in the middle of it, and the prose either side has to survive. Ported
     * from the Swing app's `formatJson`, including its quirks — `"key":` becomes `"key" : `, and
     * runs of whitespace inside the payload are dropped.
     */
    fun formatJson(text: String): String {
        val out = StringBuilder()
        var depth = 0
        var inQuote = false
        var previous = 0.toChar()
        var justIndented = false

        fun indent() {
            repeat(depth) { out.append("    ") }
        }

        fun trimEnd() {
            while (out.isNotEmpty() && out.last().isWhitespace()) out.deleteAt(out.length - 1)
        }

        for (ch in text) {
            if (depth > 0 && ch == '"' && previous != '\\') inQuote = !inQuote

            when {
                // outside the payload, and inside a string, everything is passed through
                inQuote || (depth <= 0 && ch != '{' && ch != '[') -> {
                    out.append(ch)
                    justIndented = false
                }
                ch == '{' || ch == '[' -> {
                    if (ch == '[' || !justIndented) {
                        out.append('\n')
                        indent()
                    }
                    out.append(ch).append('\n')
                    depth++
                    indent()
                    justIndented = true
                }
                ch == '}' || ch == ']' -> {
                    depth--
                    trimEnd()
                    out.append('\n')
                    indent()
                    out.append(ch)
                    justIndented = false
                }
                ch == ',' -> {
                    out.append(ch)
                    trimEnd()
                    out.append('\n')
                    indent()
                    justIndented = true
                }
                ch == ':' && previous == '"' -> {
                    out.append(" : ")
                    justIndented = false
                }
                // runs of whitespace inside the payload carry no meaning
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' -> Unit
                else -> {
                    out.append(ch)
                    justIndented = false
                }
            }
            previous = ch
        }
        return out.toString()
    }
}
