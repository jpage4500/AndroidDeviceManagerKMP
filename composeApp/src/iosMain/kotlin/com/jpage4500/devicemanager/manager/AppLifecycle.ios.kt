package com.jpage4500.devicemanager.manager

import platform.Foundation.*
import platform.UIKit.*

/**
 * `UIApplication`'s own notifications.
 *
 * **`DidEnterBackground` / `WillEnterForeground`, not the resign-active pair.** Resigning active is
 * also what pulling down Control Centre does, and what the app switcher does while someone is only
 * glancing at it — tearing every connection down for that and dialling the desk again a second
 * later is worse than leaving them up. Entering the background is the one that means it.
 *
 * A suspended iOS app's sockets are gone regardless of any of this, which is why the reconnect path
 * had to exist before the pausing did — see docs/MOBILE-UI.md.
 *
 * The observers are never removed: they live as long as the process does, which is as long as the
 * app. `queue = null` delivers the block on whichever thread posted the notification, and
 * `UIApplication` posts both of these on the main one.
 */
actual fun observeAppLifecycle() {
    val center = NSNotificationCenter.defaultCenter
    center.addObserverForName(
        name = UIApplicationDidEnterBackgroundNotification,
        `object` = null,
        queue = null,
        usingBlock = { AppLifecycle.onBackground() },
    )
    center.addObserverForName(
        name = UIApplicationWillEnterForegroundNotification,
        `object` = null,
        queue = null,
        usingBlock = { AppLifecycle.onForeground() },
    )
}
