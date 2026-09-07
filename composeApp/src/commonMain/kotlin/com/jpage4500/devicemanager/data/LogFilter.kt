package com.jpage4500.devicemanager.data

import com.jpage4500.devicemanager.util.*
import kotlinx.serialization.*

/**
 * one `field:pattern` test, as the Swing app's `LOGS.md` documents it.
 *
 * ```
 * level:D          debug only          tag:HD_*      tag starts with
 * level:D*         debug and above     tag:*HD_      tag ends with
 * app:com.test     exact match         tag:!HD_*     does NOT start with
 * msg:"hello you"  contains            *:*hello*     any field contains
 * ```
 *
 * Case-insensitive throughout, and quotes around a pattern are stripped — they exist so a pattern
 * can hold spaces, not to make it exact.
 */
data class LogTerm(
    val field: Field,
    val pattern: String,
    val match: Match,
    val negate: Boolean,
) {
    /** [label] is how the Add Filter dialog names the field; the constant name is its syntax. */
    enum class Field(val label: String) {
        DATE("Date"),
        APP("App"),
        TID("Thread"),
        LEVEL("Level"),
        TAG("Tag"),
        MSG("Message"),
        ANY("Any"),
    }

    enum class Match { EQUALS, STARTS_WITH, ENDS_WITH, CONTAINS }

    fun accepts(entry: LogEntry): Boolean {
        val values = when (field) {
            Field.DATE -> listOf(entry.date)
            Field.APP -> listOf(entry.pid)
            Field.TID -> listOf(entry.tid)
            Field.LEVEL -> listOf(entry.level)
            Field.TAG -> listOf(entry.tag)
            Field.MSG -> listOf(entry.message)
            Field.ANY -> listOf(entry.date, entry.pid, entry.tid, entry.level, entry.tag, entry.message)
        }
        val hit = values.any { it != null && matches(it) }
        return hit != negate
    }

    private fun matches(value: String): Boolean = when (match) {
        // "level:D*" means D and everything more serious, which isn't a prefix test at all
        Match.STARTS_WITH ->
            if (field == Field.LEVEL) levelAtLeast(value, pattern) else value.startsWithIgnoreCase(pattern)
        Match.ENDS_WITH -> value.endsWith(pattern, ignoreCase = true)
        Match.CONTAINS -> value.containsIgnoreCase(pattern)
        Match.EQUALS -> value.equals(pattern, ignoreCase = true)
    }

    companion object {
        // logcat's own order of seriousness
        private const val LEVELS = "VDIWEAF"

        private fun levelAtLeast(value: String, floor: String): Boolean {
            val at = LEVELS.indexOf(value.firstOrNull()?.uppercaseChar() ?: return false)
            val from = LEVELS.indexOf(floor.firstOrNull()?.uppercaseChar() ?: return false)
            return at >= 0 && from >= 0 && at >= from
        }

        /** `field:pattern`, or null if it isn't one. */
        fun parse(text: String): LogTerm? {
            val trimmed = text.trim()
            val colon = trimmed.indexOf(':')
            if (colon <= 0) return null

            val field = when (trimmed.substring(0, colon).lowercase()) {
                "date" -> Field.DATE
                "app" -> Field.APP
                "tid" -> Field.TID
                "level" -> Field.LEVEL
                "tag" -> Field.TAG
                "msg", "message" -> Field.MSG
                "*" -> Field.ANY
                else -> return null
            }

            var pattern = trimmed.substring(colon + 1).trim().trim('"')
            if (pattern.isEmpty()) return null

            val negate = pattern.startsWith('!')
            if (negate) pattern = pattern.drop(1)

            val leading = pattern.startsWith('*')
            // "+" is the Swing app's own spelling for a level floor ("level:D+"); LOGS.md documents
            // "*". Both mean the same thing, so both are taken
            val trailing = (pattern.endsWith('*') || pattern.endsWith('+')) && pattern.length > 1
            pattern = pattern.trim('*', '+')
            if (pattern.isEmpty()) return null

            val match = when {
                leading && trailing -> Match.CONTAINS
                // "*HD_" ends with, "HD_*" starts with — the star says which end is open
                leading -> Match.ENDS_WITH
                trailing -> Match.STARTS_WITH
                else -> Match.EQUALS
            }
            return LogTerm(field, pattern, match, negate)
        }
    }
}

/**
 * how a term compares, as the Add Filter dialog offers it.
 *
 * The dialog builds terms from dropdowns rather than asking for the syntax; these are the Swing
 * app's own eight options and its own wording (`LogFilterExpression`). Each maps onto a
 * [LogTerm.Match] plus a negation.
 */
enum class LogOperator(val label: String, val match: LogTerm.Match, val negate: Boolean) {
    EQUALS("is", LogTerm.Match.EQUALS, false),
    CONTAINS("contains", LogTerm.Match.CONTAINS, false),
    STARTS_WITH("starts with", LogTerm.Match.STARTS_WITH, false),
    ENDS_WITH("ends with", LogTerm.Match.ENDS_WITH, false),
    NOT_EQUALS("is NOT", LogTerm.Match.EQUALS, true),
    NOT_CONTAINS("does NOT contain", LogTerm.Match.CONTAINS, true),
    NOT_STARTS_WITH("does NOT start with", LogTerm.Match.STARTS_WITH, true),
    NOT_ENDS_WITH("does NOT end with", LogTerm.Match.ENDS_WITH, true),
    ;

    companion object {
        fun of(match: LogTerm.Match, negate: Boolean): LogOperator =
            entries.first { it.match == match && it.negate == negate }
    }
}

/**
 * one row of the Add Filter dialog: a field, an operator and a value.
 *
 * the dialog edits these; [asExpression] turns them back into the text syntax the rest of the app
 * (and the Swing app's saved filters) uses, so nothing downstream has to know the dialog exists.
 */
data class LogTermSpec(
    val field: LogTerm.Field = LogTerm.Field.TAG,
    val operator: LogOperator = LogOperator.STARTS_WITH,
    val value: String = "",
) {
    /** `tag:!HD_*` — the star says which end is open, the bang negates. */
    fun asExpression(): String {
        val name = if (field == LogTerm.Field.ANY) "*" else field.name.lowercase()
        val bang = if (operator.negate) "!" else ""
        val pattern = when (operator.match) {
            LogTerm.Match.STARTS_WITH -> "$value*"
            LogTerm.Match.ENDS_WITH -> "*$value"
            LogTerm.Match.CONTAINS -> "*$value*"
            LogTerm.Match.EQUALS -> value
        }
        // the quotes go outside both the bang and the stars: [LogTerm.parse] strips them before it
        // looks at either, and a pattern only needs them so it can hold a space
        val body = "$bang$pattern"
        return if (value.any { it.isWhitespace() }) "$name:\"$body\"" else "$name:$body"
    }

    companion object {
        /** an expression back into rows, so an existing filter can be edited in the dialog. */
        fun parse(expression: String): List<LogTermSpec> = expression
            .split(LogFilter.AND)
            .mapNotNull { part -> LogTerm.parse(part.trim()) }
            .map { term ->
                LogTermSpec(
                    field = term.field,
                    operator = LogOperator.of(term.match, term.negate),
                    value = term.pattern,
                )
            }
    }
}

/**
 * a whole filter: several [LogTerm]s joined with `&&`, all of which must pass.
 *
 * anything that isn't a `field:pattern` term is treated as a bare `*:*text*` — so typing a word into
 * the filter box does the obvious thing without having to learn the syntax.
 */
@Serializable
data class LogFilter(
    val name: String,
    val expression: String,
    val enabled: Boolean = true,
) {
    @Transient
    private val terms: List<LogTerm> = parseTerms(expression)

    fun accepts(entry: LogEntry): Boolean = terms.all { it.accepts(entry) }

    companion object {
        const val AND = "&&"

        /** the presets the Swing app puts at the top of the list, in its own order and syntax. */
        val BUILT_IN = listOf(
            LogFilter(ALL_MESSAGES, ""),
            LogFilter("Log Level Debug+", "level:D+"),
            LogFilter("Log Level Info+", "level:I+"),
            LogFilter("Log Level Warn+", "level:W+"),
            LogFilter("Log Level Error+", "level:E"),
        )

        /** the one that means "no filter at all"; selecting it clears the rest. */
        const val ALL_MESSAGES = "All Messages"

        fun parseTerms(expression: String): List<LogTerm> = expression
            .split(AND)
            .mapNotNull { part ->
                val text = part.trim()
                if (text.isEmpty()) null
                else LogTerm.parse(text) ?: LogTerm(LogTerm.Field.ANY, text, LogTerm.Match.CONTAINS, false)
            }
    }
}
