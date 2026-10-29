package com.jpage4500.devicemanager.ui.dialog

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.*
import com.jpage4500.devicemanager.data.AppPref
import com.jpage4500.devicemanager.manager.DeviceManager
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.transport.TransportChoice
import com.jpage4500.devicemanager.ui.dialog.common.*
import com.jpage4500.devicemanager.ui.setting.SettingItem
import com.jpage4500.devicemanager.ui.setting.SettingSwitchItem
import com.jpage4500.devicemanager.ui.theme.Colors
import com.jpage4500.devicemanager.ui.theme.ThemeMode
import com.jpage4500.devicemanager.util.SettingsManager
import com.jpage4500.devicemanager.util.obfuscate
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * settings, as a menu rather than a page.
 *
 * The Swing app's `SettingsDialog` is one long scroll of every pref, which is what this was too. It
 * became a drill-down for a reason that only shows up on the two platforms at once: the same list
 * that is a comfortable form in a 560dp desktop window is a page and a half of scrolling on a phone,
 * and a phone has no room for the section headings that made the long version readable.
 *
 * So: five categories here, one dialog each, and the leaf that a row opens — a slider, a stepper, a
 * field, a list of radio buttons — is a dialog of its own on top of that. On a phone each level
 * replaces the one before it and comes back when you close the inner one; on the desktop every level
 * is a window you can drag off to the side, so a form and the field you are editing sit next to each
 * other. See [BaseDialog].
 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    var open by remember { mutableStateOf<SettingsCategory?>(null) }

    BaseDialog(
        titleRes = Res.string.action_settings,
        icon = Res.drawable.settings,
        onDismiss = onDismiss,
    ) {
        SettingsCategory.entries.forEach { category ->
            SettingItem(
                icon = category.icon,
                labelRes = category.label,
                // a chevron rather than a summary of what's inside: every one of these rows opens
                // something, and that is the only thing they have in common worth saying
                valueIcon = Res.drawable.arrow_right,
                valueIconColor = Colors.secondaryText,
                showDivider = category != SettingsCategory.entries.last(),
                onClick = { open = category },
            )
        }
    }

    when (open) {
        SettingsCategory.CONNECTION -> ConnectionSettingsDialog { open = null }
        SettingsCategory.DEVICE_LIST -> DeviceListSettingsDialog { open = null }
        SettingsCategory.LOGS -> LogSettingsDialog { open = null }
        SettingsCategory.APP -> AppSettingsDialog { open = null }
        SettingsCategory.ABOUT -> AboutDialog { open = null }
        null -> Unit
    }
}

/** the five second-level dialogs, in the order the top-level list offers them. */
private enum class SettingsCategory(val label: StringResource, val icon: DrawableResource) {
    CONNECTION(Res.string.settings_connection, Res.drawable.server),
    DEVICE_LIST(Res.string.settings_device_list, Res.drawable.android),
    LOGS(Res.string.settings_logs, Res.drawable.logs),
    APP(Res.string.settings_app, Res.drawable.settings),
    ABOUT(Res.string.settings_about, Res.drawable.about),
}

/**
 * how the app reaches devices, and the addresses it reaches them at.
 *
 * Everything in here restarts the device list — see docs/ADB-TRANSPORTS.md.
 */
@Composable
private fun ConnectionSettingsDialog(onDismiss: () -> Unit) {
    CategoryDialog(Res.string.settings_connection, Res.drawable.server, onDismiss) {
        TransportSetting()
        TextSetting(Res.drawable.adb, Res.string.settings_adb_host, AppPref.ADB_SERVER_HOST, onChanged = ::applyTransport)
        TextSetting(
            icon = Res.drawable.network,
            label = Res.string.settings_adb_port,
            pref = AppPref.ADB_SERVER_PORT,
            inputType = InputType.NUMBER,
            onChanged = ::applyTransport,
        )
        TextSetting(Res.drawable.device_remote, Res.string.settings_remote_url, AppPref.REMOTE_URL, onChanged = ::applyTransport)
        TextSetting(
            icon = Res.drawable.root,
            label = Res.string.settings_remote_token,
            pref = AppPref.REMOTE_TOKEN,
            secret = true,
            showDivider = false,
            onChanged = ::applyTransport,
        )
    }
}

@Composable
private fun DeviceListSettingsDialog(onDismiss: () -> Unit) {
    CategoryDialog(Res.string.settings_device_list, Res.drawable.android, onDismiss) {
        SliderSetting(
            icon = Res.drawable.refresh,
            label = Res.string.settings_refresh_secs,
            pref = AppPref.DETAILS_REFRESH_SECS,
            range = 5..300,
            manualEntry = Res.string.settings_enter_secs,
            unit = Res.string.settings_unit_secs,
        )
        SliderSetting(
            icon = Res.drawable.recent,
            label = Res.string.settings_full_refresh,
            pref = AppPref.FULL_REFRESH_MINS,
            range = 0..240,
            manualEntry = Res.string.settings_enter_mins,
            unit = Res.string.settings_unit_mins,
            zeroText = Res.string.settings_value_never,
        )
        SliderSetting(
            icon = Res.drawable.mirror,
            label = Res.string.settings_mirror_ms,
            pref = AppPref.MIRROR_INTERVAL_MS,
            range = 50..2000,
            manualEntry = Res.string.settings_enter_ms,
            unit = Res.string.settings_unit_ms,
        )
        StepperSetting(
            icon = Res.drawable.chart,
            label = Res.string.settings_max_mirrors,
            pref = AppPref.MAX_MIRROR_WINDOWS,
            minValue = 1,
            maxValue = 16,
        )
        SwitchSetting(Res.drawable.menu, Res.string.settings_compact_rows, AppPref.DEVICES_COMPACT)
        SwitchSetting(Res.drawable.about, Res.string.settings_tooltips, AppPref.SHOW_TOOLTIPS, showDivider = false)
    }
}

@Composable
private fun LogSettingsDialog(onDismiss: () -> Unit) {
    CategoryDialog(Res.string.settings_logs, Res.drawable.logs, onDismiss) {
        TextSetting(Res.drawable.logs, Res.string.settings_log_buffer, AppPref.LOGS_MAX_LINES, InputType.NUMBER)
        TextSetting(Res.drawable.pause, Res.string.settings_log_hold, AppPref.LOGS_HOLD_LINES, InputType.NUMBER)
        SliderSetting(
            icon = Res.drawable.size,
            label = Res.string.settings_log_font,
            pref = AppPref.LOGS_FONT_SIZE,
            range = 9..24,
            manualEntry = Res.string.settings_enter_pt,
            unit = Res.string.settings_unit_pt,
            showDivider = false,
        )
    }
}

@Composable
private fun AppSettingsDialog(onDismiss: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }

    CategoryDialog(Res.string.settings_app, Res.drawable.settings, onDismiss) {
        ThemeSetting()
        SwitchSetting(Res.drawable.tray_icon, Res.string.settings_tray, AppPref.MINIMIZE_TO_TRAY)
        SwitchSetting(Res.drawable.star, Res.string.settings_on_top, AppPref.ALWAYS_ON_TOP)
        SwitchSetting(Res.drawable.error, Res.string.settings_confirm, AppPref.CONFIRM_DESTRUCTIVE)
        SettingItem(
            icon = Res.drawable.trash,
            labelRes = Res.string.settings_reset,
            valueRes = Res.string.settings_reset_button,
            showDivider = false,
            onClick = { confirmReset = true },
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
            },
        )
    }
}

/** the frame every category shares: a title, its icon, and one button that closes it. */
@Composable
private fun CategoryDialog(
    title: StringResource,
    icon: DrawableResource,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BaseDialog(
        titleRes = title,
        icon = icon,
        onDismiss = onDismiss,
        content = content,
    )
}

/**
 * point the settings' own connection at whatever the settings now say.
 *
 * only that one: a Device Manager server connected from the device list is a connection of its own
 * and is left where it is, which is what lets a phone read a desk and change its adb host without
 * losing the desk.
 */
private fun applyTransport() = DeviceManager.applySettings()

// ---------------------------------------------------------------------------------------------
// the row shapes. each owns the dialog it opens, so a category dialog is a list and nothing else,
// and each subscribes to its own pref through observePref rather than a revision counter
// ---------------------------------------------------------------------------------------------

/** which route devices are read over. */
/** light, dark, or whatever the OS is — light by default, see [com.jpage4500.devicemanager.ui.theme.isDarkTheme]. */
@Composable
private fun ThemeSetting() {
    SettingsManager.observePref(AppPref.THEME)
    var picking by remember { mutableStateOf(false) }
    val current = SettingsManager.getEnum<ThemeMode>(AppPref.THEME)
    val labels = mapOf(
        ThemeMode.LIGHT to Res.string.settings_theme_light,
        ThemeMode.DARK to Res.string.settings_theme_dark,
        ThemeMode.SYSTEM to Res.string.settings_theme_system,
    )

    SettingItem(
        icon = Res.drawable.eye_open,
        labelRes = Res.string.settings_theme,
        valueRes = labels.getValue(current),
        onClick = { picking = true },
    )

    if (picking) {
        OptionsDialog(
            title = stringResource(Res.string.settings_theme),
            options = ThemeMode.entries.map { mode -> DialogOption(data = mode, textRes = labels.getValue(mode)) },
            selectedValue = current,
            onDismiss = { picking = false },
            onConfirm = { SettingsManager.setEnum(AppPref.THEME, it) },
        )
    }
}

@Composable
private fun TransportSetting() {
    SettingsManager.observePref(AppPref.TRANSPORT)
    var picking by remember { mutableStateOf(false) }
    val settings by DeviceManager.settingsConnection.collectAsState()

    SettingItem(
        icon = Res.drawable.server,
        labelRes = Res.string.settings_transport,
        valueText = settings?.label,
        onClick = { picking = true },
    )

    if (picking) {
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
            onDismiss = { picking = false },
            onConfirm = {
                SettingsManager.setEnum(AppPref.TRANSPORT, it)
                applyTransport()
            },
        )
    }
}

/** a pref typed into a field — an address, a port, a line count. */
@Composable
private fun TextSetting(
    icon: DrawableResource,
    label: StringResource,
    pref: AppPref,
    inputType: InputType = InputType.TEXT,
    secret: Boolean = false,
    showDivider: Boolean = true,
    onChanged: () -> Unit = {},
) {
    SettingsManager.observePref(pref)
    var editing by remember { mutableStateOf(false) }

    val labelText = stringResource(label)
    val value = SettingsManager.getStringOrEmpty(pref)
    val shown = when {
        value.isEmpty() -> stringResource(Res.string.settings_not_set)
        // a token is long, and its value is not something to leave on screen
        secret -> value.obfuscate()
        else -> value
    }

    SettingItem(
        icon = icon,
        labelText = labelText,
        valueText = shown,
        showDivider = showDivider,
        onClick = { editing = true },
    )

    if (editing) {
        TextInputDialog(
            title = labelText,
            initialValue = value,
            inputType = inputType,
            // an address can legitimately be cleared back to "not set"; a number cannot
            validator = if (inputType == InputType.TEXT) ({ true }) else null,
            onDismiss = { editing = false },
            onConfirm = { typed ->
                when (inputType) {
                    InputType.NUMBER -> typed.toIntOrNull()?.let { SettingsManager.setInt(pref, it) }
                    else -> SettingsManager.setString(pref, typed.trim().ifBlank { null })
                }
                onChanged()
                editing = false
            },
        )
    }
}

/** a duration or a size, set with a slider and typeable when the slider isn't precise enough. */
@Composable
private fun SliderSetting(
    icon: DrawableResource,
    label: StringResource,
    pref: AppPref,
    range: IntRange,
    manualEntry: StringResource,
    unit: StringResource,
    zeroText: StringResource? = null,   // what 0 means, where it means something other than zero units
    showDivider: Boolean = true,
) {
    SettingsManager.observePref(pref)
    var editing by remember { mutableStateOf(false) }

    val labelText = stringResource(label)
    val unitText = stringResource(unit)
    val zero = zeroText?.let { stringResource(it) }
    // the unit is resolved out here and the formatter closes over it: the dialog calls back while
    // dragging its slider, which is not a place a string resource can be read
    val format: (Int) -> String = { if (it == 0 && zero != null) zero else "$it $unitText" }
    val value = SettingsManager.getInt(pref)

    SettingItem(
        icon = icon,
        labelText = labelText,
        valueText = format(value),
        showDivider = showDivider,
        onClick = { editing = true },
    )

    if (editing) {
        SliderDialog(
            title = labelText,
            icon = icon,
            initialValue = value,
            valueRange = range,
            // one tick per unit is unreadable over a range this wide, so these are all smooth
            steps = 0,
            defaultValue = pref.defaultValue as? Int,
            valueDesc = format(value),
            manualEntryLabel = stringResource(manualEntry),
            cancelText = stringResource(Res.string.close),
            onDismiss = { editing = false },
            onValueChange = { format(it) to null },
            onValueChangeFinished = { SettingsManager.setInt(pref, it) },
        )
    }
}

/** a small count, set with +/-. */
@Composable
private fun StepperSetting(
    icon: DrawableResource,
    label: StringResource,
    pref: AppPref,
    minValue: Int,
    maxValue: Int,
    showDivider: Boolean = true,
) {
    SettingsManager.observePref(pref)
    var editing by remember { mutableStateOf(false) }

    val labelText = stringResource(label)
    val value = SettingsManager.getInt(pref)

    SettingItem(
        icon = icon,
        labelText = labelText,
        valueText = value.toString(),
        showDivider = showDivider,
        onClick = { editing = true },
    )

    if (editing) {
        StepperDialog(
            title = labelText,
            icon = icon,
            initialValue = value,
            minValue = minValue,
            maxValue = maxValue,
            defaultValue = pref.defaultValue as? Int,
            onDismiss = { editing = false },
            onValueChange = { SettingsManager.setInt(pref, it) },
        )
    }
}

/** an on/off pref, changed in place — the one row shape that opens nothing. */
@Composable
private fun SwitchSetting(
    icon: DrawableResource,
    label: StringResource,
    pref: AppPref,
    showDivider: Boolean = true,
) {
    val checked by SettingsManager.getBoolAsState(pref)
    SettingSwitchItem(
        icon = icon,
        labelRes = label,
        checked = checked,
        showDivider = showDivider,
        onCheckedChange = { SettingsManager.setBoolean(pref, it) },
    )
}
