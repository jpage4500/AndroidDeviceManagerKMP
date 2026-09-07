package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.*

/**
 * [text] with every case-insensitive occurrence of [needle] given a background.
 *
 * The Swing app highlights search hits in the cell itself rather than filtering the log down to
 * them — which is the right call: a hit is only useful with the lines around it. Two colours, as
 * there: yellow for every match, orange for the one ⌘G is currently on.
 *
 * The `remember` is **unconditional**. An early return before it — the obvious way to write the
 * "nothing to highlight" case — changes how many slots this composable claims, and the slot table
 * is then corrupt the moment the search box goes from empty to typed. It also means the result is
 * cached per cell rather than rebuilt every frame.
 */
@Composable
fun highlighted(text: String, needle: String?, current: Boolean): AnnotatedString =
    remember(text, needle, current) { buildHighlighted(text, needle, current) }

private fun buildHighlighted(text: String, needle: String?, current: Boolean): AnnotatedString {
    if (needle.isNullOrEmpty() || text.isEmpty()) return AnnotatedString(text)
    val background = if (current) HighlightColors.current else HighlightColors.match
    return buildAnnotatedString {
        var at = 0
        while (true) {
            val found = text.indexOf(needle, at, ignoreCase = true)
            if (found < 0) {
                append(text.substring(at))
                break
            }
            append(text.substring(at, found))
            withStyle(SpanStyle(background = background, color = Color.Black)) {
                append(text.substring(found, found + needle.length))
            }
            at = found + needle.length
        }
    }
}

/** true when [needle] appears anywhere in [text]. */
fun matches(text: String?, needle: String?): Boolean =
    !needle.isNullOrEmpty() && text != null && text.contains(needle, ignoreCase = true)

private object HighlightColors {
    val match = Color(0xFFFFFF00)

    /** the Swing app's second highlight — the match the cursor is on. */
    val current = Color(0xFFFB6D08)
}
