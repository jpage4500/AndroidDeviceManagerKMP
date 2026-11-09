package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.adb.*
import kotlinx.coroutines.*
import java.util.concurrent.*

private val log = LoggerConfig.createLogger("AdbServerHost")

// start-server returns once the daemon is listening — a second or two, longer on a first run that
// writes ~/.android/adbkey
private const val START_MS = 15_000L

actual object AdbServerHost {
    actual suspend fun start(server: AdbServer): Boolean = withContext(Dispatchers.IO) {
        val adb = HostPath.find("adb")
        if (adb == null) {
            log.d("start: no adb found")
            return@withContext false
        }
        log.d("start: $adb -P ${server.port} start-server")
        runCatching {
            // output discarded rather than piped: the daemon adb forks inherits the pipe and would
            // hold it open for as long as it runs
            val builder = ProcessBuilder(adb, "-P", server.port.toString(), "start-server")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            HostPath.apply(builder.environment(), null)
            val process = builder.start()
            if (!runInterruptible { process.waitFor(START_MS, TimeUnit.MILLISECONDS) }) {
                process.destroy()
                log.e("start: timed out")
                false
            } else {
                process.exitValue() == 0
            }
        }.onFailure { log.e("start: ${it.message}") }.getOrDefault(false)
    }
}
