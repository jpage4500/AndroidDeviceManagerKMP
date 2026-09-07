package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draganddrop.*
import com.jpage4500.devicemanager.util.*
import java.awt.datatransfer.DataFlavor
import java.io.File

private val log = LoggerConfig.createLogger("FileDropTarget")

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragChanged: (Boolean) -> Unit,
    onDrop: (List<LocalFile>) -> Unit,
): Modifier {
    // read inside the callbacks without re-keying the modifier: `enabled` flips as the selection
    // changes, and re-keying mid-drag would cancel it
    val enabledNow by rememberUpdatedState(enabled)
    val onDropNow by rememberUpdatedState(onDrop)
    val onDragNow by rememberUpdatedState(onDragChanged)

    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = onDragNow(true)
            override fun onExited(event: DragAndDropEvent) = onDragNow(false)
            override fun onEnded(event: DragAndDropEvent) = onDragNow(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                onDragNow(false)
                if (!enabledNow) return false
                val files = event.filesOrEmpty()
                if (files.isEmpty()) return false
                log.d("onDrop: ${files.size} file(s)")
                onDropNow(files)
                return true
            }
        }
    }

    return dragAndDropTarget(
        // only a file drag is ours; text dragged from another app has to fall through
        shouldStartDragAndDrop = { event -> enabledNow && event.hasFiles() },
        target = target,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.hasFiles(): Boolean =
    runCatching { awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false)

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("UNCHECKED_CAST")
private fun DragAndDropEvent.filesOrEmpty(): List<LocalFile> = runCatching {
    val transferable = awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
        .filter { it.isFile }
        .map { LocalFile(it.absolutePath, it.name, it.length()) }
}.getOrDefault(emptyList())
