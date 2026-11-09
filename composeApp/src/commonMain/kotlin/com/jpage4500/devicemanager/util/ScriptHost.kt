package com.jpage4500.devicemanager.util

/** a script in the scripts folder: its file, and the name the Scripts page shows. */
data class Script(val path: String, val fileName: String) {
    /** `reboot_all.sh` reads as "reboot all" — the Swing app's naming, kept so a folder reads the same. */
    val name: String get() = fileName.substringBeforeLast('.').replace('_', ' ')
}

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
     * run [script] with [args], calling [onLine] with each line of output — stdout and stderr
     * together, in the order they arrived — and return its exit code, or null if it couldn't start.
     *
     * the adb the app found goes on the script's `PATH`, and the adb server the app reads is passed
     * along, so `adb -s $1 …` reaches the same devices the table shows.
     */
    suspend fun run(script: Script, args: List<String>, onLine: (String) -> Unit): Int?

    /**
     * a starter script in the folder, named from [name] — one device per run, and a note on looping
     * over `$@` for anyone who wants the Swing app's every-serial-at-once. @return it, or null if it
     * couldn't be written
     */
    fun create(name: String): Script?

    /** open the script in whatever edits it on this machine. */
    fun edit(script: Script): Boolean
}
