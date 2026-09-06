package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.flow.*

/** where a window was, so reopening it doesn't throw away a position someone chose. */
data class WindowBounds(
    val x: Int = -1,
    val y: Int = -1,
    val width: Int = 1000,
    val height: Int = 640,
)

/** what a window opens on. */
sealed interface WindowContent {
    /** the device list, and whatever is selected in it. */
    data object DeviceList : WindowContent

    /** one device on its own — a mirror torn off into a window of its own. */
    data class Mirror(val serial: String) : WindowContent
}

/** one open window, as the entry point's `application` block sees it. */
class AppWindow(
    val id: Long,
    val content: WindowContent,
    val bounds: WindowBounds?,
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

    private val bounds = mutableMapOf<Long, WindowBounds>()
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
        log.d("openWindow: $id, ${_windows.value.size} already open")
        if (at != null) bounds[id] = at
        _windows.value = _windows.value + AppWindow(id, content, at)
        // whatever opened it is what the user is looking at, and the OS is about to give it focus
        activeWindowId = id
        return id
    }

    /** a mirror in a window of its own, beside the one it was opened from. */
    fun openMirror(serial: String, from: Long? = activeWindowId): Long {
        // already open somewhere: raise that rather than opening a second one on the same device
        val existing = _windows.value.firstOrNull {
            (it.content as? WindowContent.Mirror)?.serial == serial
        }
        if (existing != null) {
            activeWindowId = existing.id
            return existing.id
        }
        return openWindow(WindowContent.Mirror(serial), cascadeFrom(from))
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
     * nothing is written to disk here on purpose: this fires on every frame of a drag.
     */
    fun recordBounds(id: Long, at: WindowBounds) {
        bounds[id] = at
    }

    fun boundsOf(id: Long): WindowBounds? = bounds[id]

    /**
     * where a new window opened from [from] should go: down and across from it.
     *
     * a window whose position isn't known yet gives null, and the new one is centred rather than
     * placed at a guess.
     */
    private fun cascadeFrom(from: Long?): WindowBounds? {
        val parent = from?.let { bounds[it] } ?: return null
        if (parent.x < 0 || parent.y < 0) return null
        return parent.copy(x = parent.x + CASCADE_OFFSET, y = parent.y + CASCADE_OFFSET)
    }
}
