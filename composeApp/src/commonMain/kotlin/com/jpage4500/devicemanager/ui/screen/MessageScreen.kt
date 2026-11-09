package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

/**
 * one message, in a window of its own.
 *
 * Ported from the Swing app's `MessageViewScreen`. Its four buttons, in its order:
 *
 * - **Restore** — back to the text exactly as it opened, undoing the formatting and any edits
 * - **Wrap ON/OFF** — line wrapping; off by default, because a log payload is usually read by
 *   scanning down the left edge
 * - **Auto Format ON/OFF** — pretty-print the JSON in it on open, if there is any
 * - **Edit** — hand the text, as it stands now, to the OS's default editor for `.txt`
 *
 * The text itself is editable. Both toggles are remembered, so the next message opens the way the
 * last one was left.
 */
@Composable
fun MessageScreen(raw: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var wrap by remember { mutableStateOf(SettingsManager.getBoolean(AppPref.MESSAGE_WRAP)) }
    var autoFormat by remember { mutableStateOf(SettingsManager.getBoolean(AppPref.MESSAGE_AUTO_FORMAT)) }
    // what the box holds now — formatted, edited, or as it came. Restore puts [raw] back
    var text by remember(raw) { mutableStateOf(raw) }
    val copyToClipboard = rememberClipboardCopy()

    // format on open when the setting is on and there is JSON to format; toggling the setting
    // re-decides from the original, which is what makes the button read as a mode, not an action
    LaunchedEffect(raw, autoFormat) {
        text = if (autoFormat && TextUtils.containsJson(raw)) TextUtils.formatJson(raw) else raw
    }

    Column(modifier.fillMaxSize().background(Colors.listBg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Dimens.toolbarHeight)
                .background(Colors.toolbarBg)
                .padding(horizontal = Dimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarButton(
                res = Res.drawable.restart,
                label = stringResource(Res.string.message_restore),
                tooltip = stringResource(Res.string.message_restore_tip),
                onClick = { text = raw },
                enabled = text != raw,
                tint = Colors.toolbarIcon,
            )
            ToolbarButton(
                res = Res.drawable.wrap,
                label = stringResource(if (wrap) Res.string.message_wrap_on else Res.string.message_wrap_off),
                tooltip = stringResource(Res.string.message_wrap_tip),
                onClick = {
                    wrap = !wrap
                    SettingsManager.setBoolean(AppPref.MESSAGE_WRAP, wrap)
                },
                tint = Colors.toolbarIcon,
            )
            ToolbarButton(
                res = Res.drawable.status_busy,
                label = stringResource(
                    if (autoFormat) Res.string.message_format_on else Res.string.message_format_off
                ),
                tooltip = stringResource(Res.string.message_format_tip),
                onClick = {
                    autoFormat = !autoFormat
                    SettingsManager.setBoolean(AppPref.MESSAGE_AUTO_FORMAT, autoFormat)
                },
                tint = Colors.toolbarIcon,
            )
            ToolbarButton(
                res = Res.drawable.edit,
                label = stringResource(Res.string.message_edit),
                tooltip = stringResource(Res.string.message_edit_tip),
                onClick = { scope.launch { openInEditor(text) } },
                tint = Colors.toolbarIcon,
            )

            Spacer(Modifier.weight(1f))
            ToolbarButton(
                res = Res.drawable.copy,
                label = stringResource(Res.string.logs_copy_message),
                tooltip = stringResource(Res.string.message_copy_tip),
                onClick = { copyToClipboard(text) },
                tint = Colors.toolbarIcon,
            )
        }
        HorizontalDivider(color = Colors.divider)

        // unwrapped text scrolls both ways; wrapped only needs the one
        val vertical = rememberScrollState()
        val horizontal = rememberScrollState()
        Box(Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(vertical)
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal))
                    .padding(Dimens.gap),
            ) {
                // a text field has no softWrap; under a horizontal scroll it is measured with no
                // width limit, which is what keeps unwrapped lines on one line
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = if (wrap) Modifier.fillMaxWidth() else Modifier,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = Colors.primaryText,
                    ),
                    cursorBrush = SolidColor(Colors.primaryText),
                )
            }
            AppVerticalScrollbar(vertical, Modifier.align(Alignment.CenterEnd))
            if (!wrap) AppHorizontalScrollbar(horizontal, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * write the text out and open it in the OS's default editor.
 *
 * the Swing app writes to a temp file and calls `Desktop.edit`; this writes into the app's own
 * folder, which leaves the file somewhere findable, and opens it the same way.
 */
private suspend fun openInEditor(text: String) {
    val name = "message-${DateUtils.formatDate("yyyyMMdd-HHmmss")}.txt"
    val ok = StorageManager.writeBytes(PATH_MESSAGES, name, text.encodeToByteArray())
    val path = if (ok) StorageManager.getAbsolutePath(PATH_MESSAGES, name) else null
    if (path == null || !editLocalFile(path)) {
        StatusManager.post("Couldn't open $name", isError = true)
    }
}

const val PATH_MESSAGES = "messages"
