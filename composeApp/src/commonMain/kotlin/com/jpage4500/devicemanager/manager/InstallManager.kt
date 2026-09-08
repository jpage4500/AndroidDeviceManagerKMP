package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.util.*

private val log = LoggerConfig.createLogger("InstallManager")

/**
 * put a local file onto devices — an APK to install, anything else to copy.
 *
 * `pm install` can only read a path **on the device**, so an APK is pushed to a scratch folder first
 * and removed afterwards. That is what `adb install` does too; it just hides it.
 *
 * Several devices at once is the point — it is how a bench of 45 gets the same build. The ids are
 * [com.jpage4500.devicemanager.data.Device.id]s, so one list can span two desks.
 */
object InstallManager {
    /** somewhere every Android version lets the shell user write and `pm` read. */
    private const val SCRATCH = "/data/local/tmp"

    /** where a plain file lands. */
    private const val DROP = "/sdcard/Download"

    /**
     * install [file] on each of [serials].
     *
     * `-r` reinstalls over an existing copy keeping its data, which is what makes this useful for
     * iterating on a build. `-d` allows a downgrade, for the same reason.
     */
    suspend fun installApk(deviceIds: List<String>, file: LocalFile): Int {
        val bytes = readLocalFile(file.path)
        if (bytes == null) {
            StatusManager.post("Couldn't read ${file.name}", isError = true)
            return 0
        }
        var installed = 0
        deviceIds.forEachIndexed { index, deviceId ->
            val name = DeviceManager.nameOf(deviceId)
            StatusManager.post("Installing ${file.name} on $name", progress = percent(index, deviceIds.size))
            val remote = "$SCRATCH/${file.name}"
            val pushed = DeviceManager.pushFile(deviceId, bytes, remote)
            if (pushed.isFailure) {
                StatusManager.post("$name: ${pushed.exceptionOrNull()?.message}", isError = true)
                return@forEachIndexed
            }
            val output = DeviceManager.runCommand(
                deviceId = deviceId,
                command = "pm install -r -d ${Utils.shellQuote(remote)}",
                label = "Installing on",
            ).getOrNull().orEmpty()
            // pm prints "Success" or "Failure [REASON]"; the exit code isn't carried by exec:
            if (output.contains("Success", ignoreCase = true)) {
                installed++
            } else {
                log.w("installApk: $deviceId: ${output.trim().take(200)}")
                StatusManager.post("$name: ${output.trim().lineSequence().lastOrNull()}", isError = true)
            }
            DeviceManager.runCommand(deviceId, "rm -f ${Utils.shellQuote(remote)}", "Cleaning up on")
        }
        StatusManager.post("Installed ${file.name} on $installed of ${deviceIds.size}", progress = 100)
        return installed
    }

    /** copy [file] to each device's Download folder. */
    suspend fun copyFile(deviceIds: List<String>, file: LocalFile): Int {
        val bytes = readLocalFile(file.path) ?: return 0
        var copied = 0
        deviceIds.forEachIndexed { index, deviceId ->
            StatusManager.post(
                "Copying ${file.name} to ${DeviceManager.nameOf(deviceId)}",
                progress = percent(index, deviceIds.size),
            )
            if (DeviceManager.pushFile(deviceId, bytes, "$DROP/${file.name}").isSuccess) copied++
        }
        StatusManager.post("Copied ${file.name} to $copied of ${deviceIds.size}", progress = 100)
        return copied
    }

    /** an APK installs; anything else is copied. */
    suspend fun send(deviceIds: List<String>, files: List<LocalFile>) {
        files.forEach { file ->
            if (file.name.endsWith(".apk", ignoreCase = true)) installApk(deviceIds, file)
            else copyFile(deviceIds, file)
        }
    }

    private fun percent(index: Int, total: Int): Int =
        if (total <= 0) 0 else (index * 100) / total
}
