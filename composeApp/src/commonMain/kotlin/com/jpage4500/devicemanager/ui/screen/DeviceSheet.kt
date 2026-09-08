package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * one device, over the list.
 *
 * Tapping a row opens *this* rather than a mirror: someone tapping a device usually wants to see
 * what it is, and going straight to a four-frames-a-second screencap poll is an expensive guess.
 * See docs/MOBILE-UI.md.
 *
 * The body is [DeviceInfoScreen], the same composable the desktop's Device Details window draws — it
 * reads the live device, so the battery and the free space move while the sheet is open. What is
 * added on a phone is the row of actions above it, which is the desktop's right-click menu filtered
 * to what a finger can reach for.
 */
@Composable
fun DeviceSheet(serial: String, onDismiss: () -> Unit, onMirror: (String) -> Unit) {
    val devices by DeviceManager.devices.collectAsState()
    val transport by DeviceManager.transport.collectAsState()
    val device = devices.firstOrNull { it.serial == serial }
    val scope = rememberCoroutineScope()
    val copyToClipboard = rememberClipboardCopy()

    var shot by remember(serial) { mutableStateOf<ByteArray?>(null) }
    var busy by remember(serial) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var rename by remember { mutableStateOf<InputRequest?>(null) }

    val restartTitle = stringResource(Res.string.sheet_restart_title)
    val renameTitle = stringResource(Res.string.sheet_rename_title)
    val shotFailed = stringResource(Res.string.sheet_screenshot_failed)

    DialogContainer(
        title = device?.displayName ?: serial,
        onDismiss = onDismiss,
        onEnter = null,
    ) {
        Column(Modifier.fillMaxSize().background(Colors.menuBg).safeDrawingPadding()) {
            Header(
                title = device?.displayName ?: serial,
                // where this device came from as well as what it is: with more than one connection
                // in play, the serial alone doesn't say which desk it belongs to
                subtitle = listOf(serial, transport.label).joinToString("  ·  "),
                onDismiss = onDismiss,
            )
            HorizontalDivider(color = Colors.divider)

            if (shot != null) {
                Screenshot(
                    bytes = shot,
                    modifier = Modifier.weight(1f),
                    onDone = { shot = null },
                )
            } else {
                ActionGrid(
                    enabled = device?.isOnline == true && !busy,
                    onMirror = { onMirror(serial) },
                    onScreenshot = {
                        busy = true
                        scope.launch {
                            DeviceManager.screenshot(serial)
                                .onSuccess { shot = it }
                                .onFailure { ToastManager.showToast(shotFailed, success = false) }
                            busy = false
                        }
                    },
                )
                HorizontalDivider(color = Colors.divider)
                DeviceInfoScreen(serial, Modifier.weight(1f))
                HorizontalDivider(color = Colors.divider)
                Footer(
                    device = device,
                    enabled = !busy,
                    onCopy = { copyToClipboard(serial) },
                    onRename = {
                        rename = InputRequest(
                            title = renameTitle,
                            initialValue = device?.nickname.orEmpty(),
                            onConfirm = { name -> scope.launch { DeviceManager.setNickname(serial, name) } },
                        )
                    },
                    onRestart = {
                        val run = { scope.launch { DeviceManager.reboot(serial) }; Unit }
                        if (SettingsManager.getBoolean(AppPref.CONFIRM_DESTRUCTIVE)) {
                            scope.launch {
                                confirm = ConfirmRequest(
                                    title = restartTitle,
                                    message = getString(
                                        Res.string.sheet_restart_msg,
                                        device?.displayName ?: serial,
                                    ),
                                    onConfirm = run,
                                )
                            }
                        } else {
                            run()
                        }
                    },
                    onDisconnect = {
                        scope.launch { DeviceManager.disconnect(serial) }
                        onDismiss()
                    },
                )
            }
        }
    }

    // siblings rather than children: each is its own dialog window, and a confirmation nested inside
    // the sheet's own Column would be clipped to it
    ConfirmDialog(confirm) { confirm = null }
    InputDialog(rename) { rename = null }
}

@Composable
private fun Header(title: String, subtitle: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchRowHeight)
            .padding(horizontal = Dimens.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = Colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss) {
            AppIcon(Res.drawable.close, size = Dimens.icon, tint = Colors.secondaryText)
        }
    }
}

/**
 * the four things a phone does to a device, two by two.
 *
 * Files and Logs are drawn disabled rather than left out, the way the desktop toolbar draws its
 * unfinished buttons: the shape is the real one and nothing has to move when they arrive.
 */
@Composable
private fun ActionGrid(enabled: Boolean, onMirror: () -> Unit, onScreenshot: () -> Unit) {
    Column(Modifier.padding(Dimens.gap)) {
        Row {
            SheetAction(Res.drawable.mirror, Res.string.action_mirror, enabled, Modifier.weight(1f), onMirror)
            SheetAction(Res.drawable.screenshot, Res.string.action_screenshot, enabled, Modifier.weight(1f), onScreenshot)
        }
        Spacer(Modifier.height(Dimens.gap))
        Row {
            SheetAction(Res.drawable.browse, Res.string.action_browse, false, Modifier.weight(1f)) {}
            SheetAction(Res.drawable.logs, Res.string.action_view_logs, false, Modifier.weight(1f)) {}
        }
    }
}

@Composable
private fun SheetAction(
    icon: DrawableResource,
    label: StringResource,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Colors.accent else Colors.menuDisabledText
    Row(
        modifier
            .padding(horizontal = Dimens.gap / 2)
            .heightIn(min = Dimens.touchRowHeight)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AppIcon(icon, size = Dimens.iconLarge, tint = tint)
        Spacer(Modifier.width(Dimens.gap))
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) Colors.primaryText else Colors.menuDisabledText,
        )
    }
}

@Composable
private fun Footer(
    device: Device?,
    enabled: Boolean,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onRestart: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCopy) {
            Text(stringResource(Res.string.sheet_copy_serial), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRename, enabled = enabled && device?.isOnline == true) {
            Text(stringResource(Res.string.edit_name), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onRestart, enabled = enabled && device?.isOnline == true) {
            Text(stringResource(Res.string.restart), style = MaterialTheme.typography.bodySmall)
        }
        // only a wireless device can be dropped; a USB one comes straight back
        if (device?.isWireless == true) {
            TextButton(onClick = onDisconnect, enabled = enabled) {
                Text(
                    stringResource(Res.string.disconnect),
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.error,
                )
            }
        }
    }
}

/**
 * the PNG that was just taken, in place of the properties list.
 *
 * A phone has no folder anyone will find, so this doesn't write a file the way the desktop does —
 * it shows the shot and throws it away. Handing it to the system share sheet is the next step, and
 * needs a platform seam this app doesn't have yet.
 */
@Composable
private fun Screenshot(bytes: ByteArray?, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val image = remember(bytes) { bytes?.let { decodeImage(it) } }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(Dimens.gap),
                )
            } else {
                CircularProgressIndicator()
            }
        }
        Row(Modifier.fillMaxWidth().padding(Dimens.gap), horizontalArrangement = Arrangement.End) {
            Button(onClick = onDone) { Text(stringResource(Res.string.sheet_done)) }
        }
    }
}
