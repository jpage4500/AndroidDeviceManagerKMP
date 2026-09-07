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
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.stringResource

/**
 * everything known about one device.
 *
 * Ported from the Swing app's `DeviceInfoScreen`: a properties list, then a gauge each for storage
 * and battery. Reads the live device, so it follows a refresh rather than showing a snapshot.
 */
@Composable
fun DeviceInfoScreen(serial: String, modifier: Modifier = Modifier) {
    val devices by DeviceManager.devices.collectAsState()
    val device = devices.firstOrNull { it.serial == serial }
    val copyToClipboard = rememberClipboardCopy()

    if (device == null) {
        EmptyState(
            title = stringResource(Res.string.info_gone),
            detail = serial,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Colors.headerBackground)
            .verticalScroll(rememberScrollState())
            .padding(Dimens.gapLarge),
    ) {
        Card {
            Property(stringResource(Res.string.info_serial), device.serial, copyToClipboard)
            Property(stringResource(Res.string.info_name), device.nickname, copyToClipboard)
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

        Card {
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
}

/** the panel a group of rows sits on — the Swing app's card. */
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.corner))
            .background(Colors.listBg)
            .border(Dp.Hairline, Colors.divider, RoundedCornerShape(Dimens.corner))
            .padding(vertical = Dimens.gap),
        content = content,
    )
}

/** name on the left, value on the right; click to copy. an unknown value is left blank, not "null". */
@Composable
private fun Property(label: String, value: String?, onCopy: (String) -> Unit) {
    if (value.isNullOrBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .plainClickable { onCopy(value) }
            .padding(horizontal = Dimens.gapLarge, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            modifier = Modifier.width(90.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.primaryText,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Gauge(label: String, percent: Int?, detail: String, color: Color) {
    if (percent == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gapLarge, vertical = Dimens.gap)) {
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
