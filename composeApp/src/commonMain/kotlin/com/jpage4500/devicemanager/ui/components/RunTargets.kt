package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.selection.*
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
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * which devices a page acts on — Scripts, Commands and Input all ask the same question: the chosen
 * ones that are ready when the action starts, or every ready one when [all] is on (Scripts only).
 */
fun runTargets(ready: List<Device>, chosen: List<String>, all: Boolean = false): List<String> =
    if (all) ready.map { it.id } else chosen.filter { id -> ready.any { it.id == id } }

/**
 * "Runs on: [chips] + [recent]" — the chosen devices, the shared [DevicePickerButton] to change them,
 * and after it a chip for each of the [recent] devices that's ready and not chosen, newest first —
 * one click to bring back a device dropped a minute ago, as Logs and Files do for their tabs.
 *
 * Nothing is chosen for the page until someone picks: the Devices selection is not a start, since
 * which devices that happens to be is rarely the ones to type or run on.
 *
 * @param label what the row is called — "Runs on", or "Types on" for Input
 * @param all null for no All devices pill; else whether it's on. On, it stands in for the chips;
 * picking a device turns it off, which is the caller's to do in [onChoose]
 * @param recent the page's recently chosen devices, or null for no chips after the +
 */
@Composable
fun RunsOn(
    ready: List<Device>,
    chosen: List<String>,
    onChoose: (List<String>) -> Unit,
    label: String = stringResource(Res.string.run_on),
    all: Boolean? = null,
    onAll: (Boolean) -> Unit = {},
    recent: RecentDevices? = null,
) {
    val recentIds = recent?.ids?.collectAsState()?.value.orEmpty()
    val allOn = all == true
    val chips = if (allOn) emptyList() else recentIds
        .filter { id -> id !in chosen && ready.any { it.id == id } }
        .take(MAX_RECENT_CHIPS)
    val skipped = if (allOn) 0 else chosen.count { id -> ready.none { it.id == id } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Colors.primaryText)
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.liveDot),
        ) {
            if (all != null) {
                AllDevicesPill(stringResource(Res.string.run_all_devices, ready.size), allOn) { onAll(!allOn) }
            }
            if (!allOn) {
                if (chosen.isEmpty()) {
                    Text(stringResource(Res.string.run_none), style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
                }
                chosen.forEach { id ->
                    DeviceChipPill(DeviceManager.nameOf(id)) { onChoose(chosen - id) }
                }
            }
        }
        DevicePickerButton(
            title = stringResource(Res.string.run_pick_title),
            checked = if (allOn) emptyList() else chosen,
            onChange = { ids, on ->
                val from = if (allOn) emptyList() else chosen
                onChoose(if (on) (from + ids).distinct() else from - ids.toSet())
            },
            canPick = { it.isReady },
            size = Dimens.chipHeight + Dimens.liveDot,
        )
        chips.forEach { id ->
            key(id) {
                val name = DeviceManager.nameOf(id)
                RecentChip(name, stringResource(Res.string.run_add_recent, name)) { onChoose(chosen + id) }
            }
        }
        if (skipped > 0) {
            Text(stringResource(Res.string.run_skips, skipped), style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
        }
    }
}

/** the All devices toggle: filled when on, an outline when off — the Devices page's filter chip look. */
@Composable
private fun AllDevicesPill(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .height(Dimens.chipHeight)
            .clip(shape)
            .background(if (on) Colors.chipOn else Color.Transparent)
            .border(Dimens.hairline, if (on) Colors.chipOn else Colors.divider, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.navGap),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Colors.chipOnText else Colors.primaryText, maxLines = 1)
    }
}

@Composable
private fun DeviceChipPill(name: String, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .height(Dimens.chipHeight)
            .clip(shape)
            .background(Colors.pillBg)
            .border(Dimens.hairline, Colors.pillBorder, shape)
            .padding(start = Dimens.navGap, end = Dimens.liveDot),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = Colors.primaryText, maxLines = 1)
        Spacer(Modifier.width(Dimens.liveDot))
        AppIcon(
            Res.drawable.close,
            size = Dimens.tabCloseIcon,
            tint = Colors.secondaryText,
            contentDescription = stringResource(Res.string.tab_close, name),
            modifier = Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(Dimens.hairline * 2),
        )
    }
}
