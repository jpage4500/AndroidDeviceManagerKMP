package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*

/** a shell command someone saved, to run on whatever is selected. */
@Serializable
data class CustomCommand(val name: String, val command: String)

/**
 * the user's own adb commands — the Commands page's Saved list, and the device menu.
 *
 * Ported from the Swing app's `CommandScreen`. Deliberately plain shell strings rather than scripts:
 * a script has to exist on disk and be found, which is the dependency the whole port is avoiding —
 * see PORT-PLAN.md.
 */
object CommandStore {
    private val log = LoggerConfig.createLogger("CommandStore")

    private val _commands = MutableStateFlow(load())
    val commands: StateFlow<List<CustomCommand>> = _commands.asStateFlow()

    fun save(command: CustomCommand, replacing: String? = null) {
        log.d("save: ${command.name}")
        persist(_commands.value.filterNot { it.name == (replacing ?: command.name) } + command)
    }

    fun delete(name: String) = persist(_commands.value.filterNot { it.name == name })

    /**
     * the Swing app's `commands.txt` in the scripts folder, a `name = command` per line — read, never
     * written, so a file kept for the Swing app works here unchanged. Empty where there's no folder.
     */
    suspend fun fromFile(): List<CustomCommand> {
        val folder = ScriptHost.folder() ?: return emptyList()
        val text = readLocalFile("$folder/$COMMANDS_FILE")?.decodeToString() ?: return emptyList()
        return text.lines()
            .filter { '=' in it }
            // split on the first `=` only: the Swing app split on every one, and lost the rest of
            // a command like `settings put global x=1`
            .map { CustomCommand(it.substringBefore('=').trim(), it.substringAfter('=').trim()) }
            .filter { it.name.isNotEmpty() && it.command.isNotEmpty() }
            .sortedBy { it.name.lowercase() }
    }

    private fun persist(list: List<CustomCommand>) {
        _commands.value = list
        SettingsManager.setString(AppPref.CUSTOM_COMMANDS, JsonUtils.toJson(list))
    }

    private fun load(): List<CustomCommand> =
        JsonUtils.stringToList(SettingsManager.getString(AppPref.CUSTOM_COMMANDS))

    private const val COMMANDS_FILE = "commands.txt"
}
