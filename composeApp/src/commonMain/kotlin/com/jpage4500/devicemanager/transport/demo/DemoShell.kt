package com.jpage4500.devicemanager.transport.demo

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*

/**
 * a small `sh` for the demo devices — enough of one that every screen's real commands get real
 * answers.
 *
 * The point is that nothing above the transport knows these devices are invented. The detail fetch
 * sends its batches (`getprop ; echo __ADM_NEXT__ ; settings get …`), Commands appends
 * `echo __adm_exit:$?`, Files sends `rm -rf`, `mkdir -p` and `mv`, install sends `pm install`, the
 * log page sends `ps` — and each is parsed here as a shell would: `;`, `&&`, `||` and newlines between
 * statements, `|` into `grep`, `head`, `tail` or `wc`, `>` into a file, quotes and `$?`. A command
 * that isn't here says so the way a phone does, with exit status 127.
 *
 * Holds what using a device changes — nicknames, custom fields, its files, a reboot — so that
 * what the app writes is what it reads back.
 */
internal class DemoShell(private val fleet: List<DemoDevice>) {
    private val startMs = DateUtils.nowMs()
    private val nicknames = mutableMapOf<String, String?>()
    private val customProps = mutableMapOf<String, String>()
    private val files = mutableMapOf<String, DemoFiles>()
    private val bootedAtMs = mutableMapOf<String, Long>()

    fun files(device: DemoDevice): DemoFiles = files.getOrPut(device.serial) { DemoFiles(device, startMs) }

    fun nickname(device: DemoDevice): String? =
        if (nicknames.containsKey(device.serial)) nicknames[device.serial] else device.nickname

    /** a reboot: the device answers as one still coming up for a while. */
    fun reboot(device: DemoDevice) {
        bootedAtMs[device.serial] = DateUtils.nowMs()
    }

    fun isBooting(device: DemoDevice): Boolean {
        if (device.booting) return true
        val at = bootedAtMs[device.serial] ?: return false
        return DateUtils.nowMs() - at < REBOOT_MS
    }

    fun properties(device: DemoDevice): Map<String, String> = buildMap {
        put(Device.PROP_MODEL, device.model)
        put(Device.PROP_BRAND, device.brand)
        put("ro.product.manufacturer", device.brand.replaceFirstChar { it.uppercase() })
        put(Device.PROP_NAME, device.model.lowercase().replace(' ', '_'))
        put(Device.PROP_OS, device.os)
        put(Device.PROP_SDK, device.sdk)
        put(Device.PROP_TIMEZONE, "America/New_York")
        put("ro.serialno", device.serial.substringBefore(':'))
        put("ro.build.type", "user")
        put("ro.build.id", "AP${device.os}A.${240905 + device.index}.00${device.index % 9}")
        put("ro.build.version.security_patch", "2026-10-05")
        put("ro.product.cpu.abi", "arm64-v8a")
        put("persist.sys.locale", "en-US")
        put("sys.boot_completed", if (isBooting(device)) "" else "1")
        device.carrier?.let {
            put(Device.PROP_CARRIER, it)
            put(Device.PROP_CARRIER_2, it)
        }
    }

    /** everything [script] prints, and its last exit status. */
    fun run(device: DemoDevice, script: String): Pair<String, Int> {
        val out = StringBuilder()
        var status = 0
        for ((op, statement) in statements(script)) {
            if (op == "&&" && status != 0) continue
            if (op == "||" && status == 0) continue
            val (text, code) = statement(device, statement, status)
            out.append(text)
            if (text.isNotEmpty() && !text.endsWith('\n')) out.append('\n')
            status = code
        }
        return out.toString() to status
    }

    // ── parsing ─────────────────────────────────────────────────────────────────────────────────

    /** one statement: redirects, then a pipeline. */
    private fun statement(device: DemoDevice, text: String, lastStatus: Int): Pair<String, Int> {
        var line = text.trim()
        if (line.isEmpty()) return "" to lastStatus
        val quietErrors = line.contains("2>/dev/null")
        line = line.replace("2>/dev/null", "").replace("2>&1", "").trim()
        // a plain `>` into a file — what the custom properties are written with
        var target: String? = null
        splitTop(line, ">").takeIf { it.size == 2 }?.let { (left, right) ->
            line = left.trim()
            target = words(right.trim(), lastStatus).firstOrNull()
        }
        val stages = splitTop(line, "|")
        var (output, status) = command(device, words(stages.first(), lastStatus))
        stages.drop(1).forEach { output = filter(output, words(it, lastStatus)) }
        if (quietErrors && status != 0) output = ""
        target?.let { path ->
            write(device, path, output)
            return "" to status
        }
        return output to status
    }

    /** split on `;`, `&&`, `||` and newlines outside quotes, keeping the operator before each piece. */
    private fun statements(script: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var op = ";"
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < script.length) {
            val c = script[i]
            val next = script.getOrNull(i + 1)
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null else if (c == '\\' && quote == '"' && next != null) {
                        current.append(next)
                        i++
                    }
                }
                c == '\\' && next != null -> {
                    // an escaped character outside quotes — `'\''` is how a single quote is written
                    current.append(c).append(next)
                    i++
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                }
                c == ';' || c == '\n' -> {
                    result += op to current.toString()
                    current.clear()
                    op = ";"
                }
                (c == '&' && next == '&') || (c == '|' && next == '|') -> {
                    result += op to current.toString()
                    current.clear()
                    op = "$c$c"
                    i++
                }
                else -> current.append(c)
            }
            i++
        }
        result += op to current.toString()
        return result
    }

    /** split [text] on a single-character [separator] outside quotes (and not doubled). */
    private fun splitTop(text: String, separator: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for ((i, c) in text.withIndex()) {
            when {
                escaped -> {
                    current.append(c)
                    escaped = false
                }
                quote == null && c == '\\' -> {
                    current.append(c)
                    escaped = true
                }
                quote != null -> {
                    if (c == quote) quote = null
                    current.append(c)
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                }
                c.toString() == separator && text.getOrNull(i + 1)?.toString() != separator &&
                    text.getOrNull(i - 1)?.toString() != separator -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }

    /** the words of one command, quotes removed and `$?` filled in. */
    private fun words(text: String, lastStatus: Int): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var inWord = false
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote == '\'' -> if (c == '\'') quote = null else current.append(c)
                quote == '"' -> when {
                    c == '"' -> quote = null
                    c == '\\' && i + 1 < text.length -> current.append(text[++i])
                    c == '$' && text.getOrNull(i + 1) == '?' -> current.append(lastStatus).also { i++ }
                    else -> current.append(c)
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    inWord = true
                }
                c == '\\' && i + 1 < text.length -> {
                    current.append(text[++i])
                    inWord = true
                }
                c == '$' && text.getOrNull(i + 1) == '?' -> {
                    current.append(lastStatus)
                    inWord = true
                    i++
                }
                c.isWhitespace() -> if (inWord) {
                    words += current.toString()
                    current.clear()
                    inWord = false
                }
                else -> {
                    current.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) words += current.toString()
        return words
    }

    // ── commands ────────────────────────────────────────────────────────────────────────────────

    private fun command(device: DemoDevice, args: List<String>): Pair<String, Int> {
        val name = args.firstOrNull() ?: return "" to 0
        val rest = args.drop(1)
        return when (name) {
            "getprop" -> getprop(device, rest)
            "settings" -> settings(device, rest)
            "service" -> service(device, rest)
            "cat" -> cat(device, rest)
            "df" -> df(device, rest)
            "dumpsys" -> dumpsys(device, rest)
            "wm" -> wm(device, rest)
            "ps" -> ps()
            "pm" -> pm(rest)
            "am" -> am(rest)
            "ls" -> ls(device, rest)
            "rm" -> rm(device, rest)
            "mkdir" -> {
                rest.filterNot { it.startsWith('-') }.forEach { files(device).makeDirs(it) }
                "" to 0
            }
            "mv" -> fileOp(rest) { a, b -> files(device).move(a, b) }
            "cp" -> fileOp(rest) { a, b -> files(device).copy(a, b) }
            "touch" -> {
                rest.forEach { if (!files(device).exists(it)) files(device).write(it, ByteArray(0)) }
                "" to 0
            }
            "echo" -> rest.joinToString(" ") to 0
            "whoami" -> "shell" to 0
            "id" -> "uid=2000(shell) gid=2000(shell) groups=2000(shell),1004(input),1007(log),1011(adb),1015(sdcard_rw),3003(inet) context=u:r:shell:s0" to 0
            "date" -> DateUtils.formatDate("EEE MMM d HH:mm:ss Z yyyy") to 0
            "uptime" -> uptimeLine(device) to 0
            "ip" -> "192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.${101 + device.index}" to 0
            "logcat" -> if ("-d" in rest) DemoLog.dump(device, LOGCAT_DUMP_LINES).joinToString("\n") to 0 else "" to 0
            "reboot" -> {
                reboot(device)
                "" to 0
            }
            "exit" -> "" to (rest.firstOrNull()?.toIntOrNull() ?: 0)
            "false" -> "" to 1
            "true", "input", "svc", "cmd", "setprop", "log", "sleep", "chmod", "chown", "sync", "screencap", "monkey" -> "" to 0
            else -> "/system/bin/sh: $name: inaccessible or not found" to 127
        }
    }

    private fun getprop(device: DemoDevice, rest: List<String>): Pair<String, Int> {
        val props = properties(device)
        val key = rest.firstOrNull() ?: return props.entries.sortedBy { it.key }.joinToString("\n") { "[${it.key}]: [${it.value}]" } to 0
        return props[key].orEmpty() to 0
    }

    private fun settings(device: DemoDevice, rest: List<String>): Pair<String, Int> {
        val verb = rest.getOrNull(0)
        val key = rest.getOrNull(2)
        return when {
            verb == "get" && key == "device_name" -> (nickname(device) ?: "null") to 0
            verb == "get" -> "null" to 0
            verb == "put" && key == "device_name" -> {
                nicknames[device.serial] = rest.getOrNull(3)?.ifBlank { null }
                "" to 0
            }
            else -> "" to 0
        }
    }

    /** `service call iphonesubinfo …`, in the UTF-16 hex dump shape [DeviceDetails.parseServiceCall] reads. */
    private fun service(device: DemoDevice, rest: List<String>): Pair<String, Int> {
        if (rest.getOrNull(1) != "iphonesubinfo") return "" to 0
        if (device.isTablet) return "service: Service iphonesubinfo does not exist" to 0
        val value = when (rest.getOrNull(2)) {
            "1" -> device.imei
            "15", "12" -> device.phone
            else -> null
        }.orEmpty()
        return parcel(value) to 0
    }

    private fun parcel(digits: String): String {
        if (digits.isEmpty()) return "Result: Parcel(00000000 ffffffff   '........')"
        val lines = digits.chunked(4).mapIndexed { i, chunk ->
            val quoted = chunk.map { "$it." }.joinToString("")
            "  0x${(i * 16).toString(16).padStart(8, '0')}: 00000000 00000000 00000000 00000000 '$quoted'"
        }
        return "Result: Parcel(\n" + lines.joinToString("\n") + ")"
    }

    private fun cat(device: DemoDevice, rest: List<String>): Pair<String, Int> {
        val path = rest.firstOrNull() ?: return "" to 0
        return when (path) {
            "/proc/uptime" -> "${uptimeSec(device)}.24 ${uptimeSec(device) * 3}.65" to 0
            DeviceDetails.CUSTOM_PROP_FILE -> {
                val text = customProps[device.serial]
                    ?: device.custom.entries.joinToString("\n") { "${it.key}=${it.value}" }
                text to 0
            }
            else -> files(device).read(path).fold(
                onSuccess = { it.decodeToString() to 0 },
                onFailure = { "cat: ${it.message}" to 1 },
            )
        }
    }

    private fun write(device: DemoDevice, path: String, text: String) {
        if (path == DeviceDetails.CUSTOM_PROP_FILE) {
            customProps[device.serial] = text.trimEnd('\n')
            return
        }
        if (path == "/dev/null") return
        files(device).write(path, text.encodeToByteArray())
    }

    private fun df(device: DemoDevice, rest: List<String>): Pair<String, Int> {
        val total = device.storageTotalKb
        val used = total * device.storageUsedPercent / 100
        val header = "Filesystem       1K-blocks     Used Available Use% Mounted on"
        val data = "/dev/block/dm-48 ${total.toString().padStart(9)} ${used.toString().padStart(8)} ${(total - used).toString().padStart(9)} ${device.storageUsedPercent.toString().padStart(3)}% /data"
        if (rest.isNotEmpty()) return "$header\n$data" to 0
        return listOf(
            header,
            "/dev/block/dm-6     3145728  3132440     13288 100% /",
            "tmpfs               3891200     1924   3889276   1% /dev",
            data,
            "/dev/fuse        ${total.toString().padStart(9)} ${used.toString().padStart(8)} ${(total - used).toString().padStart(9)} ${device.storageUsedPercent.toString().padStart(3)}% /storage/emulated",
        ).joinToString("\n") to 0
    }

    private fun dumpsys(device: DemoDevice, rest: List<String>): Pair<String, Int> = when (rest.firstOrNull()) {
        "battery" -> listOf(
            "Current Battery Service state:",
            "  AC powered: false",
            "  USB powered: ${device.charging}",
            "  Wireless powered: false",
            "  Max charging current: ${if (device.charging) 3000000 else 0}",
            "  status: ${if (device.charging) 2 else 3}",
            "  health: 2",
            "  present: true",
            "  level: ${device.batteryLevel}",
            "  scale: 100",
            "  voltage: ${3700 + device.batteryLevel * 5}",
            "  temperature: ${device.temperatureTenths}",
            "  technology: Li-ion",
        ).joinToString("\n") to 0
        "power" -> "  mWakefulness=${if (device.screenOff) "Asleep" else "Awake"}" to 0
        else -> "" to 0
    }

    private fun wm(device: DemoDevice, rest: List<String>): Pair<String, Int> = when (rest.firstOrNull()) {
        "size" -> "Physical size: ${device.screenWidth}x${device.screenHeight}" to 0
        "density" -> "Physical density: ${if (device.isTablet) 320 else 420}" to 0
        else -> "" to 0
    }

    private fun ps(): Pair<String, Int> =
        ("  PID NAME\n" + DemoLog.PROCESSES.entries.joinToString("\n") { "${it.key.padStart(5)} ${it.value}" }) to 0

    private fun pm(rest: List<String>): Pair<String, Int> = when (rest.firstOrNull()) {
        "list" -> PACKAGES.joinToString("\n") { "package:$it" } to 0
        "path" -> "package:/data/app/~~demo==/${rest.getOrNull(1)}-1/base.apk" to 0
        "install" -> "Performing Streamed Install\nSuccess" to 0
        "uninstall", "clear", "grant", "revoke" -> "Success" to 0
        else -> "" to 0
    }

    private fun am(rest: List<String>): Pair<String, Int> = when (rest.firstOrNull()) {
        "start" -> "Starting: Intent { ${rest.drop(1).joinToString(" ")} }" to 0
        "broadcast" -> "Broadcasting: Intent { ${rest.drop(1).joinToString(" ")} flg=0x400000 }\nBroadcast completed: result=0" to 0
        else -> "" to 0
    }

    private fun ls(device: DemoDevice, rest: List<String>): Pair<String, Int> {
        val long = rest.any { it.startsWith('-') && 'l' in it }
        val path = rest.lastOrNull { !it.startsWith('-') } ?: "/"
        return files(device).list(path).fold(
            onSuccess = { list ->
                list.sortedBy { it.name }.joinToString("\n") { f ->
                    if (!long) {
                        f.name
                    } else {
                        val type = if (f.isDirectory) "d" else if (f.isSymlink) "l" else "-"
                        val date = DateUtils.formatDate(DateUtils.timeToDate(f.modifiedMs), "yyyy-MM-dd HH:mm")
                        "${type}rwxrwx--- 2 root sdcard_rw ${f.size.toString().padStart(9)} $date ${f.name}"
                    }
                } to 0
            },
            onFailure = { "ls: ${it.message}" to 1 },
        )
    }

    private fun rm(device: DemoDevice, rest: List<String>): Pair<String, Int> {
        val force = rest.any { it.startsWith('-') && 'f' in it }
        var status = 0
        rest.filterNot { it.startsWith('-') }.forEach { path ->
            if (!files(device).remove(path) && !force) status = 1
        }
        return (if (status != 0) "rm: No such file or directory" else "") to status
    }

    private fun fileOp(rest: List<String>, op: (String, String) -> Boolean): Pair<String, Int> {
        val paths = rest.filterNot { it.startsWith('-') }
        if (paths.size < 2) return "missing operand" to 1
        return if (op(paths[0], paths[1])) "" to 0 else "${paths[0]}: No such file or directory" to 1
    }

    /** what a pipe stage does to the text before it. */
    private fun filter(input: String, args: List<String>): String {
        val lines = input.lines().let { if (it.lastOrNull().isNullOrEmpty()) it.dropLast(1) else it }
        val rest = args.drop(1)
        return when (args.firstOrNull()) {
            "grep" -> {
                val ignoreCase = rest.any { it.startsWith('-') && 'i' in it }
                val invert = rest.any { it.startsWith('-') && 'v' in it }
                val regex = rest.any { it.startsWith('-') && 'E' in it }
                val max = rest.firstOrNull { it.startsWith("-m") }?.removePrefix("-m")?.toIntOrNull()
                val pattern = rest.lastOrNull { !it.startsWith('-') }.orEmpty()
                val re = if (regex) {
                    runCatching { Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()) }.getOrNull()
                } else {
                    null
                }
                val matches = { line: String ->
                    if (regex) re?.containsMatchIn(line) == true else line.contains(pattern, ignoreCase)
                }
                lines.filter { matches(it) != invert }.let { if (max != null) it.take(max) else it }.joinToString("\n")
            }
            "head" -> lines.take(count(rest)).joinToString("\n")
            "tail" -> lines.takeLast(count(rest)).joinToString("\n")
            "wc" -> lines.size.toString()
            "sort" -> lines.sorted().joinToString("\n")
            else -> input
        }
    }

    private fun count(args: List<String>): Int =
        args.firstNotNullOfOrNull { it.removePrefix("-n").removePrefix("-").toIntOrNull() } ?: 10

    private fun uptimeSec(device: DemoDevice): Long {
        val sinceStart = (DateUtils.nowMs() - startMs) / 1000
        val rebooted = bootedAtMs[device.serial]
        return if (rebooted != null) (DateUtils.nowMs() - rebooted) / 1000 + 1 else device.uptimeSec + sinceStart
    }

    private fun uptimeLine(device: DemoDevice): String {
        val secs = uptimeSec(device)
        val days = secs / 86_400
        val hours = (secs % 86_400) / 3_600
        val minutes = (secs % 3_600) / 60
        return " ${DateUtils.formatDate("HH:mm:ss")} up $days days, $hours:${minutes.toString().padStart(2, '0')},  0 users,  load average: 0.52, 0.61, 0.66"
    }

    private companion object {
        const val REBOOT_MS = 25_000L
        const val LOGCAT_DUMP_LINES = 40

        val PACKAGES = listOf(
            "android", "com.acme.inventory", "com.android.chrome", "com.android.phone", "com.android.settings",
            "com.android.systemui", "com.google.android.apps.messaging", "com.google.android.apps.photos",
            "com.google.android.gm", "com.google.android.gms", "com.google.android.youtube",
        )
    }
}
