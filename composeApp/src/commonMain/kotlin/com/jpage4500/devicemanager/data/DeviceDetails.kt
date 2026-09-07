package com.jpage4500.devicemanager.data

import com.jpage4500.devicemanager.util.*

/**
 * the shell commands that fill in a device row, and the parsers for what they print.
 *
 * ported from the Swing app's `DeviceManager.fetchDeviceDetails`. Pure functions on purpose: the
 * running of them belongs to [com.jpage4500.devicemanager.manager.DeviceManager], which reaches
 * every device through whichever transport is set, and the parsing is the part worth keeping
 * identical to the app this replaces.
 */
object DeviceDetails {
    /** the name someone typed into Settings > About > Device name. */
    const val CMD_NICKNAME = "settings get global device_name"

    /**
     * there is no consistent way to read a phone number over adb.
     *
     * these two `iphonesubinfo` transaction codes are the best available, and which one works
     * differs by Android version — 15 first, then 12. From
     * <https://github.com/micro5k/microg-unofficial-installer>, via the Swing app.
     */
    const val CMD_PHONE_1 = "service call iphonesubinfo 15 s16 com.android.shell"
    const val CMD_PHONE_2 = "service call iphonesubinfo 12 s16 com.android.shell"
    const val CMD_IMEI = "service call iphonesubinfo 1 s16 com.android.shell"

    const val CMD_DISK = "df /data"
    const val CMD_BATTERY = "dumpsys battery"

    /** counts deep sleep, so this is time since reboot rather than time awake. */
    const val CMD_UPTIME = "cat /proc/uptime"

    /** "1" once the device has finished booting; anything else means it is still coming up. */
    const val CMD_BOOTED = "getprop sys.boot_completed"

    /**
     * where the Swing app keeps the labels a person typed — on the device, not on this machine, so
     * they follow it between desks. Same path, so the two apps read each other's.
     */
    const val CUSTOM_PROP_FILE = "/sdcard/android_device_manager.properties"
    const val CMD_CUSTOM_PROPS = "cat $CUSTOM_PROP_FILE 2>/dev/null"

    /** `settings put global device_name "..."` — what the NAME column edits. */
    fun setNicknameCommand(name: String): String =
        "settings put global device_name " + Utils.shellQuote(name)

    /** rewrite the whole properties file; there is no way to edit one line of it over adb. */
    fun writeCustomPropsCommand(props: Map<String, String>): String {
        val body = props.entries.joinToString("\n") { (key, value) -> "$key=$value" }
        return "echo " + Utils.shellQuote(body) + " > $CUSTOM_PROP_FILE"
    }

    /**
     * printed between the commands of a batch, so one round trip's output can be split back up.
     *
     * unlikely enough not to appear in a `getprop` value, and cheap to scan for.
     */
    private const val MARKER = "__ADM_NEXT__"

    /**
     * the commands read **once** per device, as a single shell line.
     *
     * batched because a socket per command is what made the table fill in slowly: every `exec:`
     * opens a new connection to the adb server, so five commands across 45 devices was 225 round
     * trips. As one line it is 45.
     */
    val FIXED_BATCH =
        batch(listOf("getprop", CMD_NICKNAME, CMD_PHONE_1, CMD_PHONE_2, CMD_IMEI, CMD_CUSTOM_PROPS))
    const val FIXED_COUNT = 6

    /** and the two that go stale, likewise in one round trip. */
    val CHANGING_BATCH = batch(listOf(CMD_DISK, CMD_BATTERY, CMD_UPTIME, CMD_BOOTED))
    const val CHANGING_COUNT = 4

    // `;` rather than `&&`: a device with no iphonesubinfo service must not stop the rest
    private fun batch(commands: List<String>): String =
        commands.joinToString(" ; echo $MARKER ; ")

    /** split one batch's output back into a section per command, in the order they were sent. */
    fun splitBatch(output: String?, count: Int): List<String> {
        val parts = MutableList(count) { StringBuilder() }
        var index = 0
        output?.lineSequence()?.forEach { line ->
            if (line.trim() == MARKER) {
                index++
                return@forEach
            }
            if (index < count) parts[index].append(line).append('\n')
        }
        return parts.map { it.toString() }
    }

    /** a phone number shorter than this is a failed read, not a short number. */
    private const val MIN_PHONE_LENGTH = 7

    /**
     * the string a `service call` hid inside a Parcel dump.
     *
     * ```
     * Result: Parcel(
     *   0x00000000: 00000000 0000000b 00350031 00300034 '........1.2.2.2.'
     *   0x00000010: 00310039 00390034 00310032 00000034 '3.3.3.4.4.4.4...')
     * ```
     *
     * the value is the UTF-16 text in the quoted column, one character per two bytes — so every
     * other character is a NUL that prints as `.`. Taking the digits out of each quoted run and
     * joining them is what reassembles it.
     *
     * @return null when the service isn't there, or printed nothing usable
     */
    fun parseServiceCall(output: String?): String? {
        if (output == null) return null
        // "service: Service iphonesubinfo does not exist" — a tablet or a TV, not a failure
        if (output.contains("does not exist", ignoreCase = true)) return null

        val text = buildString {
            for (line in output.lineSequence()) {
                val start = line.indexOf('\'')
                if (start < 0) continue
                val end = line.indexOf('\'', start + 1)
                if (end < 0) continue
                // the quoted run is padded with '.' for every NUL byte; only digits survive
                append(line.substring(start + 1, end).filter { it.isDigit() || it == '-' })
            }
        }
        return text.ifEmpty { null }
    }

    fun parsePhone(output: String?): String? =
        parseServiceCall(output)?.takeIf { it.length > MIN_PHONE_LENGTH }

    fun parseImei(output: String?): String? = parseServiceCall(output)

    /**
     * the name someone gave the device, or null.
     *
     * `settings get` prints `null` for an unset key, and on a device with no settings service it
     * prints an error instead of failing — both of which have to be read as "no nickname".
     */
    fun parseNickname(output: String?): String? {
        val name = output?.trim()?.lineSequence()?.firstOrNull()?.trim() ?: return null
        if (name.isEmpty() || name == "null") return null
        if (name.contains("Can't find service", ignoreCase = true)) return null
        return name
    }

    /** free and total bytes on `/data`, from `df`. */
    data class DiskSpace(val freeBytes: Long?, val totalBytes: Long?)

    /**
     * ```
     * Filesystem            1K-blocks    Used Available Use% Mounted on
     * /dev/block/mmcblk0p15  27545632 4090224  23455408  15% /data
     * ```
     *
     * the last line is the one that matters — a busybox `df` can print a wrapped header first. The
     * columns are 1K blocks, so both are multiplied up. Total is allowed to stay null: it is only
     * used to show free space as a fraction, and a device whose `df` prints something else still
     * gives a usable free figure.
     */
    fun parseDiskSpace(output: String?): DiskSpace {
        val line = output?.lineSequence()
            ?.map { it.trim() }
            ?.lastOrNull { it.isNotEmpty() }
            ?: return DiskSpace(null, null)
        val columns = line.split(Regex("\\s+"))
        return DiskSpace(
            freeBytes = columns.getOrNull(3).toBlockBytes(),
            totalBytes = columns.getOrNull(1).toBlockBytes(),
        )
    }

    private fun String?.toBlockBytes(): Long? = this?.toLongOrNull()?.times(1024L)

    /**
     * `key=value` per line, as the Swing app writes it.
     *
     * old versions of that app replaced spaces with `~`, so those are turned back.
     */
    fun parseCustomProps(output: String?): Map<String, String> {
        if (output.isNullOrBlank()) return emptyMap()
        return output.lineSequence()
            .mapNotNull { line ->
                val key = line.substringBefore('=', "").trim()
                if (key.isEmpty() || !line.contains('=')) return@mapNotNull null
                key to line.substringAfter('=').replace('~', ' ')
            }
            .toMap()
    }

    /**
     * the host clock time the device booted, from `/proc/uptime`.
     *
     * `"5817.24 21456.65"` — seconds since boot, then idle seconds. Stored as a timestamp rather
     * than a duration so the column doesn't have to be recomputed to stay right.
     */
    fun parseBootTime(output: String?, nowMs: Long): Long? {
        val seconds = output?.trim()?.substringBefore(' ')?.toDoubleOrNull() ?: return null
        if (seconds <= 0) return null
        return nowMs - (seconds * 1000).toLong()
    }

    /** what `dumpsys battery` says right now. */
    data class BatteryInfo(
        val level: Int? = null,
        val powerStatus: PowerStatus = PowerStatus.NONE,
        val temperatureC: Float? = null,
        val voltageMv: Int? = null,
    )

    /**
     * ```
     * Current Battery Service state:
     *   AC powered: false
     *   USB powered: true
     *   level: 72
     *   temperature: 251
     *   voltage: 4136
     * ```
     *
     * NOTE: Samsung devices also dump log buffers into this output, whose lines are prefixed with a
     * timestamp (`08-04 14:03:54.308  Sending ACTION_BATTERY_CHANGED: level:82, ..`). Those can't be
     * mistaken for state lines, because the timestamp ends up part of the name and then matches
     * nothing here.
     */
    fun parseBattery(output: String?): BatteryInfo {
        if (output == null) return BatteryInfo()
        var level: Int? = null
        var temperatureC: Float? = null
        var voltageMv: Int? = null
        var status = PowerStatus.NONE

        for (raw in output.lineSequence()) {
            val line = raw.trim()
            val separator = line.indexOf(": ")
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 2).trim()
            when (name) {
                "level" -> level = value.toIntOrNull()
                // reported in tenths of a degree celsius
                "temperature" -> temperatureC = value.toIntOrNull()?.let { it / 10f }
                "voltage" -> voltageMv = value.toIntOrNull()
                "AC powered" -> if (value.toBoolean()) status = PowerStatus.AC
                "USB powered" -> if (value.toBoolean()) status = PowerStatus.USB
                "Wireless powered" -> if (value.toBoolean()) status = PowerStatus.WIRELESS
                "Dock powered" -> if (value.toBoolean()) status = PowerStatus.DOCK
            }
        }
        return BatteryInfo(
            // some Android TV boxes report 0; that's "no battery", not a flat one
            level = level?.takeIf { it in 1..100 },
            powerStatus = status,
            temperatureC = temperatureC,
            voltageMv = voltageMv,
        )
    }
}
