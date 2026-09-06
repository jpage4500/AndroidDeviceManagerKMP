package com.jpage4500.devicemanager.util

import android.content.*
import android.content.pm.*
import android.content.res.*
import android.net.Uri
import android.os.Build

private val log = LoggerConfig.createLogger("PlatformUtils")

actual object PlatformUtils {
    actual fun getPlatform(): PlatformType = PlatformType.ANDROID

    actual fun getPlatformName(): String = "Android"

    actual fun getPlatformVersion(): String = Build.VERSION.RELEASE ?: "${Build.VERSION.SDK_INT}"

    actual fun getDeviceModel(): String = Build.MODEL ?: Build.DEVICE ?: "?"

    actual fun getCoreCount(): Int = Runtime.getRuntime().availableProcessors()

    actual fun getMemoryInfo(): MemoryInfo = jvmMemoryInfo()

    actual fun isTablet(): Boolean {
        val size = AndroidContextProvider.context.resources.configuration.screenLayout and
            Configuration.SCREENLAYOUT_SIZE_MASK
        return size >= Configuration.SCREENLAYOUT_SIZE_LARGE
    }

    actual fun isDebugBuild(): Boolean {
        val flags = AndroidContextProvider.context.applicationInfo.flags
        return (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    actual fun openUrl(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            AndroidContextProvider.context.startActivity(intent)
            true
        } catch (e: Exception) {
            log.e("openUrl: $url: ${e.message}")
            false
        }
    }
}
