package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.stringResource

/**
 * a dialog that looks like the rest of the app.
 *
 * **Not Material3's `AlertDialog`**, which draws a 28dp-radius floating card with 24dp padding and
 * pill buttons — a phone dialog on a desktop, and nothing else in this app looks like it. This is a
 * plain panel with the buttons in the bottom-right corner where a desktop puts them, inside a real
 * titled window on the desktop and an overlay on a phone — see [DialogContainer].
 *
 * Enter confirms and Escape cancels, which `AlertDialog` also doesn't do.
 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    extraButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val confirm = onConfirm?.takeIf { confirmEnabled }
    DialogContainer(title = title, onDismiss = onDismiss, onEnter = confirm) {
        // a titled window draws its own frame and heading; an overlay has to draw both itself
        val titled = LocalDialogTitled.current
        val shape = if (titled) RectangleShape else RoundedCornerShape(Dimens.corner)
        Column(
            modifier
                .widthIn(min = 360.dp, max = 720.dp)
                .clip(shape)
                .background(Colors.menuBg)
                .border(Dp.Hairline, if (titled) Color.Transparent else Colors.menuBorder, shape)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> { onDismiss(); true }
                        Key.Enter, Key.NumPadEnter -> confirm?.let { it(); true } ?: false
                        else -> false
                    }
                }
                .padding(Dimens.gapLarge),
        ) {
            if (!titled) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = Colors.primaryText,
                )
                Spacer(Modifier.height(Dimens.gapLarge))
            }
            content()
            Spacer(Modifier.height(Dimens.gapLarge))
            Row(verticalAlignment = Alignment.CenterVertically) {
                extraButton?.invoke()
                Spacer(Modifier.weight(1f))
                DialogButton(stringResource(Res.string.cancel), onClick = onDismiss)
                if (onConfirm != null && confirmLabel != null) {
                    Spacer(Modifier.width(Dimens.gap))
                    DialogButton(confirmLabel, enabled = confirmEnabled, primary = true, onClick = onConfirm)
                }
            }
        }
    }
}

/** a push button, sized and shaped like a desktop's rather than Material's pill. */
@Composable
fun DialogButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(5.dp)
    val background = when {
        !enabled -> Colors.headerBackground
        primary -> Colors.accent
        else -> Colors.listBg
    }
    val text = when {
        !enabled -> Colors.menuDisabledText
        primary -> Colors.rowSelectedText
        else -> Colors.primaryText
    }
    Box(
        modifier
            .clip(shape)
            .background(background)
            .border(Dp.Hairline, if (primary) Color.Transparent else Colors.divider, shape)
            .plainClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.gapLarge, vertical = 5.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = text)
    }
}

/**
 * a single-line text field in the app's own chrome.
 *
 * `OutlinedTextField` brings a floating label, a 56dp minimum height and Material's own focus
 * animation; none of that belongs on a desktop form.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    focusRequester: FocusRequester? = null,
    isError: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = Colors.primaryText),
        cursorBrush = SolidColor(Colors.primaryText),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default),
        modifier = modifier.fieldFocus(focusRequester).fieldChrome(isError),
        decorationBox = { field -> FieldDecoration(value, hint, field) },
    )
}

/**
 * the same field, over a [TextFieldValue].
 *
 * needed wherever the selection matters — a name field that opens with its text selected, so typing
 * replaces the suggestion instead of appending to it.
 */
@Composable
fun AppTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    focusRequester: FocusRequester? = null,
    isError: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = Colors.primaryText),
        cursorBrush = SolidColor(Colors.primaryText),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default),
        modifier = modifier.fieldFocus(focusRequester).fieldChrome(isError),
        decorationBox = { field -> FieldDecoration(value.text, hint, field) },
    )
}

@Composable
private fun Modifier.fieldChrome(isError: Boolean): Modifier {
    val shape = RoundedCornerShape(5.dp)
    return clip(shape)
        .background(Colors.listBg)
        .border(Dp.Hairline, if (isError) Colors.error else Colors.divider, shape)
        .padding(horizontal = Dimens.gap, vertical = 6.dp)
}

private fun Modifier.fieldFocus(focusRequester: FocusRequester?): Modifier =
    if (focusRequester != null) focusRequester(focusRequester) else this

@Composable
private fun FieldDecoration(text: String, hint: String, field: @Composable () -> Unit) {
    if (text.isEmpty() && hint.isNotEmpty()) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
    }
    field()
}

/**
 * take focus once the field is really there.
 *
 * Must be called **inside** a dialog's content. A desktop dialog is a real window whose content is
 * composed after the effects of whatever opened it have already run, so a request made out there
 * fires while there is still no node to give focus to — and silently does nothing.
 */
@Composable
fun FocusOnShow(focusRequester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) { runCatching { focusRequester.requestFocus() } }
}

/** the caption above a field. */
@Composable
fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Colors.secondaryText,
        modifier = Modifier.padding(bottom = 3.dp),
    )
}

/** a checkbox and its label, at the size a desktop draws one. */
@Composable
fun AppCheckbox(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.plainClickable { onCheckedChange(!checked) }.padding(horizontal = Dimens.gap / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(13.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) Colors.accent else Colors.listBg)
                .border(Dp.Hairline, if (checked) Colors.accent else Colors.divider, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                AppIcon(Res.drawable.success, size = 9.dp, tint = Colors.rowSelectedText)
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Colors.primaryText)
    }
}

/**
 * a search field with a magnifier and a clear button.
 *
 * the chrome is drawn by the caller (background, border, padding) so the same component can be a
 * toolbar box on a panel and a plain one inside a list.
 */
@Composable
fun FilterField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = Colors.primaryText),
        cursorBrush = SolidColor(Colors.primaryText),
        modifier = modifier.then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
        ),
        decorationBox = { field ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Res.drawable.filter, size = 11.dp, tint = Colors.secondaryText)
                Spacer(Modifier.width(5.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
                    }
                    field()
                }
                if (value.isNotEmpty()) {
                    AppIcon(
                        res = Res.drawable.close,
                        size = 11.dp,
                        tint = Colors.secondaryText,
                        modifier = Modifier.plainClickable { onValueChange("") },
                    )
                }
            }
        },
    )
}
