package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.IS_RELEASE
import java.awt.Desktop
import java.io.File
import java.net.URI

private val log = LoggerConfig.createLogger("PlatformUtils")

actual object PlatformUtils {
    actual fun getPlatform(): PlatformType = PlatformType.DESKTOP

    actual fun getPlatformName(): String = when (DesktopUtils.getDesktopOS()) {
        DesktopOS.MAC -> "macOS"
        DesktopOS.WINDOWS -> "Windows"
        DesktopOS.LINUX -> "Linux"
    }

    actual fun getPlatformVersion(): String = System.getProperty("os.version").orEmpty()

    // there is no "model" for a desktop; the CPU arch is the closest useful thing, and it's what
    // tells an arm64 mac apart from an x64 one in a log
    actual fun getDeviceModel(): String = System.getProperty("os.arch").orEmpty()

    actual fun getCoreCount(): Int = Runtime.getRuntime().availableProcessors()

    actual fun getMemoryInfo(): MemoryInfo = jvmMemoryInfo()

    actual fun isTablet(): Boolean = false

    // a desktop build ships through no store at all, so "release" means the jDeploy fat jar
    actual fun isDebugBuild(): Boolean = !IS_RELEASE

    actual fun openUrl(url: String): Boolean {
        return try {
            if (!Desktop.isDesktopSupported()) return false
            val desktop = Desktop.getDesktop()
            // a local path goes to the file viewer; anything else to the browser
            val file = File(url)
            if (file.exists()) {
                desktop.open(if (file.isDirectory) file else file.parentFile)
            } else {
                desktop.browse(URI(url))
            }
            true
        } catch (e: Exception) {
            log.e("openUrl: $url: ${e.message}")
            false
        }
    }
}
