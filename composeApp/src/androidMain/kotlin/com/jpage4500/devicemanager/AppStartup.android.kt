package com.jpage4500.devicemanager

import android.content.Context
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.util.*

/**
 * everything that has to happen before the first composable, in the order it has to happen in.
 *
 * lives here rather than in `androidApp` so the shell stays a shell — and so the log writer's type
 * never has to appear on the shell's classpath.
 */
fun initApp(context: Context) {
    // the context first: settings, file storage and the log writer all read it
    AndroidContextProvider.init(context)
    LoggerConfig.initialize(AndroidLogWriter())
    installCrashHandler()
    // before the first composable, so nothing has started polling by the time the callbacks are
    // registered — see AppLifecycle
    observeAppLifecycle()
    LogManager.onAppStart()
}
