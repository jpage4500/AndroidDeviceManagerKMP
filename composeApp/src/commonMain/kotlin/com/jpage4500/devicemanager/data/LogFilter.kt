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
    /** [label] is how the filter editor names the field; the constant name is its syntax. */
    enum class Field(val label: String) {
        DATE("Date"),
        APP("App"),
        TID("Thread"),
        LEVEL("Level"),
        TAG("Tag"),
        MSG("Message"),
        ANY("Any"),
        ;

        companion object {
            /**
             * the columns the filter editor offers, in the design's order — plus Any, which is what a
             * text search becomes when it's saved as a filter. Date and Thread only show for a filter
             * that already uses them.
             */
            val OFFERED = listOf(APP, TAG, MSG, LEVEL, ANY)
        }
    }

    enum class Match { EQUALS, STARTS_WITH, ENDS_WITH, CONTAINS }

    /**
     * [names] is pid -> process: an App term passes on either, so `app:com.test` names the app and
     * outlives its process, while the pid a right-click filter writes still works.
     */
    fun accepts(entry: LogEntry, names: Map<String, String> = emptyMap()): Boolean {
        val values = when (field) {
            Field.DATE -> listOf(entry.date)
            Field.APP -> listOf(entry.pid, entry.pid?.let { names[it] })
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
            if (leading) pattern = pattern.drop(1)
            // "+" is the Swing app's own spelling for a level floor ("level:D+"); LOGS.md documents
            // "*". Both mean the same thing on a level, but anywhere else a "+" is part of the value
            // ("tag:HD+*"), so only the one marker comes off and only a level reads "+" as one
            val trailing = pattern.endsWith('*') || (field == Field.LEVEL && pattern.endsWith('+'))
            if (trailing) pattern = pattern.dropLast(1)
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
 * how a term compares, as the filter editor offers it.
 *
 * The editor builds terms from dropdowns rather than asking for the syntax. These are the Swing
 * app's eight (`LogFilterExpression`), in the redesign's lower-case wording, and the editor offers
 * all of them. Each maps onto a [LogTerm.Match] plus a negation.
 */
enum class LogOperator(val label: String, val match: LogTerm.Match, val negate: Boolean) {
    EQUALS("is", LogTerm.Match.EQUALS, false),
    CONTAINS("contains", LogTerm.Match.CONTAINS, false),
    STARTS_WITH("starts with", LogTerm.Match.STARTS_WITH, false),
    ENDS_WITH("ends with", LogTerm.Match.ENDS_WITH, false),
    NOT_EQUALS("is not", LogTerm.Match.EQUALS, true),
    NOT_CONTAINS("does not contain", LogTerm.Match.CONTAINS, true),
    NOT_STARTS_WITH("does not start with", LogTerm.Match.STARTS_WITH, true),
    NOT_ENDS_WITH("does not end with", LogTerm.Match.ENDS_WITH, true),
    ;

    companion object {
        fun of(match: LogTerm.Match, negate: Boolean): LogOperator =
            entries.first { it.match == match && it.negate == negate }

        /** the editor's order: each test, then its negation. */
        val OFFERED = listOf(
            EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS, STARTS_WITH, NOT_STARTS_WITH, ENDS_WITH, NOT_ENDS_WITH,
        )
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

    /**
     * the term in words — `tag is not "Choreographer"` — for a pill or a tooltip.
     *
     * [names] turns an App value, which is a pid, into the process it belongs to when that's known.
     */
    fun describe(names: Map<String, String> = emptyMap()): String {
        val shown = if (field == LogTerm.Field.APP) names[value] ?: value else value
        return "${field.label.lowercase()} ${operator.label.lowercase()} \"$shown\""
    }

    companion object {
        /** an expression back into rows, so an existing filter can be edited in the dialog. */
        fun parse(expression: String): List<LogTermSpec> = expression
            .split(LogFilter.AND, LogFilter.OR)
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
 * a whole filter: [LogTerm]s joined with `&&`, all of which must pass, or with `||`, any of which may.
 *
 * Both can appear, and `&&` binds tighter — `tag:A && level:E* || tag:B` is (A and error) or B. The
 * filter editor only writes one or the other; the mix is for a filter carried over from the Swing
 * app, whose filters were typed.
 *
 * anything that isn't a `field:pattern` term is treated as a bare `*:*text*` — a word on its own
 * does the obvious thing without the syntax.
 */
@Serializable
data class LogFilter(
    val name: String,
    val expression: String,
    val enabled: Boolean = true,
) {
    // alternatives, each a list of terms that must all pass; an empty expression is no alternatives
    @Transient
    private val groups: List<List<LogTerm>> = expression
        .split(OR)
        .map { parseTerms(it) }
        .filter { it.isNotEmpty() }

    /** [names] is pid -> process, which an App term also matches on. */
    fun accepts(entry: LogEntry, names: Map<String, String> = emptyMap()): Boolean =
        groups.isEmpty() || groups.any { group -> group.all { it.accepts(entry, names) } }

    /** whether a rule looks at the App column, and so has to be re-run when process names arrive. */
    val usesApp: Boolean get() = groups.any { group -> group.any { it.field == LogTerm.Field.APP } }

    /** whether this matches when any one of its terms does — what the editor's All / Any shows. */
    val matchesAny: Boolean get() = OR in expression

    /** how many rules it has — what a saved filter's pill counts. */
    val ruleCount: Int get() = LogTermSpec.parse(expression).size

    /**
     * the whole filter in words, its rules joined by "and" or "or" as the editor joins them — the
     * raw expression where it holds something the editor can't (a bare word, `&&` and `||` mixed).
     */
    fun describe(names: Map<String, String> = emptyMap()): String {
        val specs = LogTermSpec.parse(expression)
        val parts = expression.split(AND, OR).count { it.isNotBlank() }
        val mixed = AND in expression && OR in expression
        if (specs.isEmpty() || specs.size != parts || mixed) return expression
        return specs.joinToString(if (matchesAny) " or " else " and ") { it.describe(names) }
    }

    companion object {
        const val AND = "&&"
        const val OR = "||"

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
