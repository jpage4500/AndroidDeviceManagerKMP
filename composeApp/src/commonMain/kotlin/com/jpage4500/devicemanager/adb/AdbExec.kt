package com.jpage4500.devicemanager.adb

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*

private val log = LoggerConfig.createLogger("AdbExec")

/**
 * run [command] on the device and hand back everything it printed.
 *
 * `exec:` rather than `shell:` throughout. `shell:` translates every `\n` into `\r\n` on the way
 * back, which is why jadb needs a whole `AdbFilterInputStream` to undo it again; `exec:` leaves the
 * bytes alone.
 */
suspend fun adbExec(
    server: AdbServer,
    serial: String,
    command: String,
    readMs: Long = AdbStream.EXEC_MS,
): AdbOutcome<String> {
    val opened = AdbConnection.toDevice(server, serial)
    val connection = when (opened) {
        is AdbOutcome.Err -> return opened
        is AdbOutcome.Ok -> opened.value
    }
    return connection.use {
        when (val outcome = it.request("exec:$command")) {
            is AdbOutcome.Err -> outcome
            is AdbOutcome.Ok -> try {
                val text = withTimeoutOrNull(readMs) { it.raw.readAll().decodeToString() }
                if (text == null) AdbOutcome.Err(AdbError.TIMEOUT, "the device didn't answer")
                else AdbOutcome.Ok(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AdbOutcome.Err(AdbError.DISCONNECTED, e.message ?: "the device went away")
            }
        }
    }
}

/**
 * run [command] and ignore whatever it prints.
 *
 * the reply still has to be read: `exec:` doesn't finish until the far end closes, and dropping the
 * socket first can cut the command off before it has run.
 */
suspend fun adbExecQuiet(server: AdbServer, serial: String, command: String): Boolean =
    adbExec(server, serial, command) is AdbOutcome.Ok

/**
 * true when [command] exited 0.
 *
 * the exit status isn't carried by the `exec:` service itself, so it's echoed and read back — the
 * same trick a shell script would use.
 */
suspend fun adbExecOk(server: AdbServer, serial: String, command: String): Boolean {
    val output = adbExec(server, serial, "$command >/dev/null 2>&1; echo $?").valueOrNull()
        ?: return false
    return output.trim() == "0"
}

/** everything `getprop` knows, as a map — the source of a device's model, OS and carrier. */
suspend fun adbProperties(server: AdbServer, serial: String): AdbOutcome<Map<String, String>> =
    adbExec(server, serial, "getprop").map { output -> parseGetProp(output) }

/** `[ro.product.model]: [Pixel 9]` per line. */
fun parseGetProp(output: String): Map<String, String> = output.lineSequence()
    .mapNotNull { line ->
        val key = line.substringAfter('[', "").substringBefore(']', "")
        if (key.isEmpty()) return@mapNotNull null
        val value = line.substringAfter("]: [", "").substringBeforeLast(']', "")
        key to value
    }
    .toMap()

/** fold a getprop map onto a device row. */
fun Device.withProperties(props: Map<String, String>): Device = copy(
    model = props[Device.PROP_MODEL]?.takeIf { it.isNotBlank() } ?: model,
    os = props[Device.PROP_OS],
    sdk = props[Device.PROP_SDK],
    timezone = props[Device.PROP_TIMEZONE],
    // often the carrier is just "," on a device with two empty SIM slots
    carrier = TextUtils.firstValid(props[Device.PROP_CARRIER], props[Device.PROP_CARRIER_2])
        ?.trim(',', ' ')
        ?.takeIf { it.isNotBlank() },
)

/** what the shell says a path really is, for the cases the sync protocol can't settle. */
enum class RemoteKind(val directory: Boolean) {
    /** nothing at that path — including a symlink whose target has gone. */
    MISSING(false),
    NOT_A_DIRECTORY(false),

    /** a directory the shell can't open: `-d` says yes, `-r -x` say no. still a directory. */
    UNREADABLE(true),
    DIRECTORY(true),

    /** the shell didn't answer. */
    UNKNOWN(false),
}

/**
 * what [remote] really is, asked of the shell in one round trip.
 *
 * two separate holes in the sync protocol need this. v1's `STAT` is an `lstat`, so where there is no
 * `stat_v2` — Android 7 and older — `/sdcard` comes back as the symlink standing in front of it. and
 * a v1 `LIST` explains nothing: a forbidden folder, a plain file and a path that isn't there all
 * answer with no entries and a clean `DONE`, exactly like an empty folder.
 */
suspend fun adbRemoteKind(server: AdbServer, serial: String, remote: String): RemoteKind {
    val quoted = Utils.shellQuote(remote)
    // -e follows symlinks, so a dangling /sdcard reports missing rather than "there but unreadable"
    val script = "if [ ! -e $quoted ]; then echo missing; " +
        "elif [ ! -d $quoted ]; then echo file; " +
        "elif [ -r $quoted -a -x $quoted ]; then echo dir; " +
        "else echo denied; fi"
    return when (adbExec(server, serial, script).valueOrNull()?.trim()) {
        "missing" -> RemoteKind.MISSING
        "file" -> RemoteKind.NOT_A_DIRECTORY
        "dir" -> RemoteKind.DIRECTORY
        "denied" -> RemoteKind.UNREADABLE
        else -> RemoteKind.UNKNOWN
    }
}
