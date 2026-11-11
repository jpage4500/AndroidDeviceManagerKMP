package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.serialization.*

private val log = LoggerConfig.createLogger("UpdateManager")

/**
 * is there a newer build than this one.
 *
 * The desktop ships through jDeploy as a GitHub release on a **separate public repo** — this one is
 * private, and a release API call against it would need a token no install has. See
 * docs/RELEASING.md. The check is the releases API's `latest`, which skips drafts and prereleases,
 * so the rolling `0.0.0-<branch>` build a branch push publishes never offers itself as an update.
 *
 * It only ever *reports*. Nothing here downloads or installs: jDeploy's own launcher updates the
 * desktop app on its next start, and there is no store channel for the phone builds yet — so the
 * offer is a link to the release page, on every platform.
 */
object UpdateManager {
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val TICK_MS = 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var backgroundJob: Job? = null

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/jpage4500/AndroidDeviceManagerKMP/releases/latest"

    sealed interface Result {
        data class UpToDate(val version: String) : Result
        data class Available(val version: String, val url: String) : Result
        data class Failed(val message: String) : Result
    }

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
    )

    /**
     * look for a release once a day while Settings › General's Check for updates is on, and say so
     * with a toast when there is one — the Swing app's scheduled check. Safe to call more than once.
     *
     * the timer ticks hourly and checks [AppPref.LAST_UPDATE_CHECK] rather than sleeping a day, so a
     * laptop that slept through the night still checks in the morning, and a restart doesn't check
     * again if it's been less than a day.
     */
    fun startBackgroundChecks() {
        if (backgroundJob != null) return
        backgroundJob = scope.launch {
            while (isActive) {
                val due = DateUtils.nowMs() - SettingsManager.getLong(AppPref.LAST_UPDATE_CHECK) >= CHECK_INTERVAL_MS
                if (due && SettingsManager.getBoolean(AppPref.CHECK_UPDATES)) {
                    val result = checkForUpdates()
                    log.d("startBackgroundChecks: $result")
                    if (result is Result.Available) {
                        ToastManager.showToast("Version ${result.version} is available", actionLabel = "Open") {
                            PlatformUtils.openUrl(result.url)
                        }
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    suspend fun checkForUpdates(): Result {
        SettingsManager.setLong(AppPref.LAST_UPDATE_CHECK, DateUtils.nowMs())

        val body = NetworkManager.downloadUrlToString(
            url = LATEST_RELEASE_URL,
            // the API answers without this, but versioned is what GitHub asks callers to send
            headers = mapOf("Accept" to "application/vnd.github+json"),
        ).getOrElse {
            log.e("checkForUpdates: ${it.message}")
            return Result.Failed(it.message ?: "")
        }

        val release = JsonUtils.fromJson<GithubRelease>(body)
        val latest = release?.tagName?.trim()?.removePrefix("v")
        if (latest.isNullOrEmpty()) {
            log.e("checkForUpdates: no tag in the latest release")
            return Result.Failed("no version in the latest release")
        }

        log.d("checkForUpdates: latest:$latest, this:$APP_VERSION")
        return if (isNewer(latest, APP_VERSION)) {
            Result.Available(latest, release.htmlUrl.orEmpty())
        } else {
            Result.UpToDate(APP_VERSION)
        }
    }

    /**
     * true when [candidate] is a later version than [current].
     *
     * segment by segment as numbers, so 1.0.100 is newer than 1.0.99 — which a string compare gets
     * backwards, and this scheme's third segment is a commit count that passes 99 quickly. A
     * segment that isn't a number counts as 0 rather than failing the check: a build someone tagged
     * by hand should not make the app claim an update forever.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}
