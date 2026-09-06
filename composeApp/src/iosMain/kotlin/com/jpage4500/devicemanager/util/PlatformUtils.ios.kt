package com.jpage4500.devicemanager.util

import kotlin.experimental.ExperimentalNativeApi
import platform.Foundation.NSProcessInfo
import platform.UIKit.*

private val log = LoggerConfig.createLogger("PlatformUtils")

actual object PlatformUtils {
    actual fun getPlatform(): PlatformType = PlatformType.IOS

    actual fun getPlatformName(): String = UIDevice.currentDevice.systemName

    actual fun getPlatformVersion(): String = UIDevice.currentDevice.systemVersion

    actual fun getDeviceModel(): String = UIDevice.currentDevice.model

    actual fun getCoreCount(): Int = NSProcessInfo.processInfo.processorCount.toInt()

    /**
     * physical RAM as the total, and nothing as the free figure.
     *
     * iOS gives an app no supported way to ask how much it may still allocate — the number that
     * exists is the whole device's, and a jetsam limit is what actually stops us. reporting 0 free
     * is the honest answer rather than a figure that means something different here than it does on
     * the other two platforms.
     */
    actual fun getMemoryInfo(): MemoryInfo =
        MemoryInfo(freeBytes = 0L, totalBytes = NSProcessInfo.processInfo.physicalMemory.toLong())

    actual fun isTablet(): Boolean =
        UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad

    // Kotlin/Native sets this for a debug binary; an App Store or TestFlight build is a release one
    @OptIn(ExperimentalNativeApi::class)
    actual fun isDebugBuild(): Boolean = Platform.isDebugBinary

    actual fun openUrl(url: String): Boolean {
        return try {
            val nsUrl = platform.Foundation.NSURL.URLWithString(url) ?: return false
            UIApplication.sharedApplication.openURL(nsUrl, emptyMap<Any?, Any>(), null)
            true
        } catch (e: Exception) {
            log.e("openUrl: $url: ${e.message}")
            false
        }
    }
}
