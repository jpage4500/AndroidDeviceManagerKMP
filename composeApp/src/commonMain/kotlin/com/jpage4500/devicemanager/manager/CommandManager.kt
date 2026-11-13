package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*

/** one run of a shell command, across every device it went to. */
data class CommandRun(val command: String, val devices: List<DeviceRun>) {
    val running: Boolean get() = devices.any { it.state == RunState.RUNNING }
}

/**
 * the Commands page's engine: the commands run lately, and the last run's results.
 *
 * Ported from the Swing app's `CommandScreen`, which sent one command to the selection and showed
 * the joined output in the message viewer. Here a command runs **once per device with a row each**,
 * the way Scripts does, so one device's failure can be seen — and run again — without the other 44.
 *
 * Only the last run is kept, and only in memory: a command is one line to type again, which a
 * script's run is not.
 */
object CommandManager {
    private val log = LoggerConfig.createLogger("CommandManager")
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // shells open at once — the same cap as Scripts, for the same USB hubs
    private const val MAX_PARALLEL = 8

    private const val MAX_RECENT = 10

    // a device's output is kept to its tail: `dumpsys` on 45 devices is a lot of heap for one look
    private const val MAX_OUTPUT_LINES = 2_000

    // echoed after the command so its exit status comes back with its output: neither `exec:` nor
    // a Device Manager server's /api/execute carries one. On a line of its own so a trailing comment
    // in the command can't swallow it
    private const val EXIT_MARK = "__adm_exit:"

    private val parallel = Semaphore(MAX_PARALLEL)
    private var job: Job? = null

    private val _recent = MutableStateFlow(loadRecent())

    /** commands run lately, newest first. */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private val _run = MutableStateFlow<CommandRun?>(null)

    /** the last command run, and how it went on each device; null until one has run. */
    val run: StateFlow<CommandRun?> = _run.asStateFlow()

    private val _chosen = MutableStateFlow<List<String>>(emptyList())

    /** the devices picked to act on — never "all": the user always chooses. */
    val chosen: StateFlow<List<String>> = _chosen.asStateFlow()

    /** a device newly chosen joins [RecentDevices.commands], for its chip beside the + once it's dropped. */
    fun choose(deviceIds: List<String>) {
        (deviceIds - _chosen.value.toSet()).reversed().forEach(RecentDevices.commands::add)
        _chosen.value = deviceIds
    }

    /**
     * run [command] once per device, at most [MAX_PARALLEL] at a time.
     *
     * @param retry the devices are the last run's failures: the ones that passed keep their rows
     */
    fun run(command: String, deviceIds: List<String>, retry: Boolean = false) {
        val clean = sanitize(command)
        if (clean.isEmpty() || deviceIds.isEmpty() || job?.isActive == true) return
        log.d("run: $clean on ${deviceIds.size} devices${if (retry) " (retry)" else ""}")
        if (!retry) remember(clean)
        val now = DateUtils.nowMs()
        val kept = if (retry) _run.value?.devices.orEmpty().filterNot { it.deviceId in deviceIds } else emptyList()
        _run.value = CommandRun(
            command = clean,
            devices = kept + deviceIds.map { DeviceRun(it, DeviceManager.nameOf(it), RunState.RUNNING, startedMs = now) },
        )
        job = scope.launch {
            deviceIds.map { id -> launch { parallel.withPermit { runOne(clean, id) } } }.joinAll()
            log.d("run: $clean done, ${_run.value?.devices?.count { it.state == RunState.FAILED } ?: 0} failed")
        }
    }

    /** stop waiting on what's still running; those devices count as failed, so they can be retried. */
    fun stop() {
        job?.cancel()
        job = null
        update { if (it.state == RunState.RUNNING) it.copy(state = RunState.FAILED, endedMs = DateUtils.nowMs()) else it }
    }

    fun forget(command: String) = persistRecent(_recent.value - command)

    /**
     * a command as `adb shell` wants it: what's pasted from a terminal often starts with `adb shell`,
     * which the device has never heard of. The Swing app's `santizeCommand`.
     */
    fun sanitize(command: String): String = command.trim().removePrefix("adb ").trim().removePrefix("shell ").trim()

    private suspend fun runOne(command: String, deviceId: String) {
        val started = DateUtils.nowMs()
        update(deviceId) { it.copy(startedMs = started) }
        val result = DeviceManager.runCommand(deviceId, "$command\necho $EXIT_MARK$?")
        val lines = result.getOrNull()?.lines()?.map { it.trimEnd('\r') }.orEmpty()
        // no mark is a command that ended the shell itself (`exit 3`) — its status is unknown, not bad
        val code = lines.lastOrNull { it.startsWith(EXIT_MARK) }?.removePrefix(EXIT_MARK)?.trim()?.toIntOrNull()
        val output = result.fold(
            onSuccess = { lines.filterNot { it.startsWith(EXIT_MARK) }.dropLastWhile { it.isBlank() } },
            onFailure = { listOfNotNull(it.message) },
        )
        update(deviceId) {
            it.copy(
                state = if (result.isSuccess && (code ?: 0) == 0) RunState.DONE else RunState.FAILED,
                exitCode = code,
                endedMs = DateUtils.nowMs(),
                output = output.takeLast(MAX_OUTPUT_LINES),
            )
        }
    }

    private fun update(deviceId: String, change: (DeviceRun) -> DeviceRun) =
        update { if (it.deviceId == deviceId) change(it) else it }

    private fun update(change: (DeviceRun) -> DeviceRun) {
        _run.update { run -> run?.copy(devices = run.devices.map(change)) }
    }

    private fun remember(command: String) =
        persistRecent((listOf(command) + _recent.value.filterNot { it == command }).take(MAX_RECENT))

    private fun persistRecent(list: List<String>) {
        _recent.value = list
        SettingsManager.setString(AppPref.RECENT_COMMANDS, JsonUtils.toJson(list))
    }

    private fun loadRecent(): List<String> =
        JsonUtils.stringToList(SettingsManager.getString(AppPref.RECENT_COMMANDS))
}
