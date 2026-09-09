package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.ui.theme.*

/**
 * a popup menu styled after a native desktop context menu.
 *
 * **Not Material3's `DropdownMenu`**, for two reasons that can't be worked around from outside it: it
 * forces a 48dp minimum item height through an internal `sizeIn` applied *after* the caller's
 * modifier, so it can't be shrunk to the ~24dp a desktop menu uses; and it highlights with a ripple
 * rather than the inset rounded pill the platform draws. Ported from FileExplorerKMP.
 *
 * [offset] is measured from the top-left of whatever this is composed inside — so a menu opened at
 * the pointer has to be composed **inside the thing that was clicked**, not beside it. A menu placed
 * in a list's container and offset by a position measured within a row lands at the top of the list.
 *
 * items dismiss the menu themselves, so a call site doesn't have to unset its own flag.
 */
@Composable
fun AppPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable AppMenuScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val positionProvider = remember(offset, density) {
        PointerPositionProvider(with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) })
    }
    val scope = remember(onDismissRequest) { AppMenuScope(onDismissRequest) }

    // a popup is drawn inside its own window's layer, so a menu taller than that window is simply
    // cut off — and a dialog's window is only as tall as the form in it. Scroll instead of clipping
    val container = LocalWindowInfo.current.containerSize.height
    val maxHeight = if (container <= 0) {
        Dp.Infinity
    } else {
        (with(density) { container.toDp() } - Dimens.gapLarge).coerceAtLeast(Dimens.menuItemHeight * 4)
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(Dimens.menuCorner)
        Box(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
            Column(
                modifier = modifier
                    .shadow(Dimens.menuShadow, shape)
                    .clip(shape)
                    .background(Colors.menuBg)
                    .border(Dp.Hairline, Colors.menuBorder, shape)
                    .width(IntrinsicSize.Max)
                    .widthIn(min = Dimens.menuMinWidth)
                    .padding(vertical = Dimens.menuVerticalPadding),
            ) {
                scope.content()
            }
        }
    }
}

class AppMenuScope internal constructor(private val dismiss: () -> Unit) {

    /**
     * @param shortcut drawn right-aligned, as a menu bar shows a key equivalent
     * @param leading drawn before the text — a device's status icon, say — and handed the colour
     *   the text is using, so a tinted icon dims when the row is disabled and turns over with it
     *   when it highlights, rather than staying dark on the highlight
     */
    @Composable
    fun Item(
        text: String,
        enabled: Boolean = true,
        shortcut: String? = null,
        leading: (@Composable (Color) -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val highlighted = hovered && enabled
        val contentColor = when {
            !enabled -> Colors.menuDisabledText
            highlighted -> Colors.menuHighlightText
            else -> Colors.primaryText
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.menuItemInset)
                .height(Dimens.menuItemHeight)
                .clip(RoundedCornerShape(Dimens.menuItemCorner))
                .background(if (highlighted) Colors.menuHighlight else Color.Transparent)
                .hoverable(interaction, enabled = enabled)
                .plainClickable(enabled = enabled) {
                    dismiss()
                    onClick()
                }
                .padding(horizontal = Dimens.menuTextPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading(contentColor)
                Spacer(Modifier.width(Dimens.menuIconSpacing))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = Dimens.menuTextSize,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (shortcut != null) {
                // the spacer is what makes IntrinsicSize.Max size the menu to its widest *item*
                Spacer(Modifier.width(Dimens.menuTrailingGap).weight(1f))
                Text(
                    text = shortcut,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = Dimens.menuTextSize,
                    color = if (highlighted) Colors.menuHighlightText else Colors.secondaryText,
                    maxLines = 1,
                )
            }
        }
    }

    /**
     * a heading over the items under it — what the group is, not one of the choices.
     *
     * Not clickable and not hoverable, which is what tells it apart from a disabled item.
     */
    @Composable
    fun Header(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontSize = Dimens.menuHeaderSize,
            color = Colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                start = Dimens.menuItemInset + Dimens.menuTextPadding,
                end = Dimens.menuTextPadding,
                top = Dimens.menuSeparatorSpacing,
                bottom = Dimens.menuSeparatorSpacing,
            ),
        )
    }

    @Composable
    fun Separator() {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimens.menuSeparatorInset,
                    vertical = Dimens.menuSeparatorSpacing,
                )
                .height(Dp.Hairline)
                .background(Colors.menuSeparator),
        )
    }
}

/**
 * puts a popup at the pointer, and keeps it on screen.
 *
 * off the right edge it is pulled back flush; off the bottom it flips to sit *above* the pointer,
 * which is what a native menu does rather than letting the last item fall off. Shared with
 * [CellTooltip], which wants exactly the same two rules.
 */
internal class PointerPositionProvider(private val offset: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        var x = anchorBounds.left + offset.x
        var y = anchorBounds.top + offset.y
        if (x + popupContentSize.width > windowSize.width) {
            x = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        }
        if (y + popupContentSize.height > windowSize.height) {
            y = (y - popupContentSize.height).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}
