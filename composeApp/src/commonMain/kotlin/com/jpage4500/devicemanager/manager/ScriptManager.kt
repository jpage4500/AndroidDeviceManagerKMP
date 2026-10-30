package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.*

/** where one device's run of a script is. */
enum class RunState { RUNNING, DONE, FAILED }

/** one device's run of a script: its result, and its output. */
@Serializable
data class DeviceRun(
    val deviceId: String,
    val name: String,
    val state: RunState,
    val exitCode: Int? = null,
    val startedMs: Long,
    val endedMs: Long? = null,
    val output: List<String> = emptyList(),
) {
    val lastLine: String? get() = output.lastOrNull { it.isNotBlank() }
}

/** a script's last run, across every device it ran on. */
@Serializable
data class ScriptRun(val fileName: String, val startedMs: Long, val devices: List<DeviceRun>) {
    val running: Boolean get() = devices.any { it.state == RunState.RUNNING }
    val failed: List<DeviceRun> get() = devices.filter { it.state == RunState.FAILED }
}

/**
 * the Scripts page's engine: the scripts in the folder, and each one's last run.
 *
 * **A script runs once per device, with that device's serial as `$1`** — not once with every serial,
 * as the Swing app does. That is what gives each device a result of its own, and what lets one
 * device's failure be run again without the other 44.
 */
object ScriptManager {
    private val log = LoggerConfig.createLogger("ScriptManager")
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // runs at once, across every script; a script that opens adb shells on 45 devices at once is
    // what knocks a USB hub over. 8 to start — the number to tune on the rig
    private const val MAX_PARALLEL = 8

    // a device's output is kept to its tail: 45 devices of a chatty script would otherwise sit in
    // the heap until the next run
    private const val MAX_OUTPUT_LINES = 500

    // how often a running device's output reaches the screen; a line at a time would recompose the
    // results table once per line per device
    private const val PUBLISH_MS = 150L

    private const val RUNS_FOLDER = "scripts"
    private const val RUNS_FILE = "last_runs.json"

    private val parallel = Semaphore(MAX_PARALLEL)
    private val jobs = mutableMapOf<String, Job>()

    private val _scripts = MutableStateFlow<List<Script>>(emptyList())
    val scripts: StateFlow<List<Script>> = _scripts.asStateFlow()

    private val _runs = MutableStateFlow(loadRuns())

    /** each script's last run, by file name. */
    val runs: StateFlow<Map<String, ScriptRun>> = _runs.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)

    /** the script the page has open, by file name — here so it outlasts a trip to another page. */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _chosen = MutableStateFlow<List<String>?>(null)

    /** the devices "Choose…" has picked, or null for All. */
    val chosen: StateFlow<List<String>?> = _chosen.asStateFlow()

    /** read the folder again, off the UI thread. */
    fun reload() {
        scope.launch {
            val found = ScriptHost.list()
            _scripts.value = found
            if (_selected.value !in found.map { it.fileName }) _selected.value = found.firstOrNull()?.fileName
        }
    }

    fun select(fileName: String) {
        _selected.value = fileName
    }

    /** null is All; a list, even an empty one, is Choose… with those chips. */
    fun choose(deviceIds: List<String>?) {
        _chosen.value = deviceIds
    }

    /**
     * run [script] once per device, at most [MAX_PARALLEL] at a time.
     *
     * @param retry the devices are the last run's failures: the ones that passed keep their rows
     */
    fun run(script: Script, deviceIds: List<String>, retry: Boolean = false) {
        if (deviceIds.isEmpty() || jobs[script.fileName]?.isActive == true) return
        log.d("run: ${script.fileName} on ${deviceIds.size} devices${if (retry) " (retry)" else ""}")
        val now = DateUtils.nowMs()
        _runs.update { runs ->
            val kept = if (retry) runs[script.fileName]?.devices.orEmpty().filterNot { it.deviceId in deviceIds } else emptyList()
            runs + (script.fileName to ScriptRun(
                fileName = script.fileName,
                startedMs = now,
                devices = kept + deviceIds.map { DeviceRun(it, DeviceManager.nameOf(it), RunState.RUNNING, startedMs = now) },
            ))
        }
        jobs[script.fileName] = scope.launch {
            deviceIds.map { id -> launch { parallel.withPermit { runOne(script, id) } } }.joinAll()
            val run = _runs.value[script.fileName]
            log.d("run: ${script.fileName} done, ${run?.failed?.size ?: 0} failed")
            saveRuns()
        }
    }

    fun stop(script: Script) {
        jobs.remove(script.fileName)?.cancel()
        // what was still going is a failure — it didn't finish, and Run again on failed should retry it
        update(script.fileName) { device ->
            if (device.state == RunState.RUNNING) device.copy(state = RunState.FAILED, endedMs = DateUtils.nowMs()) else device
        }
        saveRuns()
    }

    fun isRunning(script: Script): Boolean = jobs[script.fileName]?.isActive == true

    private suspend fun runOne(script: Script, deviceId: String) {
        val started = DateUtils.nowMs()
        update(script.fileName, deviceId) { it.copy(startedMs = started) }
        // only this run's reader touches it: [ScriptHost.run] calls back one line at a time
        val output = ArrayDeque<String>()
        var publishedAt = 0L
        val code = ScriptHost.run(script, listOf(Device.serialOf(deviceId))) { line ->
            output.addLast(line)
            if (output.size > MAX_OUTPUT_LINES) output.removeFirst()
            val now = DateUtils.nowMs()
            if (now - publishedAt >= PUBLISH_MS) {
                publishedAt = now
                val snapshot = output.toList()
                update(script.fileName, deviceId) { it.copy(output = snapshot) }
            }
        }
        update(script.fileName, deviceId) {
            it.copy(
                state = if (code == 0) RunState.DONE else RunState.FAILED,
                exitCode = code,
                endedMs = DateUtils.nowMs(),
                output = output.toList(),
            )
        }
    }

    private fun update(fileName: String, deviceId: String, change: (DeviceRun) -> DeviceRun) =
        update(fileName) { if (it.deviceId == deviceId) change(it) else it }

    private fun update(fileName: String, change: (DeviceRun) -> DeviceRun) {
        _runs.update { runs ->
            val run = runs[fileName] ?: return@update runs
            runs + (fileName to run.copy(devices = run.devices.map(change)))
        }
    }

    // the last run of each script outlives the app, so the list can say how each one last went
    private fun saveRuns() {
        val json = JsonUtils.toJson(_runs.value.values.filterNot { it.running }.toList()) ?: return
        StorageManager.writeFile(RUNS_FOLDER, RUNS_FILE, json)
    }

    private fun loadRuns(): Map<String, ScriptRun> =
        JsonUtils.fromJson<List<ScriptRun>>(StorageManager.readFile(RUNS_FOLDER, RUNS_FILE))
            .orEmpty()
            .associateBy { it.fileName }
}
