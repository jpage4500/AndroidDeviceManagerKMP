package com.jpage4500.devicemanager.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import com.jpage4500.devicemanager.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.dialog.common.*
import com.jpage4500.devicemanager.ui.setting.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.stringResource

/**
 * what this build is, and the four things someone opens Settings for when something is wrong.
 *
 * Debug Mode sits here rather than under App because the other three rows are the reason anyone
 * turns it on: it raises what the log file keeps from Info to Debug (see `AppLogWriter`), so the
 * order to work through is *turn it on, reproduce, then send*.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    BaseDialog(
        titleRes = Res.string.settings_about,
        icon = Res.drawable.about,
        onDismiss = onDismiss,
    ) {
        AboutBody()
    }
}

/** the rows on their own — Settings' About tab on the desktop, and [AboutDialog]'s body. */
@Composable
fun AboutBody() {
    var support by remember { mutableStateOf(false) }
    var updates by remember { mutableStateOf(false) }
    var viewLogs by remember { mutableStateOf(false) }

    val debug by SettingsManager.getBoolAsState(AppPref.DEBUG_MODE)

    Column {
        SettingItem(
            icon = Res.drawable.edit,
            labelRes = Res.string.about_support,
            onClick = { support = true },
        )
        SettingItem(
            icon = Res.drawable.update,
            labelRes = Res.string.about_updates,
            onClick = { updates = true },
        )
        SettingSwitchItem(
            icon = Res.drawable.terminal,
            labelRes = Res.string.about_debug,
            checked = debug,
            onCheckedChange = { SettingsManager.setBoolean(AppPref.DEBUG_MODE, it) },
        )
        SettingItem(
            icon = Res.drawable.file_logs,
            labelRes = Res.string.about_view_logs,
            showDivider = false,
            onClick = { viewLogs = true },
        )

        Spacer(Modifier.height(Dimens.gapLarge))
        Text(
            text = "${Branding.appName}  ·  v$APP_VERSION  ·  $BUILD_DATE",
            style = MaterialTheme.typography.labelSmall,
            color = Colors.secondaryText,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.settingPadding),
        )
    }

    if (support) SupportDialog { support = false }
    if (updates) CheckUpdatesDialog { updates = false }
    if (viewLogs) LogFileDialog { viewLogs = false }
}

/**
 * say what went wrong, then send the log with it.
 *
 * The description isn't metadata alongside the upload — [LogManager.uploadLogs] writes it into the
 * log as a `SUPPORT:` line first, so it arrives at the top of the same file it describes and nothing
 * has to be correlated by timestamp afterwards.
 */
@Composable
private fun SupportDialog(onDismiss: () -> Unit) {
    // resolved here: the upload finishes on a background scope, where a string resource can't be read
    val sent = stringResource(Res.string.log_upload_ok)
    val failed = stringResource(Res.string.log_upload_failed)
    val sending = stringResource(Res.string.about_support_sending)

    TextInputDialog(
        titleRes = Res.string.about_support,
        descRes = Res.string.about_support_desc,
        placeholder = stringResource(Res.string.about_support_hint),
        okText = stringResource(Res.string.settings_send),
        onDismiss = onDismiss,
        onConfirm = { text ->
            ToastManager.showToast(sending)
            LogManager.uploadLogs(text = text) { result ->
                // uploadLogs already toasts the one failure it knows the reason for — a build with
                // no logging URL in it — so this only reports what the network did
                if (result.isSuccess) ToastManager.showToast(sent, success = true)
                else ToastManager.showToast(failed, success = false)
            }
            onDismiss()
        },
    )
}
