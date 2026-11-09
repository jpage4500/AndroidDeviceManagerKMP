package com.jpage4500.devicemanager.manager

import android.app.*
import android.os.*
import com.jpage4500.devicemanager.util.*

private val log = LoggerConfig.createLogger("AppLifecycle")

/**
 * how many activities are started, which is what "in the foreground" means on android.
 *
 * `registerActivityLifecycleCallbacks` rather than `ProcessLifecycleOwner`: that would be the
 * canonical answer and it needs `androidx.lifecycle:lifecycle-process`, a whole artifact for a
 * callback this app registers in ten lines against a framework class it already has. `onStop` is the
 * one that matters — it is what fires when the screen goes off, which is the case this was written
 * for, and it fires whether the user locked the phone or walked away from it.
 *
 * Counted rather than a flag, so a second activity closing can't background an app that still has
 * one open. There is deliberately no debounce: `MainActivity` declares its `configChanges`, so a
 * rotation doesn't recreate it, and a spurious flip would cost one tracker restart — which is what
 * [DeviceManager.refresh] does on purpose anyway.
 */
actual fun observeAppLifecycle() {
    // the application context, which is what AndroidContextProvider holds
    val app = AndroidContextProvider.context as? Application
    if (app == null) {
        log.w("observeAppLifecycle: no Application; connections will never be paused")
        return
    }
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started++
            if (started == 1) AppLifecycle.onForeground()
        }

        override fun onActivityStopped(activity: Activity) {
            started--
            if (started <= 0) AppLifecycle.onBackground()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    })
}
