package com.jpage4500.devicemanager.util

import kotlinx.coroutines.*

/**
 * a write that waits for the calls to stop coming.
 *
 * A column resize is not one change: `Modifier.draggable` reports every delta, so dragging a
 * column's edge across the window is a hundred calls — and every one of them rewrote the whole
 * settings file, on the thread the drag was running on. The **in-memory** value still changes on
 * every pixel, because that is what the table draws from; only the file waits.
 *
 * The last call wins: [submit] cancels whatever was pending, so what lands is the value the drag
 * ended on. [cancel] drops it entirely, for a caller that is about to write something else to the
 * same place — without that, "Reset Columns" mid-drag would be overwritten by the drag that
 * preceded it.
 */
class Debouncer(private val delayMs: Long = DEFAULT_DELAY_MS) {
    // its own scope: nothing here belongs to a screen, and a resize that outlives the window it was
    // dragged in still has to land
    private val scope = CoroutineScope(ioDispatcher)
    // not guarded, because both entry points are called from the gesture or the menu item that
    // produced the change — always the UI thread. Only the write itself leaves it
    private var pending: Job? = null

    fun submit(write: () -> Unit) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            write()
        }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }

    companion object {
        // long enough to sit out a pause mid-drag, short enough that quitting straight after a
        // resize still keeps it
        private const val DEFAULT_DELAY_MS = 250L
    }
}
