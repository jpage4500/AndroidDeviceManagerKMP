package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.data.*
import java.io.File

private val log = LoggerConfig.createLogger("DesktopUtils")

/** the desktop's own corners: where its files live, and which OS it is. */
object DesktopUtils {
    // where settings and logs are saved — per-brand, see Branding.desktopHomeDir
    val DESKTOP_HOME = Branding.desktopHomeDir

    fun getHomeDir(child: String? = null): File {
        val home = File(System.getProperty("user.home"), DESKTOP_HOME)
        if (!home.exists()) home.mkdirs()
        return if (child == null) home else File(home.absolutePath, child)
    }

    fun getDesktopOS(): DesktopOS {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            osName.contains("mac") -> DesktopOS.MAC
            osName.contains("win") -> DesktopOS.WINDOWS
            else -> DesktopOS.LINUX
        }
    }

    fun isDesktopOS(vararg platformArr: DesktopOS): Boolean = platformArr.contains(getDesktopOS())

    /** the log file itself, for handing to the platform's viewer. */
    fun getLogFile(): File? = try {
        File(getHomeDir(PATH_LOGS), FILE_LOG)
    } catch (e: Exception) {
        log.e("getLogFile: Exception: ${e.message}")
        null
    }
}
