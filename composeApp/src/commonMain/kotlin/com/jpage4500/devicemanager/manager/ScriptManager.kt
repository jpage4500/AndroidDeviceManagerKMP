package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*

/** where one device's run of a script is. */
enum class RunState { RUNNING, DONE, FAILED }

/**
 * a script's run as a row in Results — [RunResults] is shared with Commands, whose rows are
 * devices; a script's one row is keyed [ScriptManager.WHOLE_RUN].
 */
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

/** a script's last run. */
@Serializable
data class ScriptRun(val fileName: String, val startedMs: Long, val devices: List<DeviceRun>) {
    val running: Boolean get() = devices.any { it.state == RunState.RUNNING }
    val failed: List<DeviceRun> get() = devices.filter { it.state == RunState.FAILED }
}

/**
 * a script waiting on an answer: the text it left without a newline, and where to send the reply.
 *
 * @param title the run's row: the devices it ran on, or the script with none
 * @param secret the question reads like a password, so the answer is typed into dots
 */
data class ScriptPrompt(
    val id: Long,
    val fileName: String,
    val rowId: String,
    val title: String,
    val text: String,
    val secret: Boolean,
)

/**
 * the Scripts page's engine: the scripts in the folder, and each one's last run.
 *
 * **A script runs once, with the serial of every device it runs on as `$1 $2 …`** — the Swing
 * app's way. Looping over them, and in what order or how many at a time, is the script's to decide;
 * one that has no use for devices ignores its arguments.
 *
 * A script that stops on a question is answered from a prompt the page shows ([prompts]), or by
 * anything typed for the row ([send]); both go to its stdin.
 */
object ScriptManager {
    /** the row id of a script's run. */
    const val WHOLE_RUN = "*"

    // a question that reads like it wants a password — answered into dots, echoed as dots
    private val SECRET = Regex("pass(word|phrase|code)?\\b|\\bpin\\b|token|secret", RegexOption.IGNORE_CASE)

    private val log = LoggerConfig.createLogger("ScriptManager")
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // a run's output is kept to its tail: a chatty script over 45 devices would otherwise sit in the
    // heap until the next run
    private const val MAX_OUTPUT_LINES = 2000

    // how often a running script's output reaches the screen; a line at a time would recompose the
    // results once per line
    private const val PUBLISH_MS = 150L

    private const val RUNS_FOLDER = "scripts"
    private const val RUNS_FILE = "last_runs.json"

    private val jobs = mutableMapOf<String, Job>()

    // each running row's stdin, by "<file>/<row>" — flows, since rows start on the io threads and
    // the page writes from the UI one
    private val inputs = MutableStateFlow<Map<String, Channel<ScriptReply>>>(emptyMap())
    private val nextPromptId = MutableStateFlow(0L)

    private val _prompts = MutableStateFlow<List<ScriptPrompt>>(emptyList())

    /** questions running scripts are waiting on, oldest first — the page shows one at a time. */
    val prompts: StateFlow<List<ScriptPrompt>> = _prompts.asStateFlow()

    private val _scripts = MutableStateFlow<List<Script>>(emptyList())
    val scripts: StateFlow<List<Script>> = _scripts.asStateFlow()

    private val _runs = MutableStateFlow(loadRuns())

    /** each script's last run, by file name. */
    val runs: StateFlow<Map<String, ScriptRun>> = _runs.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)

    /** the script the page has open, by file name — here so it outlasts a trip to another page. */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _chosen = MutableStateFlow<List<String>>(emptyList())

    /** the devices picked to act on — never "all": the user always chooses. */
    val chosen: StateFlow<List<String>> = _chosen.asStateFlow()

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

    private val _all = MutableStateFlow(true)

    /** whether a run goes to every ready device rather than [chosen] — the page's start. */
    val all: StateFlow<Boolean> = _all.asStateFlow()

    fun setAll(on: Boolean) {
        _all.value = on
    }

    /** pick devices to run on — which turns [all] off. */
    fun choose(deviceIds: List<String>) {
        _all.value = false
        _chosen.value = deviceIds
    }

    /** run [script] once, with the serial of each of [deviceIds] as an argument — none is fine. */
    fun run(script: Script, deviceIds: List<String>) {
        if (jobs[script.fileName]?.isActive == true) return
        log.d("run: ${script.fileName} on ${deviceIds.size} devices")
        val now = DateUtils.nowMs()
        val name = deviceIds.joinToString { DeviceManager.nameOf(it) }.ifEmpty { script.name }
        val row = DeviceRun(WHOLE_RUN, name, RunState.RUNNING, startedMs = now)
        _runs.update { runs -> runs + (script.fileName to ScriptRun(fileName = script.fileName, startedMs = now, devices = listOf(row))) }
        jobs[script.fileName] = scope.launch {
            runOne(script, row, deviceIds.map { Device.serialOf(it) })
            log.d("run: ${script.fileName} done, ${_runs.value[script.fileName]?.failed?.size ?: 0} failed")
            saveRuns()
        }
    }

    /** answer [prompt]: [text] goes to the script's stdin. */
    fun answer(prompt: ScriptPrompt, text: String) {
        _prompts.update { list -> list.filterNot { it.id == prompt.id } }
        send(prompt.fileName, prompt.rowId, text, secret = prompt.secret)
    }

    /** put [prompt] away unanswered — it wasn't a question, or it'll be answered from the output pane. */
    fun dismiss(prompt: ScriptPrompt) {
        _prompts.update { list -> list.filterNot { it.id == prompt.id } }
    }

    /** a line typed for a running row, to its stdin; a question it had open is answered by it. */
    fun send(fileName: String, rowId: String, text: String, secret: Boolean = false) {
        _prompts.update { list -> list.filterNot { it.fileName == fileName && it.rowId == rowId } }
        val echo = if (secret) "•".repeat(text.length.coerceIn(4, 12)) else text
        inputs.value[inputKey(fileName, rowId)]?.trySend(ScriptReply(text, "› $echo"))
    }

    fun stop(script: Script) {
        jobs.remove(script.fileName)?.cancel()
        _prompts.update { list -> list.filterNot { it.fileName == script.fileName } }
        // what was still going is a failure — it didn't finish, and Run again on failed should retry it
        update(script.fileName) { device ->
            if (device.state == RunState.RUNNING) device.copy(state = RunState.FAILED, endedMs = DateUtils.nowMs()) else device
        }
        saveRuns()
    }

    fun isRunning(script: Script): Boolean = jobs[script.fileName]?.isActive == true

    private suspend fun runOne(script: Script, row: DeviceRun, args: List<String>) {
        val rowId = row.deviceId
        val started = DateUtils.nowMs()
        update(script.fileName, rowId) { it.copy(startedMs = started) }
        // only this run's reader touches it: [ScriptHost.run] calls back one piece at a time
        val output = ArrayDeque<String>()
        var publishedAt = 0L
        val key = inputKey(script.fileName, rowId)
        val input = Channel<ScriptReply>(Channel.UNLIMITED)
        inputs.update { it + (key to input) }
        val code = try {
            ScriptHost.run(script, args, input) { piece ->
                val line = when (piece) {
                    is ScriptOutput.Line -> piece.text
                    is ScriptOutput.Prompt -> piece.text
                }
                output.addLast(line)
                if (output.size > MAX_OUTPUT_LINES) output.removeFirst()
                val now = DateUtils.nowMs()
                if (piece is ScriptOutput.Prompt) {
                    val prompt = ScriptPrompt(
                        id = nextPromptId.getAndUpdate { it + 1 },
                        fileName = script.fileName,
                        rowId = rowId,
                        title = row.name,
                        text = piece.text.trim(),
                        secret = SECRET.containsMatchIn(piece.text),
                    )
                    _prompts.update { it + prompt }
                }
                if (piece is ScriptOutput.Prompt || now - publishedAt >= PUBLISH_MS) {
                    publishedAt = now
                    val snapshot = output.toList()
                    update(script.fileName, rowId) { it.copy(output = snapshot) }
                }
            }
        } finally {
            inputs.update { it - key }
            input.close()
            _prompts.update { list -> list.filterNot { it.fileName == script.fileName && it.rowId == rowId } }
        }
        update(script.fileName, rowId) {
            it.copy(
                state = if (code == 0) RunState.DONE else RunState.FAILED,
                exitCode = code,
                endedMs = DateUtils.nowMs(),
                output = output.toList(),
            )
        }
    }

    private fun inputKey(fileName: String, rowId: String) = "$fileName/$rowId"

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
