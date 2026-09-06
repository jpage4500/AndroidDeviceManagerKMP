package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.manager.*
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
actual fun installCrashHandler() {
    setUnhandledExceptionHook { throwable -> LogManager.recordCrash(throwable, "ios") }
}
