package com.jpage4500.devicemanager.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.Colors
import com.jpage4500.devicemanager.ui.theme.Dimens
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * the app's own log file — not a device's logcat, which is [com.jpage4500.devicemanager.ui.screen.LogsScreen].
 *
 * The **tail**, not the file: the log rotates at 2 MB and putting that in one `Text` is a layout
 * pass measuring two million characters. 64 KB is a few hundred lines, which is as far back as
 * anyone reads before they send the whole thing instead.
 */
@Composable
fun LogFileDialog(onDismiss: () -> Unit) {
    // null while it's being read; the read is a file the size of a small book, off the UI thread
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        text = withContext(ioDispatcher) {
            val whole = StorageManager.readFile(PATH_LOGS, FILE_LOG).orEmpty()
            val tail = whole.takeLast(MAX_CHARS)
            // start at a line boundary, so the oldest line shown isn't half of one
            if (tail.length < whole.length) tail.substringAfter('\n') else tail
        }
    }

    // desktop only: 2 MB of log is worth opening in a real editor, which is what the App section's
    // "App log file" row used to do. a phone has no editor to open it in, and would be handing
    // openUrl() a bare filesystem path
    val filePath = remember {
        if (Utils.isPlatform(PlatformType.DESKTOP)) StorageManager.getAbsolutePath(PATH_LOGS, FILE_LOG) else null
    }

    BaseDialog(
        titleRes = Res.string.about_view_logs,
        icon = Res.drawable.file_logs,
        okText = filePath?.let { stringResource(Res.string.about_open_file) },
        onConfirm = { filePath?.let { PlatformUtils.openUrl(it) } },
        // this scrolls itself, so it can start at the bottom — a log is read newest-first
        scrollable = false,
        onDismiss = onDismiss,
    ) {
        val scroll = rememberScrollState()
        val body = text

        LaunchedEffect(body) {
            // maxValue is 0 until the text has been laid out once
            if (body != null) {
                withFrameNanos { }
                scroll.scrollTo(scroll.maxValue)
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = Dimens.logViewHeight),
            contentAlignment = Alignment.Center,
        ) {
            when {
                body == null -> CircularProgressIndicator()
                body.isBlank() -> Text(
                    text = stringResource(Res.string.about_no_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Colors.secondaryText,
                )
                else -> SelectionContainer(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Colors.primaryText,
                        modifier = Modifier.padding(horizontal = Dimens.settingPadding),
                    )
                }
            }
        }
    }
}

// as far back as the viewer reads; past this, sending the file is the answer
private const val MAX_CHARS = 64 * 1024
