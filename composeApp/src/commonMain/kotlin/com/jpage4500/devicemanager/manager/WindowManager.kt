package com.jpage4500.devicemanager.manager

import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.flow.*

/** where a window was, so reopening it doesn't throw away a position someone chose. */
data class WindowBounds(
    val x: Int = -1,
    val y: Int = -1,
    val width: Int = 1000,
    val height: Int = 640,
)

/** how big a window of one kind was left, which is what the next one of that kind opens at. */
data class WindowSize(val width: Int, val height: Int)

/**
 * what a window is *for*, with the device it is for left out.
 *
 * this is the key a window's size is remembered under, and leaving the serial out is the point: a
 * person sizes a log window once, not once per device, and a pref holding a rect per serial would
 * grow for as long as the app is used. It is also why a log window doesn't open at the device list's
 * size — the two are different kinds and the size travelled with the *parent* before this existed.
 *
 * the sizes are the first-open defaults only; everything after that is what the user left behind.
 */
enum class WindowKind(val defaultWidth: Dp, val defaultHeight: Dp) {
    DEVICE_LIST(Dimens.windowWidth, Dimens.windowHeight),
    MIRROR(Dimens.mirrorWindowWidth, Dimens.mirrorWindowHeight),
    INFO(Dimens.formWindowWidth, Dimens.formWindowHeight),
    CONNECT(Dimens.formWindowWidth, Dimens.formWindowHeight),
    MESSAGE(Dimens.messageWindowWidth, Dimens.messageWindowHeight),
}

/**
 * what a window opens on.
 *
 * A device is named by its [com.jpage4500.devicemanager.data.Device.id] rather than by its serial,
 * so two desks each holding an `emulator-5554` open two windows and not one — which is also what
 * [WindowManager.openOnce] compares.
 */
sealed interface WindowContent {
    /** the device list, and whatever is selected in it. */
    data object DeviceList : WindowContent

    /** one device on its own — a mirror torn off into a window of its own. */
    data class Mirror(val deviceId: String) : WindowContent

    /** everything known about one device. */
    data class Info(val deviceId: String) : WindowContent

    /** reach a device over the network. */
    data object Connect : WindowContent

    /** one log line, in full. */
    data class Message(val text: String) : WindowContent
}

/** which remembered size a window uses. */
val WindowContent.kind: WindowKind
    get() = when (this) {
        is WindowContent.DeviceList -> WindowKind.DEVICE_LIST
        is WindowContent.Mirror -> WindowKind.MIRROR
        is WindowContent.Info -> WindowKind.INFO
        is WindowContent.Connect -> WindowKind.CONNECT
        is WindowContent.Message -> WindowKind.MESSAGE
    }

/** one open window, as the entry point's `application` block sees it. */
class AppWindow(
    val id: Long,
    val content: WindowContent,
    /** the size and position it opens at — see [WindowManager.placementFor]. */
    val bounds: WindowBounds,
)

/**
 * every open window.
 *
 * The entry point draws one real OS window per entry here, so opening one is a state change and
 * nothing more — a menu item, a double-clicked device row or a keyboard shortcut can all call
 * [openWindow] without any of them knowing what a window is made of. Ported from FileExplorerKMP,
 * where the same design carries tab tear-off.
 *
 * Lives in `commonMain` even though only the desktop has windows: the phone has one screen and never
 * calls any of this, and keeping it here means a mirror can be opened from shared code.
 */
object WindowManager {
    private val log = LoggerConfig.createLogger("WindowManager")

    // a new window opens down and across from the one it came from, the way every desktop app does —
    // stacked exactly on top, it looks like nothing happened
    private const val CASCADE_OFFSET = 32

    private val _windows = MutableStateFlow<List<AppWindow>>(emptyList())
    val windows: StateFlow<List<AppWindow>> = _windows.asStateFlow()

    // where each open window is right now, for cascading a new one off it. not persisted: a
    // position is only meaningful on the screens that were attached at the time
    private val bounds = mutableMapOf<Long, WindowBounds>()

    // how big a window of each kind was last left, which is what the next one opens at. persisted
    private val sizes = loadSizes().toMutableMap()

    // a resize fires on every frame of a drag; the file it lands in doesn't need to
    private val sizeWrite = Debouncer()

    private var nextWindowId = 1L

    /**
     * the window a menu item acts on: the last one to have been in front.
     *
     * "last in front" rather than "in front now" because focus can be on a dialog or a mirror, and a
     * menu shortcut still has to do something then.
     */
    var activeWindowId: Long? = null
        private set

    fun openWindow(content: WindowContent, at: WindowBounds? = null): Long {
        val id = nextWindowId++
        val place = at ?: placementFor(content, activeWindowId)
        log.d("openWindow: $id ${content.kind}, ${place.width}x${place.height}, ${_windows.value.size} already open")
        bounds[id] = place
        _windows.value = _windows.value + AppWindow(id, content, place)
        // whatever opened it is what the user is looking at, and the OS is about to give it focus
        activeWindowId = id
        return id
    }

    /** a mirror in a window of its own, beside the one it was opened from. */
    fun openMirror(deviceId: String, from: Long? = activeWindowId): Long =
        openOnce(WindowContent.Mirror(deviceId), from)

    /**
     * a device's logcat, as a tab in the Logs page of the window last in front — not a window of
     * its own any more. See [PageManager] and docs/REDESIGN.md.
     */
    fun openLogs(deviceId: String) = PageManager.openLogs(listOf(deviceId))

    /** a device's filesystem, as a tab in the Files page — see [openLogs]. */
    fun openFiles(deviceId: String) = PageManager.openFiles(deviceId)

    fun openInfo(deviceId: String, from: Long? = activeWindowId): Long =
        openOnce(WindowContent.Info(deviceId), from)

    fun openConnect(from: Long? = activeWindowId): Long = openOnce(WindowContent.Connect, from)


    fun openMessage(text: String, from: Long? = activeWindowId): Long =
        openOnce(WindowContent.Message(text), from)

    /**
     * open [content], or raise the window already showing it.
     *
     * a second mirror would halve both their frame rates.
     */
    private fun openOnce(content: WindowContent, from: Long?): Long {
        val existing = _windows.value.firstOrNull { it.content == content }
        if (existing != null) {
            activeWindowId = existing.id
            return existing.id
        }
        return openWindow(content, placementFor(content, from))
    }

    fun close(id: Long) {
        val remaining = _windows.value.filterNot { it.id == id }
        if (remaining.size == _windows.value.size) return
        log.d("close: $id, ${remaining.size} left")
        _windows.value = remaining
        bounds.remove(id)
        if (activeWindowId == id) activeWindowId = remaining.lastOrNull()?.id
    }

    fun activate(id: Long) {
        if (activeWindowId != id) activeWindowId = id
    }

    /**
     * the window moved or was resized.
     *
     * the **size** is remembered against the window's kind, so the next log window opens at the size
     * the last one was left at rather than at whatever opened it. The position isn't kept past this
     * run: a saved position is only meaningful on the screens that were attached when it was saved,
     * and one restored onto a monitor that has since been unplugged is a window nobody can reach.
     */
    fun recordBounds(id: Long, at: WindowBounds) {
        bounds[id] = at
        val kind = _windows.value.firstOrNull { it.id == id }?.content?.kind ?: return
        val size = WindowSize(at.width, at.height)
        if (sizes[kind] == size) return
        sizes[kind] = size
        // this fires on every frame of a drag, so the file waits for the drag to stop. copied here
        // rather than read in there: the write runs on an io thread, and `sizes` is a plain map the
        // next frame of the same drag is still putting into
        val snapshot = sizes.toMap()
        sizeWrite.submit { saveSizes(snapshot) }
    }

    /** the size a window of [kind] opens at: whatever one of its kind was last left at. */
    private fun sizeOf(kind: WindowKind): WindowSize = sizes[kind] ?: WindowSize(
        kind.defaultWidth.value.toInt(),
        kind.defaultHeight.value.toInt(),
    )

    /**
     * how big a new window of [content]'s kind should be, and where to put it.
     *
     * the size is its kind's, never the parent's — a log window opening at the device list's size is
     * what this replaced. The position cascades off the last window of the same kind if there is one
     * (a second one stacked exactly on the first looks like nothing happened), otherwise off whatever
     * opened it, so it doesn't cover the window it came from. With nothing to cascade off it is
     * centred, which is what a negative x means to the entry point.
     */
    private fun placementFor(content: WindowContent, from: Long?): WindowBounds {
        val size = sizeOf(content.kind)
        val at = WindowBounds(width = size.width, height = size.height)
        val over = _windows.value.lastOrNull { it.content.kind == content.kind }?.id ?: from
        val parent = over?.let { bounds[it] }?.takeIf { it.x >= 0 && it.y >= 0 } ?: return at
        return at.copy(x = parent.x + CASCADE_OFFSET, y = parent.y + CASCADE_OFFSET)
    }

    // "<w>x<h>" rather than a nested object: this ends up in a properties file someone opens, and
    // 1200x700 says what it is where {"width":1200,...} makes them read it
    private fun saveSizes(sizes: Map<WindowKind, WindowSize>) {
        val asMap = sizes.entries.associate { (kind, size) -> kind.name to "${size.width}x${size.height}" }
        SettingsManager.setString(AppPref.WINDOW_SIZES, JsonUtils.toJson(asMap) ?: "")
    }

    private fun loadSizes(): Map<WindowKind, WindowSize> =
        JsonUtils.stringToMap<String>(SettingsManager.getString(AppPref.WINDOW_SIZES))
            .mapNotNull { (name, value) ->
                val kind = Utils.enumFromName<WindowKind>(name) ?: return@mapNotNull null
                val width = value.substringBefore('x').toIntOrNull() ?: return@mapNotNull null
                val height = value.substringAfter('x', "").toIntOrNull() ?: return@mapNotNull null
                // a size below the window minimum is one a previous build wrote wrong, or a window
                // someone shrank against a minimum that has since grown
                if (width < Dimens.minWindowWidth.value || height < Dimens.minWindowHeight.value) {
                    return@mapNotNull null
                }
                kind to WindowSize(width, height)
            }
            .toMap()
}
