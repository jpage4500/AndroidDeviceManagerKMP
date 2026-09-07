package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
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
 * - **Restore** — back to the raw text, undoing the formatting
 * - **Wrap ON/OFF** — line wrapping; off by default, because a log payload is usually read by
 *   scanning down the left edge
 * - **Auto Format ON/OFF** — pretty-print the JSON in it on open, if there is any
 * - **Edit** — hand the text to whatever the OS opens `.txt` with
 *
 * Both toggles are remembered, so the next message opens the way the last one was left.
 */
@Composable
fun MessageScreen(raw: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var wrap by remember { mutableStateOf(SettingsManager.getBoolean(AppPref.MESSAGE_WRAP)) }
    var autoFormat by remember { mutableStateOf(SettingsManager.getBoolean(AppPref.MESSAGE_AUTO_FORMAT)) }
    // null means "show it as it came"; Restore puts it back to that
    var formatted by remember(raw) { mutableStateOf<String?>(null) }
    val copyToClipboard = rememberClipboardCopy()

    // format on open when the setting is on and there is JSON to format; toggling the setting
    // re-decides, which is what makes the button read as a mode rather than an action
    LaunchedEffect(raw, autoFormat) {
        formatted = if (autoFormat && TextUtils.containsJson(raw)) TextUtils.formatJson(raw) else null
    }

    val text = formatted ?: raw

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
                onClick = { formatted = null },
                enabled = formatted != null,
                tint = Colors.toolbarIcon,
            )
            ToolbarButton(
                res = Res.drawable.wrap,
                label = stringResource(if (wrap) Res.string.message_wrap_on else Res.string.message_wrap_off),
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
                onClick = {
                    autoFormat = !autoFormat
                    SettingsManager.setBoolean(AppPref.MESSAGE_AUTO_FORMAT, autoFormat)
                },
                tint = Colors.toolbarIcon,
            )
            ToolbarButton(
                res = Res.drawable.edit,
                label = stringResource(Res.string.message_edit),
                onClick = { scope.launch { openInEditor(text) } },
                tint = Colors.toolbarIcon,
            )

            Spacer(Modifier.weight(1f))
            ToolbarButton(
                res = Res.drawable.copy,
                label = stringResource(Res.string.logs_copy_message),
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
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Colors.primaryText,
                    softWrap = wrap,
                )
            }
            AppVerticalScrollbar(vertical, Modifier.align(Alignment.CenterEnd))
            if (!wrap) AppHorizontalScrollbar(horizontal, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * write the text out and let the OS open it.
 *
 * the Swing app writes to a temp file and calls `Desktop.edit`; this writes into the app's own
 * folder, which needs no new platform code and leaves the file somewhere findable.
 */
private suspend fun openInEditor(text: String) {
    val name = "message-${DateUtils.formatDate("yyyyMMdd-HHmmss")}.txt"
    val ok = StorageManager.writeBytes(PATH_MESSAGES, name, text.encodeToByteArray())
    if (!ok) {
        StatusManager.post("Couldn't write $name", isError = true)
        return
    }
    StorageManager.getAbsolutePath(PATH_MESSAGES, name)?.let(PlatformUtils::openUrl)
}

const val PATH_MESSAGES = "messages"
