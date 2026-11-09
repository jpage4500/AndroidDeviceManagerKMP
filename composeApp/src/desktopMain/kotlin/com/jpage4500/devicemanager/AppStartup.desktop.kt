package com.jpage4500.devicemanager

import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.util.*

/**
 * everything that has to happen before the first window, in the order it has to happen in.
 *
 * logging first: every `private val log` in the app is built the moment its file is touched, which
 * for `main.kt` is before anything has run. they all reach whatever writer is installed here —
 * see [LoggerConfig].
 */
fun initApp() {
    LoggerConfig.initialize(DesktopLogWriter())
    installCrashHandler()
    // before the first composable, so nothing has started polling by the time the callbacks are
    // registered — see AppLifecycle
    observeAppLifecycle()
    LogManager.onAppStart()
    UpdateManager.startBackgroundChecks()
    // a server left running is started again, so a phone that scanned it once keeps working
    ShareServer.autoStart()
}
