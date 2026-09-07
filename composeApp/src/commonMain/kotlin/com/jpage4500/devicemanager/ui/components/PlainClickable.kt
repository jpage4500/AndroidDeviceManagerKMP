package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*

/**
 * `clickable` with no ripple and no focus.
 *
 * a table header that takes focus steals the arrow keys from the list it heads, and a ripple on a
 * column header is not something a desktop table does. ported from FileExplorerKMP.
 *
 * a null `interactionSource` lets `clickable` make its own lazily, which is what keeps this a plain
 * extension rather than a `@Composable` or a `Modifier.composed` — both of which cost a recompose
 * scope per header cell.
 */
fun Modifier.plainClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    focusProperties { canFocus = false }
        .clickable(
            interactionSource = null,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
