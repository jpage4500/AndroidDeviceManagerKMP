package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.stringResource

/**
 * every setting, in the order the Swing app's `SettingsDialog` puts them.
 *
 * The **transport** section is the one thing that has no Swing equivalent: that app is always a
 * client of a local adb server, where this one can also point at a server across the LAN or at
 * another instance's HTTP API — see docs/ADB-TRANSPORTS.md. Changing it restarts the device list.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var input by remember { mutableStateOf<InputRequest?>(null) }
    // every setter writes straight through, so this only exists to redraw what was written
    var revision by remember { mutableStateOf(0) }
    fun changed() { revision++ }

    val resetTitle = stringResource(Res.string.settings_reset_title)
    val resetMessage = stringResource(Res.string.settings_reset_msg)

    Column(
        modifier
            .fillMaxSize()
            .background(Colors.headerBackground)
            .verticalScroll(rememberScrollState())
            .padding(Dimens.gapLarge),
    ) {
        key(revision) {
            Section(stringResource(Res.string.settings_connection)) {
                val transport by DeviceManager.transport.collectAsState()
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gapLarge, vertical = 5.dp)) {
                    Text(
                        text = stringResource(Res.string.settings_transport),
                        style = MaterialTheme.typography.bodySmall,
                        color = Colors.secondaryText,
                        modifier = Modifier.width(180.dp),
                    )
                    Text(transport.label, style = MaterialTheme.typography.bodySmall, color = Colors.primaryText)
                }
                TextSetting(
                    label = stringResource(Res.string.settings_adb_host),
                    pref = AppPref.ADB_SERVER_HOST,
                    onEdit = { input = it },
                    onChanged = { applyTransport(); changed() },
                )
                IntSetting(
                    label = stringResource(Res.string.settings_adb_port),
                    pref = AppPref.ADB_SERVER_PORT,
                    onEdit = { input = it },
                    onChanged = { applyTransport(); changed() },
                )
                TextSetting(
                    label = stringResource(Res.string.settings_remote_url),
                    pref = AppPref.REMOTE_URL,
                    onEdit = { input = it },
                    onChanged = { applyTransport(); changed() },
                )
                TextSetting(
                    label = stringResource(Res.string.settings_remote_token),
                    pref = AppPref.REMOTE_TOKEN,
                    onEdit = { input = it },
                    onChanged = { applyTransport(); changed() },
                    secret = true,
                )
            }

            Spacer(Modifier.height(Dimens.gapLarge))

            Section(stringResource(Res.string.settings_device_list)) {
                IntSetting(stringResource(Res.string.settings_refresh_secs), AppPref.DETAILS_REFRESH_SECS, { input = it }, ::changed)
                IntSetting(stringResource(Res.string.settings_full_refresh), AppPref.FULL_REFRESH_MINS, { input = it }, ::changed)
                IntSetting(stringResource(Res.string.settings_mirror_ms), AppPref.MIRROR_INTERVAL_MS, { input = it }, ::changed)
                BoolSetting(stringResource(Res.string.settings_tooltips), AppPref.SHOW_TOOLTIPS, ::changed)
            }

            Spacer(Modifier.height(Dimens.gapLarge))

            Section(stringResource(Res.string.settings_logs)) {
                IntSetting(stringResource(Res.string.settings_log_buffer), AppPref.LOGS_MAX_LINES, { input = it }, ::changed)
                IntSetting(stringResource(Res.string.settings_log_hold), AppPref.LOGS_HOLD_LINES, { input = it }, ::changed)
                IntSetting(stringResource(Res.string.settings_log_font), AppPref.LOGS_FONT_SIZE, { input = it }, ::changed)
            }

            Spacer(Modifier.height(Dimens.gapLarge))

            Section(stringResource(Res.string.settings_app)) {
                BoolSetting(stringResource(Res.string.settings_tray), AppPref.MINIMIZE_TO_TRAY, ::changed)
                BoolSetting(stringResource(Res.string.settings_on_top), AppPref.ALWAYS_ON_TOP, ::changed)
                BoolSetting(stringResource(Res.string.settings_debug), AppPref.DEBUG_MODE, ::changed)
                BoolSetting(stringResource(Res.string.settings_confirm), AppPref.CONFIRM_DESTRUCTIVE, ::changed)
                ActionSetting(stringResource(Res.string.settings_open_folder), stringResource(Res.string.settings_open)) {
                    StorageManager.getAbsolutePath(PATH_LOGS, FILE_LOG)?.let { PlatformUtils.openUrl(it) }
                }
                ActionSetting(stringResource(Res.string.settings_upload_logs), stringResource(Res.string.settings_send)) {
                    LogManager.uploadLogs(text = "user requested")
                }
                ActionSetting(stringResource(Res.string.settings_reset), stringResource(Res.string.settings_reset_button)) {
                    confirm = ConfirmRequest(resetTitle, resetMessage) {
                        SettingsManager.clearAll()
                        changed()
                    }
                }
            }
        }

        Spacer(Modifier.height(Dimens.gapLarge))
        Text(
            text = "v$APP_VERSION  ·  $BUILD_DATE",
            style = MaterialTheme.typography.labelSmall,
            color = Colors.secondaryText,
        )

        Spacer(Modifier.height(Dimens.gapLarge))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(stringResource(Res.string.close)) }
        }
    }

    InputDialog(input) { input = null }
    ConfirmDialog(confirm) { confirm = null }
}

/** point the device list at whatever the settings now say. */
private fun applyTransport() = DeviceManager.useTransport(DeviceManager.transportFromSettings())

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = Colors.secondaryText,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = Dimens.gap / 2),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.corner))
            .background(Colors.listBg)
            .border(Dp.Hairline, Colors.divider, RoundedCornerShape(Dimens.corner))
            .padding(vertical = Dimens.gap),
        content = content,
    )
}

@Composable
private fun SettingRow(label: String, value: String, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.plainClickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.gapLarge, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            modifier = Modifier.width(180.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = Colors.primaryText)
    }
}

@Composable
private fun TextSetting(
    label: String,
    pref: AppPref,
    onEdit: (InputRequest) -> Unit,
    onChanged: () -> Unit,
    secret: Boolean = false,
) {
    val value = SettingsManager.getStringOrEmpty(pref)
    val shown = when {
        value.isEmpty() -> stringResource(Res.string.settings_not_set)
        // a token is long, and its value is not something to leave on screen
        secret -> value.obfuscate()
        else -> value
    }
    SettingRow(label, shown) {
        onEdit(InputRequest(label, value) {
            SettingsManager.setString(pref, it.ifBlank { null })
            onChanged()
        })
    }
}

@Composable
private fun IntSetting(label: String, pref: AppPref, onEdit: (InputRequest) -> Unit, onChanged: () -> Unit) {
    val value = SettingsManager.getInt(pref)
    SettingRow(label, value.toString()) {
        onEdit(InputRequest(label, value.toString()) { typed ->
            typed.toIntOrNull()?.let {
                SettingsManager.setInt(pref, it)
                onChanged()
            }
        })
    }
}

@Composable
private fun BoolSetting(label: String, pref: AppPref, onChanged: () -> Unit) {
    val value = SettingsManager.getBoolean(pref)
    Row(
        Modifier.fillMaxWidth().padding(start = Dimens.gapLarge, end = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = value,
            onCheckedChange = {
                SettingsManager.setBoolean(pref, it)
                onChanged()
            },
        )
    }
}

@Composable
private fun ActionSetting(label: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Dimens.gapLarge, end = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClick) { Text(action, style = MaterialTheme.typography.bodySmall) }
    }
}
