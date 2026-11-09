package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.*

/** something typed onto devices, kept so it can be sent again. A password is shown masked. */
@Serializable
data class RecentInput(val text: String, val isPassword: Boolean = false)

/**
 * how the last send went.
 *
 * @param id which send this is, so a slow device's answer to an earlier one isn't counted here
 * @param what the text sent, or the key's name — already masked for a password
 */
data class InputSend(val id: Int, val what: String, val total: Int, val done: Int = 0, val failed: Int = 0) {
    val running: Boolean get() = done + failed < total
}

/**
 * the Input page's engine: text and keys onto many devices at once, and the text sent lately.
 *
 * Ported from the Swing app's `InputScreen`, which typed on one device. Typing onto **several** is
 * the point here — it is how a bench of 45 gets the same URL or the same login without 45 taps.
 */
object InputManager {
    private val log = LoggerConfig.createLogger("InputManager")
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // the Swing app's figure
    private const val MAX_RECENT = 20

    // shells open at once — the same cap as Scripts and Commands, for the same USB hubs
    private const val MAX_PARALLEL = 8

    private const val PASSWORD_MASK = "••••••••"

    private val parallel = Semaphore(MAX_PARALLEL)
    private var sends = 0

    private val _recent = MutableStateFlow(loadRecent())

    /** text sent lately, newest first. */
    val recent: StateFlow<List<RecentInput>> = _recent.asStateFlow()

    private val _last = MutableStateFlow<InputSend?>(null)
    val last: StateFlow<InputSend?> = _last.asStateFlow()

    private val _chosen = MutableStateFlow<List<String>>(emptyList())

    /** the devices picked to act on — never "all": the user always chooses. */
    val chosen: StateFlow<List<String>> = _chosen.asStateFlow()

    fun choose(deviceIds: List<String>) {
        _chosen.value = deviceIds
    }

    /** type [text] on every device; it joins Recent once any device took it. */
    fun sendText(text: String, isPassword: Boolean, deviceIds: List<String>) {
        if (text.isEmpty()) return
        val what = if (isPassword) PASSWORD_MASK else text
        send(what, DeviceInput.Text(text), deviceIds, "Typing on") { remember(RecentInput(text, isPassword)) }
    }

    fun sendKey(keyCode: Int, name: String, deviceIds: List<String>) =
        send(name, DeviceInput.Key(keyCode), deviceIds, "$name on")

    fun forget(entry: RecentInput) = persist(_recent.value - entry)

    fun mask(entry: RecentInput): String = if (entry.isPassword) PASSWORD_MASK else entry.text

    private fun send(
        what: String,
        event: DeviceInput.Event,
        deviceIds: List<String>,
        label: String,
        onAnyDone: () -> Unit = {},
    ) {
        if (deviceIds.isEmpty()) return
        log.d("send: ${event::class.simpleName} to ${deviceIds.size} devices")
        val send = InputSend(++sends, what, deviceIds.size)
        _last.value = send
        val command = DeviceInput.shell(event)
        scope.launch {
            val results = deviceIds.map { id ->
                async {
                    parallel.withPermit {
                        val ok = DeviceManager.runCommand(id, command, label).isSuccess
                        _last.update { if (it?.id == send.id) it.counted(ok) else it }
                        ok
                    }
                }
            }.awaitAll()
            if (results.any { it }) onAnyDone()
        }
    }

    private fun InputSend.counted(ok: Boolean) = if (ok) copy(done = done + 1) else copy(failed = failed + 1)

    private fun remember(entry: RecentInput) =
        persist((listOf(entry) + _recent.value.filterNot { it.text == entry.text }).take(MAX_RECENT))

    private fun persist(list: List<RecentInput>) {
        _recent.value = list
        SettingsManager.setString(AppPref.RECENT_INPUT, JsonUtils.toJson(list))
    }

    private fun loadRecent(): List<RecentInput> {
        val saved = SettingsManager.getStringOrEmpty(AppPref.RECENT_INPUT)
        // before the Input page this was one string a line, from the Type text dialog
        if (saved.isNotEmpty() && !saved.startsWith("[")) {
            return saved.split('\n').filter { it.isNotBlank() }.map { RecentInput(it) }
        }
        return JsonUtils.stringToList(saved)
    }
}
