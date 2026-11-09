package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*

/**
 * a pick-one control, in the app's own chrome.
 *
 * Material's `ExposedDropdownMenuBox` brings a filled text field, a floating label and a 56dp
 * minimum height — a phone control. This is a bordered box with the current value and a chevron,
 * opening the same [AppPopupMenu] the context menus use, so a picker and a menu look alike.
 */
@Composable
fun <T> AppDropdown(
    value: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    // the menu opens flush under the box, so its own measured height is the offset
    var height by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val shape = RoundedCornerShape(5.dp)
    Box(modifier.onSizeChanged { height = with(density) { it.height.toDp() } }) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Colors.listBg)
                .border(Dp.Hairline, Colors.divider, shape)
                .plainClickable { open = true }
                .padding(horizontal = Dimens.gap, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label(value),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AppIcon(Res.drawable.arrow_down, size = 9.dp, tint = Colors.secondaryText)
        }
        AppPopupMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // under the box rather than at the pointer, which is what a picker does
            offset = DpOffset(0.dp, height),
        ) {
            options.forEach { option ->
                Item(label(option)) { onSelect(option) }
            }
        }
    }
}
