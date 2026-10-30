package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import com.jpage4500.devicemanager.ui.theme.*

/**
 * two or three choices in one outlined control, the picked one filled — the filter editor's
 * All / Any, and Scripts' All N / Choose….
 */
@Composable
fun <T> SegmentedToggle(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.navCorner)
    Row(
        modifier
            .height(Dimens.chipHeight)
            .clip(shape)
            .background(Colors.listBg)
            .border(Dimens.hairline, Colors.divider, shape)
            .padding(Dimens.hairline * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Dimens.navCorner - Dimens.hairline * 2))
                    .background(if (on) Colors.chipOn else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = Dimens.rowPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) Colors.chipOnText else Colors.primaryText,
                )
            }
        }
    }
}
