package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.*
import com.jpage4500.devicemanager.util.*

/**
 * accept files dragged in from the OS.
 *
 * a no-op on mobile, where there is no desktop to drag from. On desktop [onDrop] is called with
 * whatever was let go over this area — an APK to install, anything else to copy.
 *
 * @param enabled false while there is nothing to drop onto, so the ring doesn't appear
 */
@Composable
expect fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragChanged: (Boolean) -> Unit,
    onDrop: (List<LocalFile>) -> Unit,
): Modifier
