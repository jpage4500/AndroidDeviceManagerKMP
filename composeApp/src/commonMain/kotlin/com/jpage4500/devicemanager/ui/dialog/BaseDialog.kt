package com.jpage4500.devicemanager.ui.dialog

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * a title bar, a scrolling body, and an optional CANCEL/OK row — every dialog in the app.
 *
 * Ported from DashboardWeatherKMP, and the shape all three of Joe's KMP apps now share. **Not
 * [com.jpage4500.devicemanager.ui.components.AppDialog]**, which is still what the desktop-only
 * windows (Command, Logs, Send Input) use: that one is a plain panel inside a real OS window, this
 * one is a card drawn over the app on every platform. New dialogs go here.
 *
 * Enter confirms and Escape cancels, which the weather app leaves to the platform and a desktop
 * does not.
 */
@Composable
fun BaseDialog(
    title: String? = null,
    titleRes: StringResource? = null,
    icon: DrawableResource? = null,
    titleMaxLines: Int = 1,             // lines the title may wrap to before it ellipsizes
    okText: String? = null,             // optional OK button
    cancelText: String? = null,         // optional CANCEL button
    okEnabled: Boolean = true,          // only applies when okText is not null
    onConfirm: (() -> Unit)? = null,
    dismissOnConfirm: Boolean = true,   // if true, OK also calls onDismiss
    onDismiss: () -> Unit,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    modifier: Modifier = Modifier.padding(Dimens.gapLarge),
    contentPadding: PaddingValues = PaddingValues(10.dp),
    // false = omit the parent verticalScroll. needed when the content embeds its own
    // scrollable/gesture-consuming child, since the parent scroll otherwise steals drag events
    scrollable: Boolean = true,
    // true = shrink the dialog to its measured content instead of a fixed screen fraction. for
    // content that sizes itself (a mirrored screen, say), which would otherwise sit in side gaps
    fitContentWidth: Boolean = false,
    // fraction of the screen width the dialog takes on a desktop or tablet. compact screens always
    // fill the width; ignored when fitContentWidth is set
    widthFraction: Float = Dimens.dialogWidthFraction,
    content: @Composable ColumnScope.() -> Unit,
) {
    val titleText = title ?: titleRes?.let { stringResource(it) }

    val properties = DialogProperties(
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        usePlatformDefaultWidth = false,
    )

    fun confirm() {
        onConfirm?.invoke()
        // optionally auto-dismiss; flows that advance to a new sub-state opt out
        if (dismissOnConfirm) onDismiss()
    }

    // both hoisted above the [isTopDialog] check, so a dialog waiting behind an inner one comes
    // back scrolled where the user left it and at the width it already measured
    val scrollState = rememberScrollState()
    // when fitting content width, we measure the content once and snap the dialog to it. null until
    // that first measure, which falls back to the max height below (a good guess for square
    // content) so there's no visible wide-then-shrink flash
    var contentWidthPx by remember { mutableStateOf<Int?>(null) }

    // a dialog opened from another one is a step deeper into one flow, not a second thing on
    // screen: only the innermost one draws. every level stays composed either way, so nothing it
    // holds is lost
    if (!isTopDialog()) return

    val focus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss, properties = properties) {
        // measured inside the Dialog window so a nested dialog sees the actual screen size rather
        // than its parent's content width — without this a BoxWithConstraints would inherit the
        // call site's constraints and misread a narrow parent card as a compact screen
        BoxWithConstraints {
            val isCompactScreen = maxWidth < 600.dp
            val screenWidth = maxWidth
            val maxDialogHeight = maxHeight * Dimens.dialogHeightFraction

            val density = LocalDensity.current
            val baseModifier = when {
                fitContentWidth -> {
                    val fitWidth = contentWidthPx?.let { with(density) { it.toDp() } } ?: maxDialogHeight
                    modifier.width(fitWidth.coerceIn(0.dp, screenWidth * 0.9f))
                }
                isCompactScreen -> modifier.fillMaxWidth()
                else -> modifier.width(screenWidth * widthFraction)
            }
            Card(
                modifier = baseModifier
                    .heightIn(max = maxDialogHeight)
                    // focusable so the keys below arrive even when nothing inside has taken focus —
                    // a confirmation with no fields in it, say. preview order runs root-to-leaf, so
                    // a focused field inside still lets Enter through to here first
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> { onDismiss(); true }
                            Key.Enter, Key.NumPadEnter ->
                                if (okText != null && okEnabled) { confirm(); true } else false
                            else -> false
                        }
                    },
                shape = RoundedCornerShape(Dimens.dialogCorner),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // declared before the content, so a field inside that asks for focus of its own
                    // runs later and wins — effects fire in composition order, and the last request
                    // is the one that sticks. this is only the fallback for a dialog with nothing
                    // focusable in it, which is most of them
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

                    if (titleText != null) {
                        DialogTitle(title = titleText, icon = icon, maxLines = titleMaxLines, onDismiss = onDismiss)
                    }

                    val contentModifier = Modifier
                        // measures the full content width including padding, so the card can shrink
                        // to it. must be outermost: measuring inside .padding() reports the
                        // padding-less width, and re-subtracting padding each pass shrinks to zero
                        .let { if (fitContentWidth) it.onSizeChanged { size -> contentWidthPx = size.width } else it }
                        .padding(contentPadding)
                        .weight(1f, fill = false)
                        .let { if (scrollable) it.verticalScroll(scrollState) else it }

                    Column(
                        horizontalAlignment = horizontalAlignment,
                        verticalArrangement = Arrangement.Top,
                        modifier = contentModifier,
                        content = content,
                    )

                    if (okText != null || cancelText != null) {
                        DialogButtons(
                            okText = okText,
                            cancelText = cancelText,
                            okEnabled = okEnabled,
                            onOk = { confirm() },
                            onCancel = onDismiss,
                            modifier = Modifier.padding(horizontal = Dimens.gapLarge, vertical = Dimens.gap),
                        )
                    }
                }
            }
        }
    }
}
