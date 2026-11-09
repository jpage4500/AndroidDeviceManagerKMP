package com.jpage4500.devicemanager.ui.dialog

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * a title, a scrolling body, and an optional CANCEL/OK row — every dialog in the app.
 *
 * Ported from DashboardWeatherKMP, and the shape all three of Joe's KMP apps now share. **Not
 * [com.jpage4500.devicemanager.ui.components.AppDialog]**, which is what the older desktop-only
 * forms (Command, Logs, Send Input) still use. New dialogs go here.
 *
 * **The same dialog is two different things on the two platforms**, and everything about that comes
 * from [dialogsAreWindows]:
 *
 * - **Desktop** — a real OS window. It has a title bar, it can be dragged anywhere, and it stays up
 *   when a second dialog opens from it, so a drill-down leaves both levels on screen side by side.
 *   The window's own title bar is the heading, so the panel inside is flat and square: no coloured
 *   title band, no rounded card, and Escape/Enter answered by the window.
 * - **Phone** — an overlay: a rounded card with the coloured title bar and its close X, filling the
 *   width, one on screen at a time. A dialog opened from another replaces it and comes back when
 *   the inner one closes. [fullScreen] drops the card and fills the screen instead, for a dialog
 *   that is really a screen.
 */
@Composable
fun BaseDialog(
    title: String? = null,
    titleRes: StringResource? = null,
    icon: DrawableResource? = null,
    iconTint: Color? = null,            // null = the accent colour
    // a dimmer second line under the title. only drawn where the panel draws its own heading, which
    // is the phone: a desktop dialog's heading is the OS title bar, and that has room for one line
    subtitle: String? = null,
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
    // true = shrink the dialog to its measured content instead of a fixed width. for content that
    // sizes itself (a mirrored screen, say), which would otherwise sit in side gaps
    fitContentWidth: Boolean = false,
    // fraction of the screen width the dialog takes on a tablet. compact screens always fill the
    // width; ignored when fitContentWidth is set, and on a desktop, where a window has its own size
    widthFraction: Float = Dimens.dialogWidthFraction,
    // true = fill the screen edge to edge instead of floating in a card, the way the phone's mirror
    // is drawn: no side gaps, no rounded corners, no app showing around it. for a dialog that is
    // really a screen — a device's page, a screenshot. ignored on a desktop, where a dialog is a
    // window the user sizes and moves
    fullScreen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val titleText = title ?: titleRes?.let { stringResource(it) }

    fun confirm() {
        onConfirm?.invoke()
        // optionally auto-dismiss; flows that advance to a new sub-state opt out
        if (dismissOnConfirm) onDismiss()
    }

    // hoisted above the [isTopDialog] check, so a dialog waiting behind an inner one comes back
    // scrolled where the user left it and at the width it already measured
    val scrollState = rememberScrollState()
    var contentWidthPx by remember { mutableStateOf<Int?>(null) }

    // called unconditionally — the registration is what keeps the stack honest — but only a phone
    // acts on it. a desktop gives every level its own window and leaves them all up, which is what
    // a window manager is for
    val isTop = isTopDialog()
    if (!dialogsAreWindows && !isTop) return

    DialogContainer(
        title = titleText ?: Branding.shortName,
        onDismiss = onDismiss,
        // the desktop window answers Enter even when nothing inside has focus; the phone's overlay
        // ignores this and the panel below handles its own keys
        onEnter = if (okText != null && okEnabled) ({ confirm() }) else null,
    ) {
        if (dialogsAreWindows) {
            DialogPanel(
                // a fixed width rather than wrap-content: the title row and the button row both
                // fill their width, so a wrapping column would snap to whatever the widest of them
                // asked for. the height caps and the content scrolls, which is what keeps a long
                // form from asking for a window taller than the screen
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .then(if (fitContentWidth) Modifier else Modifier.width(Dimens.dialogWindowWidth))
                    .heightIn(max = Dimens.dialogWindowMaxHeight),
                titleText = null,   // the OS title bar is the heading
                icon = null,
                iconTint = null,
                subtitle = null,
                titleMaxLines = titleMaxLines,
                okText = okText,
                cancelText = cancelText,
                okEnabled = okEnabled,
                onOk = { confirm() },
                onDismiss = onDismiss,
                horizontalAlignment = horizontalAlignment,
                contentPadding = contentPadding,
                scrollable = scrollable,
                scrollState = scrollState,
                onContentWidth = null,
                content = content,
            )
        } else {
            OverlayCard(
                titleText = titleText,
                icon = icon,
                iconTint = iconTint,
                subtitle = subtitle,
                titleMaxLines = titleMaxLines,
                okText = okText,
                cancelText = cancelText,
                okEnabled = okEnabled,
                onOk = { confirm() },
                onDismiss = onDismiss,
                horizontalAlignment = horizontalAlignment,
                modifier = modifier,
                contentPadding = contentPadding,
                scrollable = scrollable,
                scrollState = scrollState,
                fitContentWidth = fitContentWidth,
                contentWidthPx = contentWidthPx,
                onContentWidth = { contentWidthPx = it },
                widthFraction = widthFraction,
                fullScreen = fullScreen,
                content = content,
            )
        }
    }
}

/** the phone's dialog: a rounded card over the app, with its own title bar — or the whole screen. */
@Composable
private fun OverlayCard(
    titleText: String?,
    icon: DrawableResource?,
    iconTint: Color?,
    subtitle: String?,
    titleMaxLines: Int,
    okText: String?,
    cancelText: String?,
    okEnabled: Boolean,
    onOk: () -> Unit,
    onDismiss: () -> Unit,
    horizontalAlignment: Alignment.Horizontal,
    modifier: Modifier,
    contentPadding: PaddingValues,
    scrollable: Boolean,
    scrollState: ScrollState,
    fitContentWidth: Boolean,
    contentWidthPx: Int?,
    onContentWidth: (Int) -> Unit,
    widthFraction: Float,
    fullScreen: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }

    // measured inside the Dialog window so a nested dialog sees the actual screen size rather than
    // its parent's content width — without this a BoxWithConstraints would inherit the call site's
    // constraints and misread a narrow parent card as a compact screen.
    //
    // safeDrawing is the union of the system bars, the cutout *and* the keyboard, so this is both
    // what keeps a tall dialog's title out from under the clock and what keeps the field in a
    // TextInputDialog above the soft keyboard: the measured height shrinks when the keyboard opens
    BoxWithConstraints(Modifier.safeDrawingPadding()) {
        val isCompactScreen = maxWidth < 600.dp
        val screenWidth = maxWidth
        val maxDialogHeight = maxHeight * Dimens.dialogHeightFraction

        val density = LocalDensity.current
        val sized = when {
            // the caller's padding goes with the card: a full-screen dialog has no margin to hold
            fullScreen -> Modifier.fillMaxSize()
            fitContentWidth -> {
                val fitWidth = contentWidthPx?.let { with(density) { it.toDp() } } ?: maxDialogHeight
                modifier.width(fitWidth.coerceIn(0.dp, screenWidth * 0.9f))
            }
            isCompactScreen -> modifier.fillMaxWidth()
            else -> modifier.width(screenWidth * widthFraction)
        }
        Card(
            modifier = sized
                // a full-screen dialog is already as tall as it gets; the cap is what leaves a
                // strip of the app showing above and below a card
                .then(if (fullScreen) Modifier else Modifier.heightIn(max = maxDialogHeight))
                // focusable so the keys below arrive even when nothing inside has taken focus — a
                // confirmation with no fields in it, say
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> { onDismiss(); true }
                        Key.Enter, Key.NumPadEnter ->
                            if (okText != null && okEnabled) { onOk(); true } else false
                        else -> false
                    }
                },
            shape = if (fullScreen) RectangleShape else RoundedCornerShape(Dimens.dialogCorner),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            DialogPanel(
                modifier = Modifier.fillMaxWidth(),
                titleText = titleText,
                icon = icon,
                iconTint = iconTint,
                subtitle = subtitle,
                titleMaxLines = titleMaxLines,
                okText = okText,
                cancelText = cancelText,
                okEnabled = okEnabled,
                onOk = onOk,
                onDismiss = onDismiss,
                horizontalAlignment = horizontalAlignment,
                contentPadding = contentPadding,
                scrollable = scrollable,
                scrollState = scrollState,
                onContentWidth = if (fitContentWidth) onContentWidth else null,
                onShow = {
                    // declared before the content, so a field inside that asks for focus of its own
                    // runs later and wins — effects fire in composition order, and the last request
                    // is the one that sticks
                    runCatching { focus.requestFocus() }
                },
                content = content,
            )
        }
    }
}

/** the title, the body and the buttons, laid out the same whichever container is around them. */
@Composable
private fun DialogPanel(
    modifier: Modifier,
    titleText: String?,
    icon: DrawableResource?,
    iconTint: Color?,
    subtitle: String?,
    titleMaxLines: Int,
    okText: String?,
    cancelText: String?,
    okEnabled: Boolean,
    onOk: () -> Unit,
    onDismiss: () -> Unit,
    horizontalAlignment: Alignment.Horizontal,
    contentPadding: PaddingValues,
    scrollable: Boolean,
    scrollState: ScrollState,
    onContentWidth: ((Int) -> Unit)?,
    onShow: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        onShow?.let { LaunchedEffect(Unit) { it() } }

        if (titleText != null) {
            DialogTitle(
                title = titleText,
                icon = icon,
                iconTint = iconTint,
                subtitle = subtitle,
                maxLines = titleMaxLines,
                onDismiss = onDismiss,
            )
        }

        val contentModifier = Modifier
            // measures the full content width including padding, so the card can shrink to it. must
            // be outermost: measuring inside .padding() reports the padding-less width, and
            // re-subtracting padding each pass shrinks to zero
            .let { if (onContentWidth != null) it.onSizeChanged { size -> onContentWidth(size.width) } else it }
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
                onOk = onOk,
                onCancel = onDismiss,
                modifier = Modifier.padding(horizontal = Dimens.gapLarge, vertical = Dimens.gap),
            )
        }
    }
}
