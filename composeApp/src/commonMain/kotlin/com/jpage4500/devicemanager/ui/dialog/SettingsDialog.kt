package com.jpage4500.devicemanager.ui.dialog

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.common.*
import com.jpage4500.devicemanager.ui.screen.*
import com.jpage4500.devicemanager.ui.setting.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// the tab last shown, for the rest of the session: ⌘, twice in a row comes back where it was
private var lastTab = SettingsTab.GENERAL

/**
 * Settings — tabs across the top of one window on the desktop, a list of categories on a phone.
 *
 * The desktop is the Swing app's `SettingsDialog`: a tabbed window, each tab a form of rows under
 * section headers, every change saved as it's made, and one Close button. The phone keeps the
 * drill-down it had, because the same form is a page and a half of scrolling there with no room for
 * the headers that make it readable. Both are built from the same [SettingsTab] bodies, so a setting
 * added to a tab turns up in both. See docs/SETTINGS.md.
 *
 * [initialTab] opens on that tab — scrcpy's prompt, say — rather than the last one shown.
 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit, initialTab: SettingsTab? = null) {
    val tabs = remember { visibleTabs() }
    val start = initialTab?.takeIf { it in tabs } ?: lastTab.takeIf { it in tabs } ?: tabs.first()
    if (dialogsAreWindows) {
        TabbedSettings(tabs, start, onDismiss)
    } else {
        SettingsMenu(tabs, initialTab?.takeIf { it in tabs }, onDismiss)
    }
}

private fun visibleTabs(): List<SettingsTab> = SettingsTab.entries.filter { tab ->
    when {
        tab == SettingsTab.SCRCPY -> ScrcpyHost.supported
        tab.desktopOnly -> isDesktop
        else -> true
    }
}

private val isDesktop: Boolean get() = localDesktopOS != null

private val SettingsTab.label: StringResource
    get() = when (this) {
        SettingsTab.GENERAL -> Res.string.settings_general
        SettingsTab.DEVICES -> Res.string.settings_devices
        SettingsTab.LOGS -> Res.string.settings_logs
        SettingsTab.FILES -> Res.string.settings_files
        SettingsTab.SCRCPY -> Res.string.settings_scrcpy
        SettingsTab.CONNECTION -> Res.string.settings_connection
        SettingsTab.ABOUT -> Res.string.settings_about
    }

private val SettingsTab.icon: DrawableResource
    get() = when (this) {
        SettingsTab.GENERAL -> Res.drawable.settings
        SettingsTab.DEVICES -> Res.drawable.android
        SettingsTab.LOGS -> Res.drawable.logs
        SettingsTab.FILES -> Res.drawable.folder
        SettingsTab.SCRCPY -> Res.drawable.scrcpy
        SettingsTab.CONNECTION -> Res.drawable.server
        SettingsTab.ABOUT -> Res.drawable.about
    }

// ---------------------------------------------------------------------------------------------
// the two frames
// ---------------------------------------------------------------------------------------------

/** the desktop: one window at a fixed size, the tab strip pinned and each tab's form scrolling. */
@Composable
private fun TabbedSettings(tabs: List<SettingsTab>, start: SettingsTab, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(start) }
    // one per tab, so going back to a tab lands where it was left
    val scrolls = remember { mutableMapOf<SettingsTab, ScrollState>() }

    DialogContainer(
        title = stringResource(Res.string.action_settings),
        onDismiss = onDismiss,
        onEnter = null,
        size = DpSize(Dimens.settingsWindowWidth, Dimens.settingsWindowHeight),
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            SettingsTabStrip(tabs, selected) {
                selected = it
                lastTab = it
            }
            val scroll = scrolls.getOrPut(selected) { ScrollState(0) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = Dimens.gapLarge, vertical = Dimens.gap),
                ) {
                    // keyed, so a row's open leaf dialog doesn't carry over to the next tab's row
                    key(selected) { TabBody(selected) }
                }
                AppVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            HorizontalDivider(color = Colors.divider)
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gapLarge, vertical = Dimens.gap)) {
                Spacer(Modifier.weight(1f))
                DialogButton(stringResource(Res.string.close), onClick = onDismiss)
            }
        }
    }
}

/**
 * the Swing tab strip: icon and label, the selected tab underlined, every tab an equal share. The
 * grounds are the device tabs' — idle tabs on [Colors.tabIdle], the open one on the form's own colour
 * — not the table header's grey, which read as a dark bar across the top of the window.
 */
@Composable
private fun SettingsTabStrip(tabs: List<SettingsTab>, selected: SettingsTab, onSelect: (SettingsTab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Colors.tabIdle)) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .background(if (isSelected) Colors.listBg else Color.Transparent)
                    .plainClickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.padding(horizontal = Dimens.tabGap, vertical = Dimens.settingsTabPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(tab.icon, size = Dimens.icon, tint = if (isSelected) Colors.accent else Colors.secondaryText)
                    Spacer(Modifier.width(Dimens.tabGap))
                    Text(
                        text = stringResource(tab.label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Medium else null,
                        color = if (isSelected) Colors.primaryText else Colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(Dimens.tabAccent)
                        .background(if (isSelected) Colors.tabAccent else Color.Transparent),
                )
            }
        }
    }
    HorizontalDivider(color = Colors.divider)
}

/** the phone: a list of the tabs, each opening its body as a dialog of its own. */
@Composable
private fun SettingsMenu(tabs: List<SettingsTab>, initialTab: SettingsTab?, onDismiss: () -> Unit) {
    var open by remember { mutableStateOf(initialTab) }

    BaseDialog(
        titleRes = Res.string.action_settings,
        icon = Res.drawable.settings,
        onDismiss = onDismiss,
    ) {
        tabs.forEach { tab ->
            SettingItem(
                icon = tab.icon,
                labelRes = tab.label,
                // a chevron rather than a summary of what's inside: every one of these rows opens
                // something, and that is the only thing they have in common worth saying
                valueIcon = Res.drawable.arrow_right,
                valueIconColor = Colors.secondaryText,
                showDivider = tab != tabs.last(),
                onClick = { open = tab },
            )
        }
    }

    open?.let { tab ->
        BaseDialog(titleRes = tab.label, icon = tab.icon, onDismiss = { open = null }) {
            TabBody(tab)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// the tabs
// ---------------------------------------------------------------------------------------------

@Composable
private fun TabBody(tab: SettingsTab) {
    Column(Modifier.fillMaxWidth()) {
        when (tab) {
            SettingsTab.GENERAL -> GeneralTab()
            SettingsTab.DEVICES -> DevicesTab()
            SettingsTab.LOGS -> LogsTab()
            SettingsTab.FILES -> FilesTab()
            SettingsTab.SCRCPY -> ScrcpyOptions(inPrompt = false)
            SettingsTab.CONNECTION -> ConnectionTab()
            SettingsTab.ABOUT -> AboutBody()
        }
    }
}

/** a section header with the room above it that separates it from the rows before. */
@Composable
private fun Section(text: StringResource) {
    Spacer(Modifier.height(Dimens.gapLarge))
    SectionHeader(stringResource(text))
}

@Composable
private fun GeneralTab() {
    ChoiceSetting(
        icon = Res.drawable.eye_open,
        label = Res.string.settings_theme,
        pref = AppPref.THEME,
        options = listOf(
            ThemeMode.LIGHT to stringResource(Res.string.settings_theme_light),
            ThemeMode.DARK to stringResource(Res.string.settings_theme_dark),
            ThemeMode.SYSTEM to stringResource(Res.string.settings_theme_system),
        ),
    )
    // the background check only runs on the desktop — see UpdateManager.startBackgroundChecks
    if (isDesktop) SwitchSetting(Res.drawable.update, Res.string.settings_check_updates, AppPref.CHECK_UPDATES, showDivider = false)

    if (isDesktop) {
        Section(Res.string.settings_section_window)
        SwitchSetting(Res.drawable.tray_icon, Res.string.settings_tray, AppPref.MINIMIZE_TO_TRAY)
        SwitchSetting(Res.drawable.star, Res.string.settings_on_top, AppPref.ALWAYS_ON_TOP, showDivider = false)
    }

    Section(Res.string.settings_section_preferences)
    ResetSetting()
}

@Composable
private fun DevicesTab() {
    SliderSetting(
        icon = Res.drawable.recent,
        label = Res.string.settings_full_refresh,
        pref = AppPref.FULL_REFRESH_MINS,
        // the Swing app's top end; 0 is this one's "never"
        range = 0..600,
        manualEntry = Res.string.settings_enter_mins,
        unit = Res.string.settings_unit_mins,
        zeroText = Res.string.settings_value_never,
    )
    SliderSetting(
        icon = Res.drawable.refresh,
        label = Res.string.settings_refresh_secs,
        pref = AppPref.DETAILS_REFRESH_SECS,
        range = 5..300,
        manualEntry = Res.string.settings_enter_secs,
        unit = Res.string.settings_unit_secs,
        showDivider = false,
    )

    Section(Res.string.settings_section_table)
    // the column table is the desktop's; a phone's device list has no columns
    if (isDesktop) ColumnsSetting()
    SwitchSetting(Res.drawable.menu, Res.string.settings_compact_rows, AppPref.DEVICES_COMPACT, showDivider = false)

    Section(Res.string.settings_section_mirror)
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
        showDivider = false,
    )
}

@Composable
private fun LogsTab() {
    TextSetting(
        icon = Res.drawable.logs,
        label = Res.string.settings_log_buffer,
        pref = AppPref.LOGS_MAX_LINES,
        inputType = InputType.NUMBER,
        range = LogsSource.MIN_BUFFER..LogsSource.MAX_BUFFER,
        desc = stringResource(Res.string.settings_buffer_range, LogsSource.MIN_BUFFER, LogsSource.MAX_BUFFER),
    )
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

@Composable
private fun FilesTab() {
    FolderSetting(
        icon = Res.drawable.download,
        label = Res.string.settings_download_folder,
        pref = AppPref.DOWNLOAD_FOLDER,
        defaultText = defaultDownloadFolder.orEmpty(),
    )
    FolderSetting(
        icon = Res.drawable.screenshot,
        label = Res.string.settings_screenshot_folder,
        pref = AppPref.SCREENSHOT_FOLDER,
        defaultText = stringResource(Res.string.settings_same_as_downloads),
    )
    SwitchSetting(Res.drawable.eye_closed, Res.string.settings_files_hidden, AppPref.FILES_SHOW_HIDDEN, showDivider = false)
}

/**
 * scrcpy's tab, and the body of the prompt before a launch — [inPrompt] — which is the Swing
 * app's `ScrcpyOptionsDialog` serving both. The prompt leaves out what the Mirror button opens:
 * someone looking at it has already chosen.
 */
@Composable
fun ScrcpyOptions(inPrompt: Boolean) {
    if (!inPrompt) {
        ChoiceSetting(
            icon = Res.drawable.mirror,
            label = Res.string.settings_mirror_with,
            pref = AppPref.MIRROR_WITH,
            options = listOf(
                MirrorWith.BUILT_IN to stringResource(Res.string.settings_mirror_builtin),
                MirrorWith.SCRCPY to stringResource(Res.string.settings_scrcpy),
            ),
        )
    }
    ScrcpyPathSetting()

    Section(Res.string.settings_section_options)
    SwitchSetting(Res.drawable.mirror, Res.string.settings_scrcpy_touches, AppPref.SCRCPY_SHOW_TOUCHES)
    SwitchSetting(Res.drawable.power, Res.string.settings_scrcpy_awake, AppPref.SCRCPY_STAY_AWAKE)
    SwitchSetting(Res.drawable.share_off, Res.string.settings_scrcpy_no_audio, AppPref.SCRCPY_NO_AUDIO)
    ChoiceSetting(
        icon = Res.drawable.screen_record,
        label = Res.string.settings_scrcpy_codec,
        pref = AppPref.SCRCPY_VIDEO_CODEC,
        // codec names, not words — the same in every language
        options = listOf("h264", "h265", "av1").map { it to it },
        stringValue = true,
    )
    TextSetting(
        icon = Res.drawable.terminal,
        label = Res.string.settings_scrcpy_flags,
        pref = AppPref.SCRCPY_EXTRA_ARGS,
        placeholder = stringResource(Res.string.settings_scrcpy_flags_hint),
    )
    SwitchSetting(Res.drawable.about, Res.string.settings_scrcpy_ask, AppPref.SCRCPY_ASK, showDivider = false)

    if (!inPrompt) {
        Spacer(Modifier.height(Dimens.gap))
        Text(
            text = stringResource(Res.string.settings_scrcpy_remote_note),
            style = MaterialTheme.typography.labelSmall,
            color = Colors.secondaryText,
            modifier = Modifier.padding(horizontal = Dimens.settingPadding),
        )
    }
}

/**
 * the options with Mirror and Cancel, before [deviceIds] go to scrcpy — Settings › scrcpy's Ask
 * before mirroring, and always when there's no scrcpy found to run.
 */
@Composable
fun ScrcpyPromptDialog(deviceIds: List<String>, onDismiss: () -> Unit) {
    if (deviceIds.isEmpty()) return
    SettingsManager.observePref(AppPref.SCRCPY_PATH)
    val configured = SettingsManager.getString(AppPref.SCRCPY_PATH)
    val found by produceState<String?>(null, configured) {
        value = withContext(ioDispatcher) { ScrcpyHost.resolve(configured) }
    }

    BaseDialog(
        titleRes = Res.string.scrcpy_prompt_title,
        icon = Res.drawable.scrcpy,
        okText = stringResource(Res.string.action_mirror),
        cancelText = stringResource(Res.string.cancel),
        okEnabled = found != null,
        onConfirm = { MirrorLauncher.launch(deviceIds) },
        onDismiss = onDismiss,
    ) {
        ScrcpyOptions(inPrompt = true)
    }
}

@Composable
private fun ConnectionTab() {
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

    // the saved servers live in Connect, which is a desktop window; a phone lists them with its devices
    if (isDesktop) {
        Section(Res.string.settings_section_remote)
        SettingItem(
            icon = Res.drawable.server,
            labelRes = Res.string.settings_other_computers,
            valueRes = Res.string.settings_manage,
            showDivider = false,
            onClick = { WindowManager.openConnect() },
        )
    }
}

/**
 * point the settings' own connection at whatever the settings now say.
 *
 * only that one: a Device Manager server connected from the device list is a connection of its own
 * and is left where it is, which is what lets a phone read a desk and change its adb host without
 * losing the desk. See docs/ADB-TRANSPORTS.md.
 */
private fun applyTransport() = DeviceManager.applySettings()

// ---------------------------------------------------------------------------------------------
// the row shapes. each owns the dialog it opens, so a tab is a list and nothing else, and each
// subscribes to its own pref through observePref rather than a revision counter
// ---------------------------------------------------------------------------------------------

/**
 * one of a few values. A dropdown in place on the desktop — the Swing dialog's combo box — and a
 * list of radio buttons on a phone, where a dropdown is a small target.
 *
 * [stringValue] stores the option itself as the pref's string; otherwise [T] is an enum and its
 * name is stored.
 */
@Composable
private fun <T> ChoiceSetting(
    icon: DrawableResource,
    label: StringResource,
    pref: AppPref,
    options: List<Pair<T, String>>,
    stringValue: Boolean = false,
    showDivider: Boolean = true,
) {
    SettingsManager.observePref(pref)
    var picking by remember { mutableStateOf(false) }
    val stored = SettingsManager.getString(pref)
    val current = options.firstOrNull { (value, _) -> stored == if (stringValue) value else (value as Enum<*>).name }
        ?: options.first()
    val select: (T) -> Unit = { value ->
        SettingsManager.setString(pref, if (stringValue) value as String else (value as Enum<*>).name)
    }
    val labelText = stringResource(label)

    if (dialogsAreWindows) {
        SettingItem(
            icon = icon,
            labelText = labelText,
            showDivider = showDivider,
            valueContent = {
                AppDropdown(
                    value = current,
                    options = options,
                    label = { it.second },
                    onSelect = { select(it.first) },
                    modifier = Modifier.width(Dimens.settingsDropdownWidth),
                )
            },
            onClick = {},
        )
    } else {
        SettingItem(
            icon = icon,
            labelText = labelText,
            valueText = current.second,
            showDivider = showDivider,
            onClick = { picking = true },
        )
        if (picking) {
            OptionsDialog(
                title = labelText,
                options = options.map { (value, text) -> DialogOption(data = value, label = text) },
                selectedValue = current.first,
                onDismiss = { picking = false },
                onConfirm = select,
            )
        }
    }
}

/** which route devices are read over. */
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

/**
 * a pref typed into a field — an address, a port, a line count.
 *
 * [range] clamps a number into what the setting accepts, as the Swing app's prompts did, and
 * [desc] says what that range is.
 */
@Composable
private fun TextSetting(
    icon: DrawableResource,
    label: StringResource,
    pref: AppPref,
    inputType: InputType = InputType.TEXT,
    secret: Boolean = false,
    range: IntRange? = null,
    desc: String? = null,
    placeholder: String = "",
    showDivider: Boolean = true,
    onChanged: () -> Unit = {},
) {
    SettingsManager.observePref(pref)
    var editing by remember { mutableStateOf(false) }

    val labelText = stringResource(label)
    // a number always has a value — unset, it is the pref's default, which is what to show
    val value = if (inputType == InputType.NUMBER) SettingsManager.getInt(pref).toString() else SettingsManager.getStringOrEmpty(pref)
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
            desc = desc,
            initialValue = value,
            placeholder = placeholder,
            inputType = inputType,
            // an address can legitimately be cleared back to "not set"; a number cannot
            validator = if (inputType == InputType.TEXT) ({ true }) else null,
            onDismiss = { editing = false },
            onConfirm = { typed ->
                when (inputType) {
                    InputType.NUMBER -> typed.toIntOrNull()?.let { number ->
                        SettingsManager.setInt(pref, range?.let { number.coerceIn(it) } ?: number)
                    }
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

/**
 * a value with an × after it that puts the setting back to its default — for a folder or a path,
 * where the default isn't something anyone could type back in.
 */
@Composable
private fun ClearableValue(text: String, onClear: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = Dimens.settingValueSize,
            color = Colors.settingValue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onClear != null) {
            Spacer(Modifier.width(Dimens.gap))
            AppIcon(
                res = Res.drawable.close,
                size = Dimens.icon,
                tint = Colors.secondaryText,
                modifier = Modifier.plainClickable(onClick = onClear),
            )
        }
    }
}

/** a folder on this machine, picked with the native chooser; unset is [defaultText]. */
@Composable
private fun FolderSetting(
    icon: DrawableResource,
    label: StringResource,
    pref: AppPref,
    defaultText: String,
    showDivider: Boolean = true,
) {
    SettingsManager.observePref(pref)
    val scope = rememberCoroutineScope()
    val labelText = stringResource(label)
    val value = SettingsManager.getString(pref)?.takeIf { it.isNotBlank() }

    SettingItem(
        icon = icon,
        labelText = labelText,
        showDivider = showDivider,
        valueContent = {
            ClearableValue(value ?: defaultText, onClear = value?.let { { SettingsManager.setString(pref, null) } })
        },
        onClick = {
            scope.launch {
                pickLocalFolder(labelText, value ?: defaultDownloadFolder)?.let { SettingsManager.setString(pref, it) }
            }
        },
    )
}

/**
 * where scrcpy is: the one picked, or the one found on `PATH` — and under it, the version that one
 * reports, which is also how anyone can tell the path works.
 */
@Composable
private fun ScrcpyPathSetting() {
    SettingsManager.observePref(AppPref.SCRCPY_PATH)
    val scope = rememberCoroutineScope()
    val labelText = stringResource(Res.string.settings_scrcpy_path)
    val configured = SettingsManager.getString(AppPref.SCRCPY_PATH)?.takeIf { it.isNotBlank() }

    // both off the UI thread: resolving stats a handful of folders, and the version runs scrcpy
    var checked by remember(configured) { mutableStateOf(false) }
    var path by remember(configured) { mutableStateOf<String?>(null) }
    var version by remember(configured) { mutableStateOf<String?>(null) }
    LaunchedEffect(configured) {
        withContext(ioDispatcher) {
            path = ScrcpyHost.resolve(configured)
            version = path?.let { ScrcpyHost.version(it) }
        }
        checked = true
    }

    val notFound = stringResource(Res.string.settings_scrcpy_not_found)
    val checking = stringResource(Res.string.settings_scrcpy_checking)
    SettingItem(
        icon = Res.drawable.open_folder,
        labelText = labelText,
        valueContent = {
            ClearableValue(
                text = path ?: if (checked) notFound else checking,
                onClear = configured?.let { { SettingsManager.setString(AppPref.SCRCPY_PATH, null) } },
            )
        },
        onClick = {
            scope.launch {
                pickLocalFiles(labelText).firstOrNull()?.let { SettingsManager.setString(AppPref.SCRCPY_PATH, it.path) }
            }
        },
    )
    SettingItem(
        icon = Res.drawable.scrcpy,
        labelRes = Res.string.settings_scrcpy_version,
        valueText = when {
            !checked -> checking
            path == null -> notFound
            else -> version ?: notFound
        },
        showDivider = false,
        onClick = {},
    )
}

/**
 * how many device columns are shown, and the checklist that picks them; a custom column to add;
 * then a layout reset.
 */
@Composable
private fun ColumnsSetting() {
    val hidden by DeviceTableState.hidden.collectAsState()
    val columns by DeviceTableState.all.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val total = columns.size
    val shown = columns.count { it.id !in hidden }

    SettingItem(
        icon = Res.drawable.menu,
        labelRes = Res.string.settings_columns,
        valueText = stringResource(Res.string.settings_columns_shown, shown, total),
        onClick = { editing = true },
    )
    SettingItem(
        icon = Res.drawable.add,
        labelRes = Res.string.settings_columns_add,
        valueRes = Res.string.settings_columns_add_value,
        onClick = { adding = true },
    )
    SettingItem(
        icon = Res.drawable.restart,
        labelRes = Res.string.settings_columns_reset,
        valueRes = Res.string.settings_reset_button,
        onClick = { confirmReset = true },
    )

    if (editing) {
        MultiSelectOptionsDialog(
            titleRes = Res.string.settings_columns,
            options = columns.map { MultiSelectDialogOption(data = it.id, text = it.label) },
            selectedValues = columns.map { it.id }.toSet() - hidden,
            onDismiss = { editing = false },
            onConfirm = { picked -> DeviceTableState.setHidden(columns.map { it.id }.toSet() - picked) },
        )
    }
    if (adding) {
        CustomColumnDialog(
            original = null,
            onDismiss = { adding = false },
            onSave = { DeviceTableState.addCustom(it) },
        )
    }
    if (confirmReset) {
        ConfirmationDialog(
            title = stringResource(Res.string.settings_columns_reset),
            message = stringResource(Res.string.settings_columns_reset_msg),
            icon = Res.drawable.restart,
            onDismiss = { confirmReset = false },
            onConfirm = { DeviceTableState.resetColumns() },
        )
    }
}

/** every setting back to its default, after asking. */
@Composable
private fun ResetSetting() {
    var confirmReset by remember { mutableStateOf(false) }

    SettingItem(
        icon = Res.drawable.trash,
        labelRes = Res.string.settings_reset,
        valueRes = Res.string.settings_reset_button,
        showDivider = false,
        onClick = { confirmReset = true },
    )

    if (confirmReset) {
        ConfirmationDialog(
            title = stringResource(Res.string.settings_reset_title),
            message = stringResource(Res.string.settings_reset_msg),
            icon = Res.drawable.trash,
            onDismiss = { confirmReset = false },
            onConfirm = {
                SettingsManager.clearAll()
                // the table holds its layout in memory as well as in the prefs just cleared
                if (isDesktop) DeviceTableState.reload()
                applyTransport()
            },
        )
    }
}
