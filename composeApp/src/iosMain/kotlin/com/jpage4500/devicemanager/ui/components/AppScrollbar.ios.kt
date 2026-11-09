package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.*

// no bar on a touch screen — the platform draws its own fading indicator while a list is moving
@Composable
actual fun AppVerticalScrollbar(listState: LazyListState, modifier: Modifier) = Unit

@Composable
actual fun AppVerticalScrollbar(gridState: LazyGridState, modifier: Modifier) = Unit

@Composable
actual fun AppVerticalScrollbar(scrollState: ScrollState, modifier: Modifier) = Unit

@Composable
actual fun AppHorizontalScrollbar(scrollState: ScrollState, modifier: Modifier) = Unit
