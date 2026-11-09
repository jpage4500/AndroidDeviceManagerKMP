package com.jpage4500.devicemanager.ui.dialog

import androidx.compose.runtime.*

/**
 * every dialog currently open, innermost last.
 *
 * a dialog opened from another one is a step deeper into one flow, not a second thing on screen —
 * so only the last one draws. the levels behind it stay composed, keeping their state and their
 * scroll position, and put a window back on screen when they're on top again.
 *
 * tokens rather than anything meaningful: all this needs to answer is "am I the innermost one".
 * ported from DashboardWeatherKMP.
 */
private val dialogStack = mutableStateListOf<Any>()

/**
 * true while the calling dialog is the innermost one open. call it unconditionally — the
 * registration is what keeps the stack honest — and skip drawing the platform Dialog when false.
 */
@Composable
fun isTopDialog(): Boolean {
    val token = remember { Any() }
    // registered from an effect rather than during composition: onDispose is the only reliable way
    // back off the stack, and a composition that never commits then adds nothing, rather than
    // wedging the list with a token no one will ever remove
    DisposableEffect(token) {
        dialogStack.add(token)
        onDispose { dialogStack.remove(token) }
    }
    return dialogStack.lastOrNull() === token
}
