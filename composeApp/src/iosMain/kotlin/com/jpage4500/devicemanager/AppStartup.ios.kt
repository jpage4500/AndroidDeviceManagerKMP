package com.jpage4500.devicemanager

import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.util.*

private var started = false

/**
 * everything that has to happen before the first composable, in the order it has to happen in.
 *
 * guarded rather than idempotent by construction: `MainViewController()` is what Xcode calls, and
 * it is called again whenever the root view controller is rebuilt.
 */
fun initApp() {
    if (started) return
    started = true
    LoggerConfig.initialize(IosLogWriter())
    installCrashHandler()
    // before the first composable, so nothing has started polling by the time the callbacks are
    // registered — see AppLifecycle
    observeAppLifecycle()
    LogManager.onAppStart()
}
