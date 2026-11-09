package com.jpage4500.devicemanager.manager

/**
 * nothing to observe: the desktop is always in the foreground.
 *
 * A window that is minimised, behind another one, or gone altogether — which is what
 * `MINIMIZE_TO_TRAY` is for — is still managing 45 devices over USB hubs, and dropping the tracker
 * because nobody happened to be looking at it would break the workload the whole app exists for.
 *
 * So the flag stays true for the life of the process and `whileForeground` is a wrapper that enters
 * its block once and never cancels it. Deliberately not tied to window focus.
 */
actual fun observeAppLifecycle() = Unit
