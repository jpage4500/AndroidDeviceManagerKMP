package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*

/** a shell command someone saved, to run on whatever is selected. */
@Serializable
data class CustomCommand(val name: String, val command: String)

/**
 * the user's own adb commands, on the device menu.
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

    private fun persist(list: List<CustomCommand>) {
        _commands.value = list
        SettingsManager.setString(AppPref.CUSTOM_COMMANDS, JsonUtils.toJson(list))
    }

    private fun load(): List<CustomCommand> =
        JsonUtils.stringToList(SettingsManager.getString(AppPref.CUSTOM_COMMANDS))
}
