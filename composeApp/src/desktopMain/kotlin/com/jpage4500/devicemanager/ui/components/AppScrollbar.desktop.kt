package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import com.jpage4500.devicemanager.ui.theme.*

private val tableScrollbarStyle
    @Composable get() = ScrollbarStyle(
        minimalHeight = Dimens.scrollbarMinThumb,
        thickness = Dimens.scrollbarThickness,
        shape = RoundedCornerShape(Dimens.scrollbarThickness / 2),
        hoverDurationMillis = 300,
        unhoverColor = Colors.scrollbarThumb,
        hoverColor = Colors.scrollbarThumbHover,
    )

// ScrollbarStyle carries no padding of its own, so the whole bar is inset instead
@Composable
actual fun AppVerticalScrollbar(listState: LazyListState, modifier: Modifier) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        style = tableScrollbarStyle,
        modifier = modifier.fillMaxHeight().padding(Dimens.scrollbarInset),
    )
}

@Composable
actual fun AppVerticalScrollbar(scrollState: ScrollState, modifier: Modifier) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        style = tableScrollbarStyle,
        modifier = modifier.fillMaxHeight().padding(Dimens.scrollbarInset),
    )
}

@Composable
actual fun AppHorizontalScrollbar(scrollState: ScrollState, modifier: Modifier) {
    HorizontalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        style = tableScrollbarStyle,
        modifier = modifier.fillMaxWidth().padding(Dimens.scrollbarInset),
    )
}
