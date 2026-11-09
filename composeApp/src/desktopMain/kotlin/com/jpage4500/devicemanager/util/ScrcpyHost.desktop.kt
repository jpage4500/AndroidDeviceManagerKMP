package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.adb.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.*
import kotlin.concurrent.*

private val log = LoggerConfig.createLogger("ScrcpyHost")

// a bad flag or an unreachable device ends scrcpy within a second; one still running after this has
// opened its window
private const val START_GRACE_MS = 1_500L

actual object ScrcpyHost {
    actual val supported: Boolean = true

    actual fun resolve(configured: String?): String? =
        configured?.takeIf { it.isNotBlank() && File(it).isFile }
            ?: HostPath.find("scrcpy")

    actual suspend fun version(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(path, "--version").redirectErrorStream(true).start()
            val first = process.inputStream.bufferedReader().use { it.readLine() }
            process.waitFor(5, TimeUnit.SECONDS)
            first?.substringBefore(" <")?.trim()
        }.onFailure { log.e("version: ${it.message}") }.getOrNull()
    }

    actual suspend fun launch(path: String, args: List<String>, server: AdbServer): String? =
        withContext(Dispatchers.IO) {
            val builder = ProcessBuilder(listOf(path) + args).redirectErrorStream(true)
            HostPath.apply(builder.environment(), server)
            val process = try {
                builder.start()
            } catch (e: Exception) {
                log.e("launch: ${e.message}")
                return@withContext e.message ?: "couldn't start scrcpy"
            }
            // drained for as long as scrcpy runs — a full pipe would stall it — keeping the tail for
            // an error message if it quits straight away
            val tail = ConcurrentLinkedDeque<String>()
            thread(isDaemon = true, name = "scrcpy-output") {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        log.d("launch: $line")
                        tail.addLast(line)
                        if (tail.size > 5) tail.pollFirst()
                    }
                }
            }
            if (runInterruptible { process.waitFor(START_GRACE_MS, TimeUnit.MILLISECONDS) } && process.exitValue() != 0) {
                // the drain thread may still be on the last line
                delay(100)
                log.e("launch: exited ${process.exitValue()}")
                tail.lastOrNull { it.contains("ERROR", ignoreCase = true) } ?: tail.lastOrNull() ?: "scrcpy exited"
            } else {
                null
            }
        }
}
