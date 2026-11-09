package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.*
import java.awt.Desktop
import java.io.*

private val log = LoggerConfig.createLogger("ScriptHost")

actual object ScriptHost {
    actual val supported: Boolean = true

    // how long text without a newline has to sit before it counts as a question. a prompt is
    // printed then waited on forever; progress written in pieces keeps coming well inside this
    private const val PROMPT_IDLE_MS = 400L

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

    /**
     * what runs [file]: its `#!` line when that names a program that's there, else `/bin/sh`.
     * through an interpreter rather than exec'd, so a script saved without +x still runs — and by
     * its own one, since macOS's `/bin/sh` prints `echo -n` literally where bash doesn't.
     */
    private fun interpreter(file: File): List<String> {
        if (windows) return listOf("cmd", "/c")
        val shebang = try {
            file.bufferedReader().use { it.readLine() }
        } catch (e: Exception) {
            null
        }
        val command = shebang?.takeIf { it.startsWith("#!") }?.substring(2)?.trim()?.split(Regex("\\s+"))
        if (command != null && command.first().let { File(it).canExecute() }) return command
        return listOf("/bin/sh")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    actual suspend fun run(script: Script, args: List<String>, input: ReceiveChannel<ScriptReply>, onOutput: (ScriptOutput) -> Unit): Int? =
        withContext(Dispatchers.IO) {
            val file = File(script.path)
            val builder = ProcessBuilder(interpreter(file) + script.path + args)
                .directory(file.parentFile)
                .redirectErrorStream(true)
            environment(builder.environment())
            val process = try {
                builder.start()
            } catch (e: Exception) {
                log.e("run: ${script.fileName}: ${e.message}")
                onOutput(ScriptOutput.Line(e.message.orEmpty()))
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
                // read in chunks, not lines: a prompt is the text that never gets its newline
                val chunks = Channel<String>(Channel.UNLIMITED)
                launch {
                    try {
                        runInterruptible {
                            val reader = process.inputStream.reader()
                            val buffer = CharArray(4096)
                            while (true) {
                                val n = reader.read(buffer)
                                if (n < 0) break
                                chunks.trySend(String(buffer, 0, n))
                            }
                        }
                    } catch (e: IOException) {
                        // the stream closed under the read: the process was killed
                        log.d("run: ${script.fileName}: ${e.message}")
                    } finally {
                        chunks.close()
                    }
                }
                val stdin = process.outputStream.bufferedWriter()
                var inputOpen = true
                val partial = StringBuilder()
                // a \r with no \n after it is a progress line redrawing itself; it never prompts
                var redrawn = false
                var pendingCr = false
                var reading = true
                while (reading) {
                    select {
                        chunks.onReceiveCatching { result ->
                            val text = result.getOrNull()
                            if (text == null) {
                                reading = false
                                return@onReceiveCatching
                            }
                            for (c in text) {
                                if (pendingCr && c != '\n') {
                                    partial.clear()
                                    redrawn = true
                                }
                                pendingCr = false
                                when (c) {
                                    '\n' -> {
                                        onOutput(ScriptOutput.Line(partial.toString()))
                                        partial.clear()
                                        redrawn = false
                                    }
                                    '\r' -> pendingCr = true
                                    else -> partial.append(c)
                                }
                            }
                        }
                        if (inputOpen) {
                            input.onReceiveCatching { result ->
                                val reply = result.getOrNull()
                                try {
                                    if (reply == null) {
                                        inputOpen = false
                                        stdin.close()
                                    } else {
                                        runInterruptible {
                                            stdin.write(reply.text)
                                            stdin.newLine()
                                            stdin.flush()
                                        }
                                        onOutput(ScriptOutput.Line(reply.echo))
                                    }
                                } catch (e: Exception) {
                                    // the script has stopped reading — it ended, or closed stdin
                                    log.d("run: ${script.fileName}: stdin: ${e.message}")
                                    inputOpen = false
                                }
                            }
                        }
                        if (partial.isNotBlank() && !redrawn) {
                            onTimeout(PROMPT_IDLE_MS) {
                                if (process.isAlive) {
                                    onOutput(ScriptOutput.Prompt(partial.toString()))
                                    partial.clear()
                                }
                            }
                        }
                    }
                }
                if (partial.isNotEmpty()) onOutput(ScriptOutput.Line(partial.toString()))
                if (inputOpen) runCatching { stdin.close() }
                val code = runInterruptible { process.waitFor() }
                watcher.cancel()
                code
            }
        }

    /** the app's adb server, as the device table reads it — see [HostPath]. */
    private fun environment(env: MutableMap<String, String>) {
        val host = SettingsManager.getString(AppPref.ADB_SERVER_HOST)?.takeIf { it.isNotBlank() } ?: AdbServer.LOCAL.host
        HostPath.apply(env, AdbServer(host, SettingsManager.getInt(AppPref.ADB_SERVER_PORT)))
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
        val editor = SettingsManager.getString(AppPref.SCRIPT_EDITOR)?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            when {
                // an app name ("Sublime Text") on a Mac; a program elsewhere
                editor != null && mac -> ProcessBuilder("open", "-a", editor, file.absolutePath).start()
                editor != null -> ProcessBuilder(editor, file.absolutePath).start()
                // checked before Desktop: macOS claims EDIT but opens the file with its default app,
                // which for a .sh is Terminal or iTerm — running it. -t is the default text editor
                mac -> ProcessBuilder("open", "-t", file.absolutePath).start()
                Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.EDIT) ->
                    Desktop.getDesktop().edit(file)
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
        |#!/bin/bash
        |# runs once, with the serial of each device picked in Runs on as ${'$'}1 ${'$'}2 …
        |
        |for SERIAL in "${'$'}@"; do
        |    echo "${'$'}{SERIAL}: ${'$'}(adb -s "${'$'}{SERIAL}" shell getprop ro.product.model)"
        |done
        |""".trimMargin()

    private val BAT_TEMPLATE = """
        |@echo off
        |rem runs once, with the serial of each device picked in Runs on as %1 %2 ...
        |
        |for %%S in (%*) do adb -s %%S shell getprop ro.product.model
        |""".trimMargin().replace("\n", "\r\n")
}
