package com.jpage4500.devicemanager.util

import kotlin.time.TimeSource

/**
 * simple class to track and log the time it takes to perform a task
 */
class Timer {
    private var startMark: TimeSource.Monotonic.ValueTimeMark

    init {
        startMark = TimeSource.Monotonic.markNow()
    }

    /**
     * get elapsed time in milliseconds
     */
    fun elapsedTimeMs(): Long {
        return startMark.elapsedNow().inWholeMilliseconds
    }

    /**
     * @return true if timer elapsed time is < maxTimeMs
     */
    fun isValid(maxTimeMs: Long): Boolean {
        return elapsedTimeMs() < maxTimeMs
    }

    /**
     * reset the timer to current time
     */
    fun reset() {
        startMark = TimeSource.Monotonic.markNow()
    }

    /**
     * get formatted duration description
     * @return formatted string like "1m 30s" or "500ms"
     */
    fun desc(): String {
        return Utils.formatDuration(elapsedTimeMs())
    }

    override fun toString(): String {
        return "${elapsedTimeMs()}ms"
    }
}

