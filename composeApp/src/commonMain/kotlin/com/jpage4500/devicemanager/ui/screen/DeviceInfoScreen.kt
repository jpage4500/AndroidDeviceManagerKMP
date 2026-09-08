package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.setting.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.stringResource

/**
 * everything known about one device, as its own scrolling screen — the desktop's Device Details
 * window.
 *
 * The phone reaches the same rows through [DeviceInfoContent] instead, since [DeviceSheet] already
 * has a scroll of its own around them.
 */
@Composable
fun DeviceInfoScreen(deviceId: String, modifier: Modifier = Modifier) {
    val devices by DeviceManager.devices.collectAsState()

    // outside the scroll: EmptyState fills its space, and nothing can fill an unbounded height
    if (devices.none { it.id == deviceId }) {
        EmptyState(
            title = stringResource(Res.string.info_gone),
            detail = Device.serialOf(deviceId),
            modifier = modifier,
        )
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Colors.listBg)
            .verticalScroll(rememberScrollState())
            .padding(Dimens.gapLarge),
    ) {
        DeviceInfoContent(deviceId)
    }
}

/**
 * the rows themselves: what the device is, then a gauge each for storage and battery.
 *
 * Ported from the Swing app's `DeviceInfoScreen`. Reads the live device, so it follows a refresh
 * rather than showing a snapshot — the free space and the battery move while it is open.
 *
 * Sections rather than cards-on-grey: the same headers and flat rows a settings screen uses, so it
 * reads as part of the app rather than as two panels floating on a band of grey. Each one folds
 * away and is remembered folded — see [InfoSection]. **Must be called inside a `Column`.**
 *
 * [onRename] draws a pencil on the Name row when it is given. The desktop's window is read-only and
 * passes nothing.
 */
@Composable
fun DeviceInfoContent(deviceId: String, onRename: (() -> Unit)? = null) {
    val devices by DeviceManager.devices.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId }
    val copyToClipboard = rememberClipboardCopy()
    val collapsed by SettingsManager.getEnumSetAsState<InfoSection>(AppPref.INFO_COLLAPSED)

    if (device == null) {
        // one line, not the full-screen EmptyState: this is composed inside someone else's scroll,
        // and whatever is around it already names the device
        Text(
            text = stringResource(Res.string.info_gone),
            style = MaterialTheme.typography.bodyMedium,
            color = Colors.secondaryText,
            modifier = Modifier.fillMaxWidth().padding(Dimens.gapLarge),
            textAlign = TextAlign.Center,
        )
        return
    }

    CollapsibleSection(
        text = stringResource(Res.string.info_details),
        open = InfoSection.DETAILS.isOpen(collapsed),
        onToggle = { InfoSection.DETAILS.toggle(collapsed) },
    ) {
        Property(
            label = stringResource(Res.string.info_serial),
            value = device.serial,
            onCopy = copyToClipboard,
            // a wireless serial is `adb-RFCX20NB5TY-DBjlg4._adb-tls-connect._tcp`, which on one
            // line ellipsizes into something nobody can read back or check against a device
            maxLines = 2,
        )
        Property(
            label = stringResource(Res.string.info_name),
            // falls back to the display name, so a device nobody has renamed still has a row for it
            value = device.nickname ?: device.displayName,
            onCopy = copyToClipboard,
            onEdit = onRename,
        )
        Property(stringResource(Res.string.info_model), device.model, copyToClipboard)
        Property(stringResource(Res.string.info_phone), device.phone, copyToClipboard)
        Property(stringResource(Res.string.info_imei), device.imei, copyToClipboard)
        Property(stringResource(Res.string.info_carrier), device.carrier, copyToClipboard)
        Property(
            label = stringResource(Res.string.info_os),
            value = device.os?.let { os -> device.sdk?.let { "$os (SDK $it)" } ?: os },
            onCopy = copyToClipboard,
        )
        Property(stringResource(Res.string.info_timezone), device.timezone, copyToClipboard)
        Property(
            label = stringResource(Res.string.info_booted),
            value = device.bootTimeMs?.let { booted ->
                val since = Utils.formatDuration(DateUtils.nowMs() - booted)
                "${DateUtils.formatDate(DateUtils.timeToDate(booted), "MM/dd h:mm a")} (up $since)"
            },
            onCopy = copyToClipboard,
        )
        Property(
            stringResource(Res.string.info_custom_1),
            device.customProperty(Device.CUST_PROP_1),
            copyToClipboard,
        )
        Property(
            stringResource(Res.string.info_custom_2),
            device.customProperty(Device.CUST_PROP_2),
            copyToClipboard,
        )
    }

    Spacer(Modifier.height(Dimens.gapLarge))

    CollapsibleSection(
        text = stringResource(Res.string.info_status),
        open = InfoSection.STATUS.isOpen(collapsed),
        onToggle = { InfoSection.STATUS.toggle(collapsed) },
    ) {
        Gauge(
            label = stringResource(Res.string.info_storage),
            percent = device.storageUsedPercent,
            detail = listOfNotNull(
                device.freeSpace?.let { "Free ${Utils.bytesToDisplayString(it)}" },
                device.totalSpace?.let { "Total ${Utils.bytesToDisplayString(it)}" },
            ).joinToString(", "),
            // a nearly-full disk is what stops a device installing anything
            color = when {
                (device.storageUsedPercent ?: 0) > 90 -> Colors.error
                (device.storageUsedPercent ?: 0) > 75 -> Colors.busy
                else -> Colors.accent
            },
        )
        Gauge(
            label = stringResource(Res.string.info_battery),
            percent = device.batteryLevel,
            detail = listOfNotNull(
                device.batteryLevel?.let { "$it%" },
                device.powerStatus.takeIf { it != PowerStatus.NONE }?.name?.lowercase(),
            ).joinToString(", "),
            color = when {
                (device.batteryLevel ?: 100) <= 15 -> Colors.error
                (device.batteryLevel ?: 100) <= 30 -> Colors.busy
                else -> Colors.online
            },
        )
    }
}

/**
 * the parts of a device's page that fold away, and are remembered folded.
 *
 * In `AppPref.INFO_COLLAPSED` rather than in the composable's own state: the sheet is built fresh
 * for each device, so a section that forgot would have to be shut again on every one. Stored as the
 * set that is **shut**, so a build that adds a section shows it rather than hiding it.
 */
enum class InfoSection { DETAILS, STATUS, COMMANDS }

fun InfoSection.isOpen(collapsed: Set<InfoSection>): Boolean = this !in collapsed

fun InfoSection.toggle(collapsed: Set<InfoSection>) {
    SettingsManager.setEnumSet(
        AppPref.INFO_COLLAPSED,
        if (this in collapsed) collapsed - this else collapsed + this,
    )
}

/**
 * name on the left, value on the right; tap to copy. an unknown value is left blank, not "null".
 *
 * **Every part of the affordance is on the row, because a column of text that silently copies is
 * indistinguishable from a column of text.** The row ripples under the finger, so the press says
 * which line it landed on; a copy hint sits in the gutter at the end of it, so the line says it can
 * be copied before anyone tries; a divider closes it, so it reads as a row rather than as a line of
 * a paragraph; and the toast afterwards names what went to the clipboard.
 */
@Composable
private fun Property(
    label: String,
    value: String?,
    onCopy: (String) -> Unit,
    maxLines: Int = 1,
    onEdit: (() -> Unit)? = null,
) {
    if (value.isNullOrBlank()) return
    val copied = stringResource(Res.string.info_copied, label)
    Row(
        Modifier
            .fillMaxWidth()
            // a real ripple rather than plainClickable: the highlight is the whole point here
            .clickable {
                onCopy(value)
                ToastManager.showToast(copied)
            }
            .padding(start = Dimens.gap, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            modifier = Modifier.width(Dimens.fieldLabelWidth),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.primaryText,
            fontWeight = FontWeight.Medium,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // one gutter, whatever is in it, so the icons line up down the right of the list
        Box(
            modifier = Modifier
                .width(Dimens.infoGutter)
                .heightIn(min = Dimens.iconLarge + Dimens.gap)
                // the rename affordance where the name is, rather than a button at the foot
                .then(if (onEdit == null) Modifier else Modifier.plainClickable(onClick = onEdit)),
            contentAlignment = Alignment.Center,
        ) {
            if (onEdit != null) {
                AppIcon(
                    res = Res.drawable.edit,
                    size = Dimens.iconLarge,
                    tint = Colors.accent,
                    contentDescription = stringResource(Res.string.edit_name),
                )
            } else {
                AppIcon(res = Res.drawable.copy, size = Dimens.icon, tint = Colors.secondaryText)
            }
        }
    }
    HorizontalDivider(color = Colors.divider)
}

@Composable
private fun Gauge(label: String, percent: Int?, detail: String, color: Color) {
    if (percent == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gap, vertical = Dimens.gap)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Colors.primaryText)
            Spacer(Modifier.weight(1f))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Colors.headerBackground)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                    .background(color)
            )
        }
    }
}
