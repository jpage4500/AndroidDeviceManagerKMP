package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * something the app is doing, said once in the status bar.
 *
 * @param progress 0..100, or -1 when there is nothing to show a bar for
 * @param detail what the bar has no room for — one line per device, say — shown in the History
 *   dialog and never on the bar itself
 */
data class StatusEvent(
    val label: String,
    val progress: Int = -1,
    val isError: Boolean = false,
    val detail: String? = null,
    val timeMs: Long = DateUtils.nowMs(),
) {
    /** nothing more is coming for this event. */
    val isTerminal: Boolean get() = isError || progress < 0 || progress >= 100
}

/**
 * the running commentary in the status bar, and the history behind it.
 *
 * separate from [ToastManager]: a toast interrupts, this doesn't. Commands that take a moment say so
 * here, and what they said stays in [history] so "what just happened?" has an answer.
 */
object StatusManager {
    private const val MAX_HISTORY = 100

    // the Swing app's cap: 45 devices' worth of lines is a few kB, a stray command's output could be MBs
    private const val MAX_DETAIL = 10_000

    // how long a finished event stays on the bar before it goes back to idle
    private const val REVERT_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var revertJob: Job? = null

    private val _current = MutableStateFlow<StatusEvent?>(null)
    val current: StateFlow<StatusEvent?> = _current.asStateFlow()

    private val _history = MutableStateFlow<List<StatusEvent>>(emptyList())
    val history: StateFlow<List<StatusEvent>> = _history.asStateFlow()

    fun post(label: String, progress: Int = -1, isError: Boolean = false, detail: String? = null) {
        val event = StatusEvent(label, progress, isError, detail?.take(MAX_DETAIL))
        _current.value = event
        // newest first, so the history dialog reads top-down. `update`, because the device tracker
        // posts from whichever thread its list arrived on
        _history.update { (listOf(event) + it).take(MAX_HISTORY) }

        revertJob?.cancel()
        if (event.isTerminal) {
            revertJob = scope.launch {
                delay(REVERT_MS)
                _current.value = null
            }
        }
    }

    fun clearHistory() {
        _history.value = emptyList()
        _current.value = null
    }
}
