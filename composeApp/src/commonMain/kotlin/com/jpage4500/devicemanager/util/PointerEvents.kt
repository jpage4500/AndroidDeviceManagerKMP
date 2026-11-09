package com.jpage4500.devicemanager.util

import androidx.compose.ui.input.pointer.*

/**
 * true when this event is the user actually putting a button down **in this window**.
 *
 * Compose hands out presses nobody made: a window that first hears about the pointer while a button
 * is already held gets a `Press` of Compose's own invention — `SyntheticEventSender.sendMissingPresses`
 * sees a pressed pointer it has no press for and makes one up, because the real one went to whichever
 * window the pointer went down in. dragging a mirror window across the device list does exactly that,
 * and every hand-rolled gesture underneath reads the invented press as a click.
 *
 * only the platform can tell the two apart — an invented press carries no native event. ported from
 * FileExplorerKMP, where a rubber-band select over every row a dragged panel passed is what paid
 * for it.
 */
expect val PointerEvent.isRealPress: Boolean

/**
 * the pointer going down in this window, for a gesture that handles its own press.
 *
 * `awaitFirstDown` can't be used for this: it takes any change that turned pressed, which includes
 * the presses Compose invents — see [isRealPress].
 */
suspend fun AwaitPointerEventScope.awaitRealPress(): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        if (!event.isRealPress) continue
        event.changes.firstOrNull { it.pressed }?.let { return it }
    }
}
