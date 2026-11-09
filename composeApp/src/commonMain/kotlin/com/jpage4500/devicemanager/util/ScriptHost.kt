package com.jpage4500.devicemanager.util

import kotlinx.coroutines.channels.*

/** a script in the scripts folder: its file, and the name the Scripts page shows. */
data class Script(val path: String, val fileName: String) {
    /** `reboot_all.sh` reads as "reboot all" — the Swing app's naming, kept so a folder reads the same. */
    val name: String get() = fileName.substringBeforeLast('.').replace('_', ' ')
}

/** what a running script printed. */
sealed interface ScriptOutput {
    /** a finished line. */
    data class Line(val text: String) : ScriptOutput

    /** text left without a newline while the script went quiet — almost always a question it is waiting on. */
    data class Prompt(val text: String) : ScriptOutput
}

/**
 * a line typed for a running script.
 *
 * @param echo what its output shows in place of [text] — a password goes in, dots come out
 */
data class ScriptReply(val text: String, val echo: String)

/**
 * the machine's own shell scripts — the Scripts page.
 *
 * Desktop only: a phone has no folder of `.sh` files to run against devices, so [supported] is false
 * there and the page doesn't appear. The folder is `~/.device_manager`, the one the Swing app reads,
 * so scripts written for it show up here without moving.
 */
expect object ScriptHost {
    val supported: Boolean

    /** the scripts folder, made if it isn't there; null where scripts aren't supported. */
    fun folder(): String?

    /** every `.sh` (or `.bat` on Windows) in [folder], by name. */
    fun list(): List<Script>

    /**
     * run [script] with [args] and return its exit code, or null if it couldn't start.
     *
     * [onOutput] gets stdout and stderr together, in the order they arrived; each of [input] is
     * written to the script's stdin as a line, and closing [input] closes stdin.
     *
     * the adb the app found goes on the script's `PATH`, and the adb server the app
     * reads is passed along, so `adb -s $1 …` reaches the same devices the table shows.
     */
    suspend fun run(script: Script, args: List<String>, input: ReceiveChannel<ScriptReply>, onOutput: (ScriptOutput) -> Unit): Int?

    /**
     * a starter script in the folder, named from [name], whose example loops over the serials it's
     * given. @return it, or null if it couldn't be written
     */
    fun create(name: String): Script?

    /** open the script in a text editor — [AppPref.SCRIPT_EDITOR] if one is set, never the script's own runner. */
    fun edit(script: Script): Boolean
}
