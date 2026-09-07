package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*

/**
 * the scrollbar down the side of a lazy list.
 *
 * desktop only in practice: `VerticalScrollbar` lives in compose-desktop, and a phone has no
 * scrollbar to draw — it has a finger and a fading indicator the platform draws itself. the mobile
 * actuals are deliberately empty rather than approximated.
 */
@Composable
expect fun AppVerticalScrollbar(listState: LazyListState, modifier: Modifier = Modifier)

/** the same bar for anything scrolled by `Modifier.verticalScroll` — a message, a settings pane. */
@Composable
expect fun AppVerticalScrollbar(scrollState: ScrollState, modifier: Modifier = Modifier)

/** the same bar along the bottom, for a table wider than its window. */
@Composable
expect fun AppHorizontalScrollbar(scrollState: ScrollState, modifier: Modifier = Modifier)
