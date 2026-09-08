package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import com.jpage4500.devicemanager.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.common.*
import com.jpage4500.devicemanager.ui.setting.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * every setting, in the order the Swing app's `SettingsDialog` puts them.
 *
 * Built from the `ui/setting/` rows ported from DashboardWeatherKMP — an icon, a label, and the
 * current value in red, because in a list this long the value is the only part anyone scans. A row
 * opens the dialog that suits what it holds: a slider for a duration, a stepper for a small count, a
 * field for an address, a list of radio buttons for a choice.
 *
 * The **transport** section is the one thing with no Swing equivalent: that app is always a client
 * of a local adb server, where this one can also point at a server across the LAN or at another
 * instance's HTTP API — see docs/ADB-TRANSPORTS.md. Changing anything in it restarts the device list.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    // every setter writes straight through, so this only exists to redraw what was written
    var revision by remember { mutableStateOf(0) }
    fun changed() { revision++ }

    // one dialog at a time, since a dialog is modal anyway
    var textEdit by remember { mutableStateOf<TextEdit?>(null) }
    var sliderEdit by remember { mutableStateOf<SliderEdit?>(null) }
    var showTransport by remember { mutableStateOf(false) }
    var showMaxMirrors by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val notSet = stringResource(Res.string.settings_not_set)
    val never = stringResource(Res.string.settings_value_never)

    Column(
        modifier
            .fillMaxSize()
            .background(Colors.listBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.gap, vertical = Dimens.gap),
    ) {
        key(revision) {
            SectionHeader(stringResource(Res.string.settings_connection))

            val transport by DeviceManager.transport.collectAsState()
            SettingItem(
                icon = Res.drawable.server,
                labelRes = Res.string.settings_transport,
                valueText = transport.label,
                onClick = { showTransport = true },
            )
            TextSetting(Res.drawable.adb, Res.string.settings_adb_host, AppPref.ADB_SERVER_HOST, notSet) { textEdit = it }
            TextSetting(Res.drawable.network, Res.string.settings_adb_port, AppPref.ADB_SERVER_PORT, notSet, InputType.NUMBER) { textEdit = it }
            TextSetting(Res.drawable.device_remote, Res.string.settings_remote_url, AppPref.REMOTE_URL, notSet) { textEdit = it }
            TextSetting(Res.drawable.root, Res.string.settings_remote_token, AppPref.REMOTE_TOKEN, notSet, secret = true) { textEdit = it }

            Spacer(Modifier.height(Dimens.gapLarge))
            SectionHeader(stringResource(Res.string.settings_device_list))

            SliderSetting(
                icon = Res.drawable.refresh,
                label = stringResource(Res.string.settings_refresh_secs),
                pref = AppPref.DETAILS_REFRESH_SECS,
                range = 5..300,
                manualEntry = stringResource(Res.string.settings_enter_secs),
                unit = stringResource(Res.string.settings_unit_secs),
                onEdit = { sliderEdit = it },
            )
            SliderSetting(
                icon = Res.drawable.recent,
                label = stringResource(Res.string.settings_full_refresh),
                pref = AppPref.FULL_REFRESH_MINS,
                range = 0..240,
                manualEntry = stringResource(Res.string.settings_enter_mins),
                unit = stringResource(Res.string.settings_unit_mins),
                zeroText = never,
                onEdit = { sliderEdit = it },
            )
            SliderSetting(
                icon = Res.drawable.mirror,
                label = stringResource(Res.string.settings_mirror_ms),
                pref = AppPref.MIRROR_INTERVAL_MS,
                range = 50..2000,
                manualEntry = stringResource(Res.string.settings_enter_ms),
                unit = stringResource(Res.string.settings_unit_ms),
                onEdit = { sliderEdit = it },
            )
            SettingItem(
                icon = Res.drawable.chart,
                labelRes = Res.string.settings_max_mirrors,
                valueText = SettingsManager.getInt(AppPref.MAX_MIRROR_WINDOWS).toString(),
                onClick = { showMaxMirrors = true },
            )
            SwitchSetting(Res.drawable.about, Res.string.settings_tooltips, AppPref.SHOW_TOOLTIPS, ::changed)

            Spacer(Modifier.height(Dimens.gapLarge))
            SectionHeader(stringResource(Res.string.settings_logs))

            TextSetting(Res.drawable.logs, Res.string.settings_log_buffer, AppPref.LOGS_MAX_LINES, notSet, InputType.NUMBER) { textEdit = it }
            TextSetting(Res.drawable.pause, Res.string.settings_log_hold, AppPref.LOGS_HOLD_LINES, notSet, InputType.NUMBER) { textEdit = it }
            SliderSetting(
                icon = Res.drawable.size,
                label = stringResource(Res.string.settings_log_font),
                pref = AppPref.LOGS_FONT_SIZE,
                range = 9..24,
                manualEntry = stringResource(Res.string.settings_enter_pt),
                unit = stringResource(Res.string.settings_unit_pt),
                onEdit = { sliderEdit = it },
            )

            Spacer(Modifier.height(Dimens.gapLarge))
            SectionHeader(stringResource(Res.string.settings_app))

            SwitchSetting(Res.drawable.tray_icon, Res.string.settings_tray, AppPref.MINIMIZE_TO_TRAY, ::changed)
            SwitchSetting(Res.drawable.star, Res.string.settings_on_top, AppPref.ALWAYS_ON_TOP, ::changed)
            SwitchSetting(Res.drawable.terminal, Res.string.settings_debug, AppPref.DEBUG_MODE, ::changed)
            SwitchSetting(Res.drawable.error, Res.string.settings_confirm, AppPref.CONFIRM_DESTRUCTIVE, ::changed)
            SettingItem(
                icon = Res.drawable.file_logs,
                labelRes = Res.string.settings_open_folder,
                valueRes = Res.string.settings_open,
                onClick = { StorageManager.getAbsolutePath(PATH_LOGS, FILE_LOG)?.let { PlatformUtils.openUrl(it) } },
            )
            SettingItem(
                icon = Res.drawable.share_on,
                labelRes = Res.string.settings_upload_logs,
                valueRes = Res.string.settings_send,
                onClick = { LogManager.uploadLogs(text = "user requested") },
            )
            SettingItem(
                icon = Res.drawable.trash,
                labelRes = Res.string.settings_reset,
                valueRes = Res.string.settings_reset_button,
                showDivider = false,
                onClick = { confirmReset = true },
            )
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

    // --- the dialogs the rows above open. outside key(revision), so confirming a change doesn't
    // dispose the dialog that reported it ---

    textEdit?.let { edit ->
        TextInputDialog(
            title = edit.label,
            initialValue = SettingsManager.getStringOrEmpty(edit.pref),
            inputType = edit.inputType,
            // an address can legitimately be cleared back to "not set"; a number cannot
            validator = if (edit.inputType == InputType.TEXT) ({ true }) else null,
            onDismiss = { textEdit = null },
            onConfirm = { typed ->
                when (edit.inputType) {
                    InputType.NUMBER -> typed.toIntOrNull()?.let { SettingsManager.setInt(edit.pref, it) }
                    else -> SettingsManager.setString(edit.pref, typed.trim().ifBlank { null })
                }
                if (edit.restartsDeviceList) applyTransport()
                changed()
                textEdit = null
            },
        )
    }

    sliderEdit?.let { edit ->
        SliderDialog(
            title = edit.label,
            icon = edit.icon,
            initialValue = SettingsManager.getInt(edit.pref),
            valueRange = edit.range,
            // one tick per unit is unreadable over a 2-second range, so these are all smooth
            steps = 0,
            defaultValue = edit.pref.defaultValue as? Int,
            valueDesc = edit.valueText,
            manualEntryLabel = edit.manualEntry,
            cancelText = stringResource(Res.string.close),
            onDismiss = { sliderEdit = null },
            onValueChange = { edit.format(it) to null },
            onValueChangeFinished = {
                SettingsManager.setInt(edit.pref, it)
                changed()
            },
        )
    }

    if (showTransport) {
        TransportDialog(
            onDismiss = { showTransport = false },
            onPicked = { applyTransport(); changed() },
        )
    }

    if (showMaxMirrors) {
        StepperDialog(
            title = stringResource(Res.string.settings_max_mirrors),
            icon = Res.drawable.chart,
            initialValue = SettingsManager.getInt(AppPref.MAX_MIRROR_WINDOWS),
            minValue = 1,
            maxValue = 16,
            defaultValue = AppPref.MAX_MIRROR_WINDOWS.defaultValue as? Int,
            onDismiss = { showMaxMirrors = false },
            onValueChange = {
                SettingsManager.setInt(AppPref.MAX_MIRROR_WINDOWS, it)
                changed()
            },
        )
    }

    if (confirmReset) {
        ConfirmationDialog(
            title = stringResource(Res.string.settings_reset_title),
            message = stringResource(Res.string.settings_reset_msg),
            icon = Res.drawable.trash,
            onDismiss = { confirmReset = false },
            onConfirm = {
                SettingsManager.clearAll()
                applyTransport()
                changed()
            },
        )
    }
}

/** which text pref a [TextInputDialog] is currently open on. */
private data class TextEdit(
    val pref: AppPref,
    val label: String,
    val inputType: InputType,
    // the connection settings are the only ones the device list has to be restarted for
    val restartsDeviceList: Boolean,
)

/** which numeric pref a [SliderDialog] is currently open on. */
private data class SliderEdit(
    val pref: AppPref,
    val label: String,
    val icon: DrawableResource,
    val range: IntRange,
    val manualEntry: String,
    val valueText: String,
    val format: (Int) -> String,
)

/** point the device list at whatever the settings now say. */
private fun applyTransport() = DeviceManager.useTransport(DeviceManager.transportFromSettings())

@Composable
private fun TextSetting(
    icon: DrawableResource,
    label: StringResource,
    pref: AppPref,
    notSet: String,
    inputType: InputType = InputType.TEXT,
    secret: Boolean = false,
    onEdit: (TextEdit) -> Unit,
) {
    val labelText = stringResource(label)
    val value = SettingsManager.getStringOrEmpty(pref)
    val shown = when {
        value.isEmpty() -> notSet
        // a token is long, and its value is not something to leave on screen
        secret -> value.obfuscate()
        else -> value
    }
    SettingItem(
        icon = icon,
        labelText = labelText,
        valueText = shown,
        prioritizeValue = false,
        onClick = {
            onEdit(TextEdit(pref, labelText, inputType, restartsDeviceList = pref in CONNECTION_PREFS))
        },
    )
}

private val CONNECTION_PREFS = setOf(
    AppPref.ADB_SERVER_HOST,
    AppPref.ADB_SERVER_PORT,
    AppPref.REMOTE_URL,
    AppPref.REMOTE_TOKEN,
)

@Composable
private fun SliderSetting(
    icon: DrawableResource,
    label: String,
    pref: AppPref,
    range: IntRange,
    manualEntry: String,
    unit: String,
    zeroText: String? = null,   // what 0 means, where it means something other than zero units
    onEdit: (SliderEdit) -> Unit,
) {
    // the unit is resolved out here and the formatter closes over it: the dialog calls back while
    // dragging its slider, which is not a place a string resource can be read
    val format: (Int) -> String = { if (it == 0 && zeroText != null) zeroText else "$it $unit" }
    val shown = format(SettingsManager.getInt(pref))
    SettingItem(
        icon = icon,
        labelText = label,
        valueText = shown,
        onClick = { onEdit(SliderEdit(pref, label, icon, range, manualEntry, shown, format)) },
    )
}

@Composable
private fun SwitchSetting(
    icon: DrawableResource,
    label: StringResource,
    pref: AppPref,
    onChanged: () -> Unit,
) {
    SettingSwitchItem(
        icon = icon,
        labelRes = label,
        checked = SettingsManager.getBoolean(pref),
        onCheckedChange = {
            SettingsManager.setBoolean(pref, it)
            onChanged()
        },
    )
}

/** which route the app reads devices over — the screen's one real list-of-choices. */
@Composable
private fun TransportDialog(onDismiss: () -> Unit, onPicked: () -> Unit) {
    OptionsDialog(
        title = stringResource(Res.string.settings_transport),
        options = listOf(
            DialogOption(
                data = TransportChoice.AUTO,
                icon = Res.drawable.refresh,
                textRes = Res.string.settings_transport_auto,
                valueRes = Res.string.settings_transport_auto_desc,
            ),
            DialogOption(
                data = TransportChoice.ADB_SERVER,
                icon = Res.drawable.adb,
                textRes = Res.string.settings_transport_adb,
                valueRes = Res.string.settings_transport_adb_desc,
            ),
            DialogOption(
                data = TransportChoice.REMOTE,
                icon = Res.drawable.device_remote,
                textRes = Res.string.settings_transport_remote,
                valueRes = Res.string.settings_transport_remote_desc,
            ),
        ),
        selectedValue = SettingsManager.getEnum<TransportChoice>(AppPref.TRANSPORT),
        onDismiss = onDismiss,
        onConfirm = { choice ->
            SettingsManager.setEnum(AppPref.TRANSPORT, choice)
            onPicked()
        },
    )
}
