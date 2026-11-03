package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.ui.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.common.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

/**
 * one device at a glance, beside the table: what it is, how it's connected, its battery and storage,
 * and its screen. What can be done to it is the selection bar's, which acts on this device too.
 *
 * it follows the table's lead selection while it's open, so clicking down the rows reads them one
 * after another without opening and closing anything — starting each device at the top. Every
 * property the device reports is here, each copied by a click; the Device Details window that used
 * to hold them is gone.
 */
@Composable
fun DeviceDetailsPanel(
    device: Device,
    busy: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var confirmRestart by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Dimens.cardCorner)
    val ready = device.isReady

    Column(
        modifier
            .width(Dimens.detailsPanelWidth)
            .fillMaxHeight()
            .clip(shape)
            .border(Dimens.hairline, Colors.sidebarBorder, shape)
            .background(Colors.listBg),
    ) {
        // who it is
        Row(
            Modifier.fillMaxWidth().padding(start = Dimens.gapLarge, end = Dimens.gap, top = Dimens.rowPadding, bottom = Dimens.rowPadding),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = Dimens.liveDot)
                    .size(Dimens.stateDot)
                    .clip(CircleShape)
                    .background(device.stateColor(busy))
            )
            Spacer(Modifier.width(Dimens.navGap))
            Column(Modifier.weight(1f)) {
                Text(
                    device.displayName,
                    fontSize = Dimens.detailsTitleSize,
                    fontWeight = FontWeight.Bold,
                    color = Colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(device.model, device.os?.let { stringResource(Res.string.details_android, it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val closeLabel = stringResource(Res.string.close)
            Box(
                Modifier.size(Dimens.tabAdd).clip(RoundedCornerShape(Dimens.navCorner)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Res.drawable.close, size = Dimens.tabCloseIcon, tint = Colors.secondaryText, contentDescription = closeLabel)
            }
        }
        HorizontalDivider(color = Colors.sidebarBorder)

        val scroll = rememberScrollState()
        // another device starts at the top — its screen and name — not where the last one was left
        LaunchedEffect(device.id) { scroll.scrollTo(0) }
        val copy = rememberClipboardCopy()
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(Dimens.gapLarge),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapLarge),
        ) {
            if (ready) ScreenPreview(device.id)

            // every property it reports, the Device Details window's list. a click copies the value
            Column {
                Fact(stringResource(Res.string.info_serial), device.serial, copy, maxLines = 2)
                Fact(stringResource(Res.string.info_name), device.nickname ?: device.displayName, copy)
                Fact(stringResource(Res.string.info_model), device.model, copy)
                Fact(stringResource(Res.string.info_os), device.os?.let { os -> device.sdk?.let { "$os (SDK $it)" } ?: os }, copy)
                Fact(
                    stringResource(Res.string.details_battery),
                    device.batteryLevel?.let { level ->
                        if (device.powerStatus.isCharging) stringResource(Res.string.details_battery_charging, level) else "$level%"
                    },
                    copy,
                )
                Fact(
                    stringResource(Res.string.details_connection),
                    stringResource(if (device.isWireless) Res.string.details_wifi else Res.string.details_usb),
                    copy,
                )
                Fact(stringResource(Res.string.info_phone), device.phone, copy)
                Fact(stringResource(Res.string.info_imei), device.imei, copy)
                Fact(stringResource(Res.string.info_carrier), device.carrier, copy)
                Fact(stringResource(Res.string.info_timezone), device.timezone, copy)
                Fact(
                    stringResource(Res.string.info_booted),
                    device.bootTimeMs?.let { booted ->
                        val since = Utils.formatDuration(DateUtils.nowMs() - booted)
                        "${DateUtils.formatDate(DateUtils.timeToDate(booted), "MM/dd h:mm a")} (up $since)"
                    },
                    copy,
                )
                Fact(stringResource(Res.string.info_custom_1), device.customProperty(Device.CUST_PROP_1), copy)
                Fact(stringResource(Res.string.info_custom_2), device.customProperty(Device.CUST_PROP_2), copy)
            }

            StorageBar(device.freeSpace, device.totalSpace)
        }

        HorizontalDivider(color = Colors.sidebarBorder)
        // icons with their names as tooltips: worded buttons don't fit 300dp without wrapping
        Row(
            Modifier.fillMaxWidth().padding(Dimens.rowPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
        ) {
            IconBox(Res.drawable.restart, stringResource(Res.string.details_restart), enabled = ready) { confirmRestart = true }
            if (device.isWireless) {
                IconBox(stringResource(Res.string.details_disconnect), onClick = { scope.launch { DeviceManager.disconnect(device.id) } }) { tint ->
                    PathIcon(GlyphPaths.WIFI_OFF, size = Dimens.icon, tint = tint)
                }
            }
        }
    }

    if (confirmRestart) {
        ConfirmationDialog(
            title = stringResource(Res.string.details_restart_title),
            message = stringResource(Res.string.details_restart_msg, device.displayName),
            icon = Res.drawable.restart,
            onDismiss = { confirmRestart = false },
            onConfirm = { scope.launch { DeviceManager.reboot(device.id) } },
        )
    }
}

/**
 * a label and its value; a click copies the value and says so. Nothing at all for a value the
 * device hasn't reported. The copy icon at the end shows while the pointer is over the row, in a
 * slot that's always there, so hovering doesn't shift the value.
 */
@Composable
private fun Fact(label: String, value: String?, onCopy: (String) -> Unit, maxLines: Int = 1) {
    if (value.isNullOrBlank()) return
    val copied = stringResource(Res.string.info_copied, label)
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.navCorner))
            .onHover { hovered = it }
            .clickable {
                onCopy(value)
                ToastManager.showToast(copied)
            }
            .padding(horizontal = Dimens.tabGap, vertical = Dimens.tabGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            modifier = Modifier.width(Dimens.factLabelWidth),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.primaryText,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AppIcon(
            Res.drawable.copy,
            size = Dimens.tabCloseIcon,
            tint = Colors.secondaryText.copy(alpha = if (hovered) 1f else 0f),
            modifier = Modifier.padding(start = Dimens.tabGap),
        )
    }
}

/**
 * a still of the device's screen, refreshed on a slow timer while the panel shows it; a click opens
 * the mirror.
 *
 * a one-shot `screencap`, not the mirror's stream: a stream is a poll that would keep running for a
 * panel someone has only glanced at. Every [PREVIEW_REFRESH_MS] is one PNG for one device, which
 * the 45-device rig doesn't notice — and none at all while the display is asleep, which
 * [DeviceManager.previewStill] finds out in the same round trip and this draws as a dark screen.
 *
 * **the frame is always [Dimens.previewHeight] tall**, from before the first still arrives, so the
 * facts under it never jump when it does; only its width follows the screen's shape. Until a device
 * has been seen once its shape comes from [DeviceDetails.CMD_SCREEN_SIZE], asked alongside the first
 * still and answered long before it, and [DEFAULT_SCREEN_ASPECT] until even that arrives — so a
 * tablet held landscape gets a landscape frame. The last still per device is kept in
 * [previewCache], so coming back to one shows its screen at once, with a spinner while the fresh
 * one loads; the corner's Refresh asks for one now. A device that can't take a screenshot at all — a test device — shows no frame.
 */
@Composable
private fun ScreenPreview(deviceId: String) {
    val maxPx = with(LocalDensity.current) { Dimens.previewHeight.roundToPx() }
    var still by remember(deviceId) { mutableStateOf(previewCache[deviceId]) }
    // the screen's shape as the device reports it, until a still says otherwise
    var reportedAspect by remember(deviceId) { mutableStateOf<Float?>(null) }
    var loading by remember(deviceId) { mutableStateOf(true) }
    var failed by remember(deviceId) { mutableStateOf(false) }
    // the corner button's "now", which cuts the wait between stills short
    val refreshNow = remember(deviceId) { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(deviceId) {
        // a text answer in well under a second, against a capture that can take several: with
        // nothing cached, it's what gets the frame's shape right before the picture lands
        if (still == null) {
            launch {
                DeviceManager.exec(deviceId, DeviceDetails.CMD_SCREEN_SIZE).getOrNull()
                    ?.let { DeviceDetails.parseScreenSize(it) }
                    ?.let { (w, h) -> reportedAspect = w.toFloat() / h }
            }
        }
        while (true) {
            loading = true
            val next: Result<PreviewStill> = DeviceManager.previewStill(deviceId).mapCatching { result ->
                when (result) {
                    // keep the last image's shape, so a sleeping screen is drawn the same size
                    is ScreenStill.Off -> PreviewStill(
                        still?.image,
                        still?.aspect ?: reportedAspect ?: DEFAULT_SCREEN_ASPECT,
                        off = true,
                    )
                    is ScreenStill.Shot -> {
                        val image = withContext(ioDispatcher) { decodeImage(result.png)?.scaledToHeight(maxPx) }
                            ?: error("couldn't decode ${result.png.size} bytes")
                        PreviewStill(image, image.width.toFloat() / image.height.coerceAtLeast(1), off = false)
                    }
                }
            }
            next
                .onSuccess {
                    still = it
                    previewCache.put(deviceId, it)
                    failed = false
                }
                .onFailure { e ->
                    log.w("ScreenPreview: $deviceId: ${e.message}")
                    failed = still == null
                }
            loading = false
            // a press during a capture was already answered by it
            refreshNow.tryReceive()
            withTimeoutOrNull(PREVIEW_REFRESH_MS) { refreshNow.receive() }
        }
    }
    if (failed) return

    val current = still
    val aspect = current?.aspect ?: reportedAspect ?: DEFAULT_SCREEN_ASPECT
    val shape = RoundedCornerShape(Dimens.cardCorner)
    Box(Modifier.fillMaxWidth().height(Dimens.previewHeight), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(aspect)
                .clip(shape)
                .background(if (current?.off == true) Colors.previewScreenOff else Colors.sidebarBg)
                .border(Dimens.hairline, Colors.sidebarBorder, shape)
                .clickable { MirrorLauncher.mirror(deviceId) },
            contentAlignment = Alignment.Center,
        ) {
            val image = current?.image
            when {
                current?.off == true -> ScreenOff()
                image != null -> Image(
                    bitmap = image,
                    contentDescription = stringResource(Res.string.details_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (loading && current == null) {
                // nothing to look at yet: the spinner is the frame's whole content
                PreviewSpinner(Modifier.size(Dimens.previewSpinnerLarge), current)
            } else if (current != null) {
                // over a still, the corner holds Refresh, or the spinner in its place while one loads
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dimens.liveDot)
                        .size(Dimens.previewCornerButton)
                        .clip(CircleShape)
                        .background(Colors.previewScrim)
                        .then(if (loading) Modifier else Modifier.clickable { refreshNow.trySend(Unit) }),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        PreviewSpinner(Modifier.size(Dimens.previewSpinner), current)
                    } else {
                        val refreshLabel = stringResource(Res.string.details_preview_refresh)
                        CellTooltip(text = { refreshLabel }) {
                            AppIcon(
                                Res.drawable.refresh,
                                size = Dimens.previewSpinner,
                                tint = Colors.previewScrimIcon,
                                contentDescription = refreshLabel,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSpinner(modifier: Modifier, current: PreviewStill?) {
    val label = stringResource(Res.string.details_preview_loading)
    CircularProgressIndicator(
        modifier = modifier.semantics { contentDescription = label },
        // on the corner's dark scrim over a still, the frame's own grey while it's empty
        color = if (current == null) Colors.toolbarIcon else Colors.previewScrimIcon,
        strokeWidth = Dimens.previewSpinnerStroke,
    )
}

/** a sleeping display: the power glyph and "Screen off", dim, on the black of the screen. */
@Composable
private fun ScreenOff() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(Res.drawable.power, size = Dimens.previewOffIcon, tint = Colors.previewScreenOffText)
        Spacer(Modifier.height(Dimens.tabGap))
        Text(
            stringResource(Res.string.details_screen_off),
            style = MaterialTheme.typography.labelSmall,
            color = Colors.previewScreenOffText,
        )
    }
}

/** the last thing a preview showed: a still, a sleeping screen, or a sleeping screen seen first. */
private class PreviewStill(val image: ImageBitmap?, val aspect: Float, val off: Boolean)

// one still per device, already shrunk to the preview's height: ~0.4 MB each at 2x, so the whole
// 45-device rig is under 20 MB. read and written only from the composition's thread
private val previewCache = LruCache<String, PreviewStill>(48)

// a modern phone held upright (1080x2400): the frame for the moment before the device says its size
private const val DEFAULT_SCREEN_ASPECT = 9f / 20f

private val log = LoggerConfig.createLogger("DeviceDetailsPanel")

// a glance, not a mirror: often enough to show which app is up, rarely enough to cost nothing
private const val PREVIEW_REFRESH_MS = 10_000L

/** a tick box drawn the redesign's way — a green square when checked. Used in the device rows. */
/** storage as a bar, with "N free of M" over it — how full it is matters more than the number. */
@Composable
fun StorageBar(free: Long?, total: Long?) {
    if (free == null || total == null || total <= 0) return
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.liveDot)) {
        Row {
            Text(
                stringResource(Res.string.details_storage),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(
                    Res.string.details_storage_free,
                    Utils.bytesToDisplayString(free),
                    Utils.bytesToDisplayString(total),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
            )
        }
        val used = (1f - free.toFloat() / total).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(Dimens.liveDot).clip(CircleShape).background(Colors.tableRule)) {
            Box(Modifier.fillMaxWidth(used).fillMaxHeight().background(Colors.toolbarIcon))
        }
    }
}

@Composable
fun RowCheckbox(checked: Boolean, onToggle: () -> Unit, label: String, partial: Boolean = false) {
    val shape = RoundedCornerShape(Dimens.checkboxCorner)
    val filled = checked || partial
    Box(
        Modifier
            .size(Dimens.checkbox)
            .clip(shape)
            .background(if (filled) Colors.tabAccent else Colors.listBg)
            .border(Dimens.hairline, if (filled) Colors.tabAccent else Colors.secondaryText, shape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        when {
            checked -> AppIcon(Res.drawable.success, size = Dimens.checkboxTick, tint = Colors.onAccent, contentDescription = label)
            // some but not all — a dash, the way every select-all box says it
            partial -> Box(Modifier.size(Dimens.checkboxTick, Dimens.hairline * 2).background(Colors.onAccent))
        }
    }
}
