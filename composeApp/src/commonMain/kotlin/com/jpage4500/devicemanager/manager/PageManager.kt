package com.jpage4500.devicemanager.manager

import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** a page of the desktop shell — what the sidebar switches between. See docs/REDESIGN.md. */
enum class Page { DEVICES, FILES, LOGS, INPUT, COMMANDS, SCRIPTS }

/** one device's log in the Logs page, kept whether or not its tab is the one showing. */
class LogsTab(val deviceId: String, val source: LogsSource) {
    val view = LogsView()
}

/** the lowest level a log tab shows — the V D I W E buttons, in logcat's order of seriousness. */
enum class LogLevelFloor(val code: Char, val expression: String?) {
    VERBOSE('V', null),
    DEBUG('D', "level:D+"),
    INFO('I', "level:I+"),
    WARN('W', "level:W+"),
    // error and worse: E and A/F both, which is what the Swing app's "Log Level Error+" meant
    ERROR('E', "level:E+");

    companion object {
        fun of(level: String?): LogLevelFloor? =
            entries.firstOrNull { it.code == level?.firstOrNull()?.uppercaseChar() }
                ?: if (level?.firstOrNull()?.uppercaseChar() in setOf('A', 'F')) ERROR else null
    }
}

/**
 * how one log tab is being read: what it's filtered to, what's being searched for, and where it is.
 *
 * kept by the tab rather than by `LogsScreen`, which is created again each time its tab is opened —
 * so switching to another device's log and back leaves this one as it was, down to the scroll.
 * Saved filters are shared by every tab ([LogFilterStore]); which of them are *on* is this tab's.
 * A new tab starts with the filters and level last set in any tab, which are kept across launches.
 */
@Stable
class LogsView {
    /**
     * highlights matches rather than filtering to them — ⌘G walks them. Only the level and the
     * filter pills take lines away.
     */
    var search by mutableStateOf("")
    /** the saved filters switched on here, by name — remembered for the next tab opened. */
    private var _selectedFilters by mutableStateOf(
        JsonUtils.stringToList<String>(SettingsManager.getString(AppPref.LOGS_SELECTED_FILTERS)).toSet()
    )
    var selectedFilters: Set<String>
        get() = _selectedFilters
        set(value) {
            _selectedFilters = value
            SettingsManager.setString(AppPref.LOGS_SELECTED_FILTERS, JsonUtils.toJson(value.toList()))
        }
    /**
     * filters made by a right-click — Show only, Hide — as expressions. This tab's alone and not
     * saved; clicking one's pill saves it as a filter.
     */
    var quick by mutableStateOf(emptyList<String>())
    /** the level buttons' choice — remembered for the next tab opened, like [selectedFilters]. */
    private var _floor by mutableStateOf(SettingsManager.getEnum<LogLevelFloor>(AppPref.LOGS_LEVEL_FLOOR))
    var floor: LogLevelFloor
        get() = _floor
        set(value) {
            _floor = value
            SettingsManager.setEnum(AppPref.LOGS_LEVEL_FLOOR, value)
        }
    var autoScroll by mutableStateOf(true)
    val listState = LazyListState()
    /** ⌘S asked this tab to save its log; the tab opens its save dialog and puts this back. */
    var saveRequested by mutableStateOf(false)
}

/**
 * the devices last given a tab on one page, newest first — the chips beside the tab strip's +.
 *
 * one list per page and shared by every window, kept across launches: the devices someone reads logs
 * on aren't always the ones they browse.
 */
class RecentDevices(private val pref: AppPref) {
    private val _ids = MutableStateFlow(JsonUtils.stringToList<String>(SettingsManager.getString(pref)))
    val ids: StateFlow<List<String>> = _ids.asStateFlow()

    fun add(deviceId: String) {
        val list = (listOf(deviceId) + _ids.value.filterNot { it == deviceId }).take(MAX)
        if (list == _ids.value) return
        _ids.value = list
        SettingsManager.setString(pref, JsonUtils.toJson(list))
    }

    companion object {
        // more than the strip ever has room to show; the rest is so a chip is still there to fill
        // the place of a recent device that's unplugged
        private const val MAX = 8
        val logs = RecentDevices(AppPref.LOGS_RECENT_DEVICES)
        val files = RecentDevices(AppPref.FILES_RECENT_DEVICES)
    }
}

/** one device's filesystem in the Files page; [browser] holds the folder it was left in. */
class FilesTab(val deviceId: String, val browser: FileBrowser)

/**
 * what one desktop window is showing: its page, and the devices open in Logs and Files.
 *
 * Owned by the window rather than the app — two windows can be on different pages — and it **owns
 * the log streams** of its tabs. They run on [scope], the window's, not on the tab's composable: a
 * stream started by a tab's composable dies when another tab is opened, and a log that stops
 * whenever you look away is only half a log. Closing a tab or the window is what stops one.
 */
@Stable
class ShellState(private val scope: CoroutineScope) {
    private val log = LoggerConfig.createLogger("ShellState")

    var page by mutableStateOf(Page.DEVICES)

    /** the Devices page's details panel is open; it shows whichever device leads the selection. */
    var detailsOpen by mutableStateOf(false)

    /** the Settings dialog is open over this window. */
    var settingsOpen by mutableStateOf(false)

    /** Share Devices is open over this window. */
    var shareOpen by mutableStateOf(false)

    /** the tab Settings opens on, when something asked for one; null for the last one shown. */
    var settingsTab by mutableStateOf<SettingsTab?>(null)

    /** the scrcpy options are open over this window before mirroring these devices; empty when not. */
    var scrcpyFor by mutableStateOf(emptyList<String>())

    val logTabs = mutableStateListOf<LogsTab>()
    var activeLogs by mutableStateOf<String?>(null)
        private set

    val fileTabs = mutableStateListOf<FilesTab>()
    var activeFiles by mutableStateOf<String?>(null)
        private set

    /** open (or focus) a log tab for each device, in order, and show the first. */
    fun openLogs(deviceIds: List<String>) {
        if (deviceIds.isEmpty()) return
        // the one shown is the newest
        deviceIds.reversed().forEach(RecentDevices.logs::add)
        deviceIds.forEach { id ->
            if (logTabs.none { it.deviceId == id }) {
                log.d("openLogs: $id")
                val tab = LogsTab(id, LogsSource(id))
                logTabs.add(tab)
                tab.source.start(scope)
            }
        }
        activeLogs = deviceIds.first()
        page = Page.LOGS
    }

    fun showLogs(deviceId: String) {
        if (logTabs.any { it.deviceId == deviceId }) activeLogs = deviceId
    }

    fun closeLogs(deviceId: String) {
        val index = logTabs.indexOfFirst { it.deviceId == deviceId }
        if (index < 0) return
        log.d("closeLogs: $deviceId")
        logTabs.removeAt(index).source.stop()
        if (activeLogs == deviceId) activeLogs = neighbour(logTabs.map { it.deviceId }, index)
    }

    /** open (or focus) a files tab for the device, and show it. */
    fun openFiles(deviceId: String) {
        RecentDevices.files.add(deviceId)
        if (fileTabs.none { it.deviceId == deviceId }) {
            log.d("openFiles: $deviceId")
            fileTabs.add(FilesTab(deviceId, FileBrowser(deviceId)))
        }
        activeFiles = deviceId
        page = Page.FILES
    }

    fun showFiles(deviceId: String) {
        if (fileTabs.any { it.deviceId == deviceId }) activeFiles = deviceId
    }

    fun closeFiles(deviceId: String) {
        val index = fileTabs.indexOfFirst { it.deviceId == deviceId }
        if (index < 0) return
        fileTabs.removeAt(index)
        if (activeFiles == deviceId) activeFiles = neighbour(fileTabs.map { it.deviceId }, index)
    }

    /**
     * ⌘W: close the tab showing on Logs or Files. False when there's none — another page, or no
     * tabs left — and the window itself is what ⌘W closes.
     */
    fun closeActiveTab(): Boolean {
        when (page) {
            Page.LOGS -> activeLogs?.let { closeLogs(it); return true }
            Page.FILES -> activeFiles?.let { closeFiles(it); return true }
            else -> {}
        }
        return false
    }

    /** ⌘S on Logs: the open tab's save dialog. False when no tab is showing. */
    fun saveActiveLog(): Boolean {
        if (page != Page.LOGS) return false
        val tab = logTabs.firstOrNull { it.deviceId == activeLogs } ?: return false
        tab.view.saveRequested = true
        return true
    }

    /** the window is going: every stream it started goes with it. */
    fun dispose() {
        logTabs.forEach { it.source.stop() }
        logTabs.clear()
        fileTabs.clear()
    }

    // closing a tab opens the one that slid into its place, or the one before it at the end — what a
    // browser does, so the next tab is under the pointer that just pressed ×
    private fun neighbour(ids: List<String>, removedAt: Int): String? =
        ids.getOrNull(removedAt) ?: ids.lastOrNull()
}

/**
 * routes "show this device's logs" to the window that should show them.
 *
 * the callers — a toolbar button, a row's menu, the tray — don't know which window they belong to,
 * and the tray belongs to none. So the window last in front gets it, and with no window open, one is
 * opened and handed the request once it exists.
 */
object PageManager {
    // most recently in front last. state, so the menu bar's page-dependent shortcuts follow a
    // window coming to the front as well as a page change inside one
    private val shells = mutableStateListOf<ShellState>()
    private val pending = mutableListOf<(ShellState) -> Unit>()

    fun register(shell: ShellState) {
        activate(shell)
        val queued = pending.toList()
        pending.clear()
        queued.forEach { it(shell) }
    }

    fun unregister(shell: ShellState) {
        shells.remove(shell)
    }

    /** this window came to the front. */
    fun activate(shell: ShellState) {
        shells.remove(shell)
        shells.add(shell)
    }

    /** the page showing in the window last in front; null with no window open. */
    val activePage: Page? get() = shells.lastOrNull()?.page

    /** ⌘S on Logs in the window last in front — see [ShellState.saveActiveLog]. */
    fun saveActiveLog(): Boolean = shells.lastOrNull()?.saveActiveLog() ?: false

    /** the Devices page — View › as List / as Icons. */
    fun openDevices() = show(Page.DEVICES)

    /** [page] as it was left, tabs and all — ⌘1 / ⌘2 / ⌘3 from the menu bar. */
    fun show(page: Page) = withShell { it.page = page }

    /** the Devices page with [deviceId] selected and its details panel open — a row's menu, the tray. */
    fun openDetails(deviceId: String) = withShell {
        DeviceManager.select(deviceId)
        it.page = Page.DEVICES
        it.detailsOpen = true
    }

    fun openLogs(deviceIds: List<String>) = withShell { it.openLogs(deviceIds) }

    /** ⌘W in the window last in front: its open tab, if it's showing one — see [ShellState.closeActiveTab]. */
    fun closeActiveTab(): Boolean = shells.lastOrNull()?.closeActiveTab() ?: false

    fun openFiles(deviceId: String) = withShell { it.openFiles(deviceId) }

    /** the Scripts page, running on [deviceIds]. */
    fun openScripts(deviceIds: List<String>) = withShell {
        if (deviceIds.isNotEmpty()) ScriptManager.choose(deviceIds)
        it.page = Page.SCRIPTS
    }

    /** the Settings dialog, over the window last in front — ⌘, from the menu bar. */
    fun openSettings(tab: SettingsTab? = null) = withShell {
        it.settingsTab = tab
        it.settingsOpen = true
    }

    /** the scrcpy options with Mirror and Cancel, before [deviceIds] go to scrcpy — see [MirrorLauncher]. */
    fun openScrcpyPrompt(deviceIds: List<String>) {
        if (deviceIds.isNotEmpty()) withShell { it.scrcpyFor = deviceIds }
    }

    /** the Input page, typing on [deviceIds] — the selection bar's Type text and ⌘I. */
    fun openInput(deviceIds: List<String>) = withShell {
        if (deviceIds.isNotEmpty()) InputManager.choose(deviceIds)
        it.page = Page.INPUT
    }

    /** the Commands page, running on [deviceIds] — the selection bar and a row's menu. */
    fun openCommands(deviceIds: List<String>) = withShell {
        if (deviceIds.isNotEmpty()) CommandManager.choose(deviceIds)
        it.page = Page.COMMANDS
    }

    private fun withShell(action: (ShellState) -> Unit) {
        val shell = shells.lastOrNull()
        if (shell != null) {
            action(shell)
        } else {
            pending.add(action)
            WindowManager.openWindow(WindowContent.DeviceList)
        }
    }
}
