package com.jpage4500.devicemanager.util

/**
 * arm the platform's uncaught-exception hook so
 * [com.jpage4500.devicemanager.manager.LogManager.recordCrash] runs before the process dies.
 *
 * safe to call once per process; a second call re-arms the hook, which is cheap and idempotent in
 * effect.
 */
expect fun installCrashHandler()
