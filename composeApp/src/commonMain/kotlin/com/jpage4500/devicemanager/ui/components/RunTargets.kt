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
 * ones that are ready when the action starts. There is no "all": the devices are always picked.
 */
fun runTargets(ready: List<Device>, chosen: List<String>): List<String> =
    chosen.filter { id -> ready.any { it.id == id } }

/**
 * "Runs on: [chips] +" — the chosen devices, and the shared [DevicePickerButton] to change them.
 *
 * Nothing chosen when the page opens starts from what is selected on the Devices page.
 *
 * @param label what the row is called — "Runs on", or "Types on" for Input
 */
@Composable
fun RunsOn(
    ready: List<Device>,
    chosen: List<String>,
    onChoose: (List<String>) -> Unit,
    label: String = stringResource(Res.string.run_on),
) {
    val selection by DeviceManager.selectedIds.collectAsState()
    LaunchedEffect(Unit) {
        if (chosen.isEmpty()) {
            val seed = selection.filter { id -> ready.any { it.id == id } }
            if (seed.isNotEmpty()) onChoose(seed)
        }
    }
    val skipped = chosen.count { id -> ready.none { it.id == id } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Colors.primaryText)
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.liveDot),
        ) {
            if (chosen.isEmpty()) {
                Text(stringResource(Res.string.run_none), style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
            }
            chosen.forEach { id ->
                DeviceChipPill(DeviceManager.nameOf(id)) { onChoose(chosen - id) }
            }
        }
        DevicePickerButton(
            title = stringResource(Res.string.run_pick_title),
            checked = chosen,
            onChange = { ids, on -> onChoose(if (on) chosen + ids else chosen - ids.toSet()) },
            canPick = { it.isReady },
            size = Dimens.chipHeight + Dimens.liveDot,
        )
        if (skipped > 0) {
            Text(stringResource(Res.string.run_skips, skipped), style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
        }
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
