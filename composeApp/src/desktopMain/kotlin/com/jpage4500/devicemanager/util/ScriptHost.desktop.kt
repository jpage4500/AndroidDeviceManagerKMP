package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.data.*
import kotlinx.coroutines.*
import java.awt.Desktop
import java.io.File

private val log = LoggerConfig.createLogger("ScriptHost")

actual object ScriptHost {
    actual val supported: Boolean = true

    private val os = System.getProperty("os.name").orEmpty()
    private val windows = os.startsWith("Windows", ignoreCase = true)
    private val mac = os.startsWith("Mac", ignoreCase = true)
    private val extension = if (windows) "bat" else "sh"

    actual fun folder(): String? {
        val folder = File(System.getProperty("user.home"), ".device_manager")
        if (!folder.isDirectory && !folder.mkdirs()) {
            log.e("folder: can't create ${folder.absolutePath}")
            return null
        }
        return folder.absolutePath
    }

    actual fun list(): List<Script> {
        val folder = folder()?.let(::File) ?: return emptyList()
        return folder.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals(extension, ignoreCase = true) }
            .map { Script(it.absolutePath, it.name) }
            .sortedBy { it.name.lowercase() }
    }

    actual suspend fun run(script: Script, args: List<String>, onLine: (String) -> Unit): Int? =
        withContext(Dispatchers.IO) {
            // through the shell rather than exec'd directly, so a script saved without +x still runs
            val command = if (windows) listOf("cmd", "/c", script.path) + args else listOf("/bin/sh", script.path) + args
            val builder = ProcessBuilder(command)
                .directory(File(script.path).parentFile)
                .redirectErrorStream(true)
            environment(builder.environment())
            val process = try {
                builder.start()
            } catch (e: Exception) {
                log.e("run: ${script.fileName}: ${e.message}")
                onLine(e.message.orEmpty())
                return@withContext null
            }
            coroutineScope {
                // a cancelled run takes its process with it; that closes the stream the read is
                // blocked on, which is what lets it end
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        process.destroyForcibly()
                    }
                }
                val code = runInterruptible {
                    process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
                    process.waitFor()
                }
                watcher.cancel()
                code
            }
        }

    /**
     * `PATH` with the usual homes of `adb` in front, and the app's adb server passed on.
     *
     * an app started from the Dock or Finder gets launchd's bare `PATH`, which has no Android SDK on
     * it — a script that works in a terminal would fail here with "adb: not found".
     */
    private fun environment(env: MutableMap<String, String>) {
        val home = System.getProperty("user.home")
        val candidates = listOfNotNull(
            System.getenv("ANDROID_HOME")?.let { "$it/platform-tools" },
            System.getenv("ANDROID_SDK_ROOT")?.let { "$it/platform-tools" },
            "$home/Library/Android/sdk/platform-tools",
            "$home/Android/Sdk/platform-tools",
            "/opt/homebrew/bin",
            "/usr/local/bin",
        ).filter { File(it).isDirectory }
        val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val current = env[key].orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }
        env[key] = (candidates.filterNot { it in current } + current).joinToString(File.pathSeparator)
        // adb reads this for which server to talk to — the same one the device table comes from
        val host = SettingsManager.getString(AppPref.ADB_SERVER_HOST)
        if (!host.isNullOrBlank() && host != "127.0.0.1" && host != "localhost") env["ANDROID_ADB_SERVER_ADDRESS"] = host
    }

    actual fun create(name: String): Script? {
        val folder = folder()?.let(::File) ?: return null
        val base = name.trim().replace(Regex("\\s+"), "_").replace(Regex("[^A-Za-z0-9_-]"), "").ifEmpty { "script" }
        // never over an existing one: new_script.sh, new_script_2.sh, …
        val file = generateSequence(1) { it + 1 }
            .map { n -> File(folder, if (n == 1) "$base.$extension" else "${base}_$n.$extension") }
            .first { !it.exists() }
        return try {
            file.writeText(if (windows) BAT_TEMPLATE else SH_TEMPLATE)
            file.setExecutable(true)
            Script(file.absolutePath, file.name)
        } catch (e: Exception) {
            log.e("create: ${file.name}: ${e.message}")
            null
        }
    }

    actual fun edit(script: Script): Boolean {
        val file = File(script.path)
        return try {
            when {
                Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.EDIT) ->
                    Desktop.getDesktop().edit(file)
                // macOS has no EDIT action, and "open" on a .sh runs it in Terminal; -t is the text editor
                mac -> ProcessBuilder("open", "-t", file.absolutePath).start()
                windows -> ProcessBuilder("notepad", file.absolutePath).start()
                else -> ProcessBuilder("xdg-open", file.absolutePath).start()
            }
            true
        } catch (e: Exception) {
            log.e("edit: ${script.fileName}: ${e.message}")
            false
        }
    }

    private val SH_TEMPLATE = """
        |#!/bin/sh
        |# runs once per device: ${'$'}1 is that device's serial
        |# (scripts written for the Swing app got every serial at once — loop over "${'$'}@" for that)
        |
        |adb -s "${'$'}1" shell getprop ro.product.model
        |""".trimMargin()

    private val BAT_TEMPLATE = """
        |@echo off
        |rem runs once per device: %1 is that device's serial
        |
        |adb -s %1 shell getprop ro.product.model
        |""".trimMargin().replace("\n", "\r\n")
}
