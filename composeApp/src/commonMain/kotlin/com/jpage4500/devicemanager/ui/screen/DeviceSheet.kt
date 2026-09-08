package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.setting.*
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
 * **A [BaseDialog] like every other dialog in the app**, which is where its title bar, its rounded
 * card and its place in the dialog stack come from — it used to hand-roll all three on top of a bare
 * `DialogContainer`, and read as a different product because of it. What is its own is the grid of
 * actions at the top, which is the desktop's right-click menu filtered to what a finger can reach
 * for; the body under it is [DeviceInfoContent], the same rows the desktop's Device Details window
 * draws, and the rest of that menu is the rows at the foot.
 */
@Composable
fun DeviceSheet(deviceId: String, onDismiss: () -> Unit, onMirror: (String) -> Unit) {
    val devices by DeviceManager.devices.collectAsState()
    val connections by DeviceManager.connections.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId }
    val serial = device?.serial ?: Device.serialOf(deviceId)
    // which desk this device came in through, which is the whole point of saying it here
    val via = connections.firstOrNull { it.id == Device.connectionOf(deviceId) }?.label.orEmpty()
    val scope = rememberCoroutineScope()

    var shot by remember(deviceId) { mutableStateOf<ByteArray?>(null) }
    var busy by remember(deviceId) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var rename by remember { mutableStateOf<InputRequest?>(null) }

    val restartTitle = stringResource(Res.string.sheet_restart_title)
    val renameTitle = stringResource(Res.string.sheet_rename_title)
    val shotFailed = stringResource(Res.string.sheet_screenshot_failed)

    val online = device?.isOnline == true

    BaseDialog(
        title = device?.displayName ?: serial,
        // the same icon and the same state colour the list row uses, so the two agree at a glance
        icon = if (device?.isWireless == true) Res.drawable.device_remote else Res.drawable.android,
        iconTint = stateColor(device?.state),
        // not the serial: a wireless one eats the line to say nothing, and it is the first row of
        // the list below. where the device came from and what it runs is what the name can't say —
        // plus its state, but only when that state is worth reading
        subtitle = listOfNotNull(
            via.ifEmpty { null },
            device?.os?.let { "Android $it" },
            device?.state?.label?.takeUnless { online },
        ).joinToString("  ·  "),
        // the shot takes over the body, so its Done is the dialog's own OK rather than a button of
        // its own; it clears the shot instead of closing the sheet
        okText = stringResource(Res.string.sheet_done).takeIf { shot != null },
        onConfirm = { shot = null },
        dismissOnConfirm = false,
        onDismiss = onDismiss,
    ) {
        if (busy || shot != null) {
            Screenshot(shot)
            return@BaseDialog
        }

        ActionGrid(
            enabled = online,
            onMirror = { onMirror(deviceId) },
            onScreenshot = {
                busy = true
                scope.launch {
                    DeviceManager.screenshot(deviceId)
                        .onSuccess { shot = it }
                        .onFailure { ToastManager.showToast(shotFailed, success = false) }
                    busy = false
                }
            },
        )
        Spacer(Modifier.height(Dimens.gapLarge))

        DeviceInfoContent(
            deviceId = deviceId,
            onRename = if (online) ({
                rename = InputRequest(
                    title = renameTitle,
                    initialValue = device.nickname.orEmpty(),
                    onConfirm = { name -> scope.launch { DeviceManager.setNickname(deviceId, name) } },
                )
            }) else null,
        )
        Spacer(Modifier.height(Dimens.gapLarge))

        // the rest of the desktop's row menu, as the settings screen's icon rows rather than as text
        // buttons across the foot — those wrapped mid-word and matched nothing else in the app
        SectionHeader(stringResource(Res.string.info_actions))
        SettingItem(
            icon = Res.drawable.restart,
            labelRes = Res.string.restart,
            enabled = online,
            // only a wireless device can be dropped; a USB one comes straight back
            showDivider = device?.isWireless == true,
            onClick = {
                val run = { scope.launch { DeviceManager.reboot(deviceId) }; Unit }
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
        )
        if (device?.isWireless == true) {
            SettingItem(
                icon = Res.drawable.close,
                labelRes = Res.string.disconnect,
                labelColor = Colors.error,
                iconColor = Colors.error,
                showDivider = false,
                onClick = {
                    scope.launch { DeviceManager.disconnect(deviceId) }
                    onDismiss()
                },
            )
        }
    }

    // siblings rather than children: each is its own dialog, and the sheet steps aside for whichever
    // one is on top — see DialogStack
    ConfirmDialog(confirm) { confirm = null }
    InputDialog(rename) { rename = null }
}

/** the colour a device's state reads as, the same mapping the list row uses. */
private fun stateColor(state: DeviceState?): Color = when (state) {
    DeviceState.DEVICE -> Colors.online
    DeviceState.UNAUTHORIZED, DeviceState.AUTHORIZING -> Colors.unauthorized
    DeviceState.OFFLINE, DeviceState.DETACHED -> Colors.offline
    else -> Colors.error
}

/**
 * the four things a phone does to a device, two by two.
 *
 * Tiles rather than a row of icon-and-label: at the top of the sheet these are the first thing a
 * thumb reaches for, and text on the dialog's own ground doesn't look like anything you can press.
 * Browse and Logs are drawn as **dashed outlines** rather than left out — the shape is the real one,
 * nothing has to move when they arrive, and an outline reads as not-yet where grey text read as
 * broken.
 */
@Composable
private fun ActionGrid(enabled: Boolean, onMirror: () -> Unit, onScreenshot: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            ActionTile(Res.drawable.mirror, Res.string.action_mirror, enabled, Modifier.weight(1f), onMirror)
            ActionTile(Res.drawable.screenshot, Res.string.action_screenshot, enabled, Modifier.weight(1f), onScreenshot)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            ActionTile(Res.drawable.browse, Res.string.action_browse, false, Modifier.weight(1f)) {}
            ActionTile(Res.drawable.logs, Res.string.action_view_logs, false, Modifier.weight(1f)) {}
        }
    }
}

@Composable
private fun ActionTile(
    icon: DrawableResource,
    label: StringResource,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.tileCorner)
    val tint = if (enabled) Colors.accent else Colors.menuDisabledText
    val outline = Colors.menuDisabledText
    val fill = Colors.accent.copy(alpha = Dimens.backgroundAlpha)
    Column(
        modifier
            .height(Dimens.tileHeight)
            .clip(shape)
            .then(
                if (enabled) Modifier.background(fill)
                else Modifier.dashedOutline(outline, Dimens.tileCorner)
            )
            .clickable(enabled = enabled, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(icon, size = Dimens.tileIcon, tint = tint)
        Spacer(Modifier.height(Dimens.gap))
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) Colors.primaryText else Colors.menuDisabledText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** the border on a tile whose screen isn't written yet: there, and visibly not ready. */
private fun Modifier.dashedOutline(color: Color, corner: Dp) = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
        ),
        cornerRadius = CornerRadius(corner.toPx()),
    )
}

/**
 * the PNG that was just taken, in place of the properties list.
 *
 * A phone has no folder anyone will find, so this doesn't write a file the way the desktop does —
 * it shows the shot and throws it away. Handing it to the system share sheet is the next step, and
 * needs a platform seam this app doesn't have yet.
 *
 * Sized by the image's own aspect ratio rather than by a weight: it hangs in the dialog's scroll,
 * and a child that fills the height of a scroll is a child asking to be measured against infinity.
 */
@Composable
private fun Screenshot(bytes: ByteArray?) {
    val image = remember(bytes) { bytes?.let { decodeImage(it) } }
    if (image == null) {
        Box(
            Modifier.fillMaxWidth().height(Dimens.tileHeight * 2),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(image.width.toFloat() / image.height.toFloat()),
    )
}
