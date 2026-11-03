package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*

private val log = LoggerConfig.createLogger("MirrorLauncher")

/**
 * the Mirror action: the built-in mirror window, or scrcpy — whichever Settings › scrcpy says.
 *
 * scrcpy needs adb to the device, so only a device read from an adb server can use it; one read
 * from a remote Device Manager server always gets the built-in mirror. The command line is the
 * Swing app's: `scrcpy -s <serial> --window-title <name> <flags…>`. See docs/SETTINGS.md.
 */
object MirrorLauncher {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    fun mirror(deviceIds: List<String>) {
        val (scrcpy, builtIn) = deviceIds.partition { usesScrcpy(it) }
        builtIn.forEach { WindowManager.openMirror(it) }
        if (scrcpy.isEmpty()) return

        // asked first when Settings says to, and always when there's no scrcpy to run — the prompt
        // is where its path is set
        val path = ScrcpyHost.resolve(SettingsManager.getString(AppPref.SCRCPY_PATH))
        if (path == null || SettingsManager.getBoolean(AppPref.SCRCPY_ASK)) {
            PageManager.openScrcpyPrompt(scrcpy)
        } else {
            launch(scrcpy)
        }
    }

    fun mirror(deviceId: String) = mirror(listOf(deviceId))

    /** run scrcpy for each of [deviceIds] with the flags Settings holds — the prompt's Mirror. */
    fun launch(deviceIds: List<String>) {
        val path = ScrcpyHost.resolve(SettingsManager.getString(AppPref.SCRCPY_PATH))
        if (path == null) {
            ToastManager.showToast("scrcpy isn't installed", success = false)
            return
        }
        scope.launch {
            deviceIds.forEach { deviceId ->
                val server = serverOf(deviceId) ?: return@forEach
                val name = DeviceManager.nameOf(deviceId)
                val args = listOf("-s", Device.serialOf(deviceId), "--window-title", name) + flags()
                log.d("launch: $name, $path ${args.joinToString(" ")}")
                val error = ScrcpyHost.launch(path, args, server)
                if (error != null) ToastManager.showToast("$name: $error", success = false)
            }
        }
    }

    /** the flags Settings › scrcpy holds, in the Swing app's order. */
    fun flags(): List<String> = buildList {
        if (SettingsManager.getBoolean(AppPref.SCRCPY_SHOW_TOUCHES)) add("--show-touches")
        if (SettingsManager.getBoolean(AppPref.SCRCPY_STAY_AWAKE)) add("--stay-awake")
        if (SettingsManager.getBoolean(AppPref.SCRCPY_NO_AUDIO)) add("--no-audio")
        SettingsManager.getString(AppPref.SCRCPY_VIDEO_CODEC)?.takeIf { it.isNotBlank() }?.let { add("--video-codec=$it") }
        SettingsManager.getString(AppPref.SCRCPY_EXTRA_ARGS)
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotEmpty() }
            ?.let { addAll(it) }
    }

    private fun usesScrcpy(deviceId: String): Boolean =
        ScrcpyHost.supported &&
            SettingsManager.getEnum<MirrorWith>(AppPref.MIRROR_WITH) == MirrorWith.SCRCPY &&
            serverOf(deviceId) != null

    private fun serverOf(deviceId: String): AdbServer? =
        (DeviceManager.connectionFor(deviceId) as? AdbServerTransport)?.server
}
