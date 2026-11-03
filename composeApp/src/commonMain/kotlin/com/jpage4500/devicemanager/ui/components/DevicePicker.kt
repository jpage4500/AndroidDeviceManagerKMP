package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.stringResource

/**
 * the + that opens a checklist of every device — the one picker behind Logs' and Files' tabs and
 * Scripts', Commands' and Input's "Runs on".
 *
 * The list stays open while devices are ticked and unticked, so several can be picked at once, and
 * Select all at the top ticks every device [canPick] allows (or unticks them all, when it's ticked).
 * A device that can't be picked can still be unticked: an offline one already chosen.
 *
 * @param checked the devices ticked now
 * @param onChange devices ticked (true) or unticked (false) — several at once from Select all
 * @param canPick whether a device that isn't ticked may be — online for a tab, ready for a run
 * @param size the +'s size; the list opens just under it
 */
@Composable
fun DevicePickerButton(
    title: String,
    checked: List<String>,
    onChange: (List<String>, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    canPick: (Device) -> Boolean = { it.isOnline },
    size: Dp = Dimens.tabAdd,
) {
    val devices by DeviceManager.devices.collectAsState()
    var picking by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(Dimens.corner))
                .clickable { picking = !picking },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(Res.drawable.add, size = Dimens.icon, tint = Colors.toolbarIcon, contentDescription = title)
        }
        if (picking) {
            val density = LocalDensity.current
            val position = remember(density, size) {
                PointerPositionProvider(IntOffset(0, with(density) { (size + Dimens.tabGap).roundToPx() }))
            }
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { picking = false },
                properties = PopupProperties(focusable = true),
            ) {
                DevicePickerList(title, devices, checked, onChange, canPick) { picking = false }
            }
        }
    }
}

@Composable
private fun DevicePickerList(
    title: String,
    devices: List<Device>,
    checked: List<String>,
    onChange: (List<String>, Boolean) -> Unit,
    canPick: (Device) -> Boolean,
    onDone: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    val pickable = devices.filter { it.id in checked || canPick(it) }
    val allOn = pickable.isNotEmpty() && pickable.all { it.id in checked }
    Column(
        Modifier
            .width(Dimens.tabPickerWidth)
            .shadow(Dimens.menuShadow, shape)
            .clip(shape)
            .background(Colors.menuBg)
            .border(Dimens.hairline, Colors.menuBorder, shape),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Colors.secondaryText,
            modifier = Modifier.padding(horizontal = Dimens.rowPadding + Dimens.hairline * 2, vertical = Dimens.navGap),
        )
        HorizontalDivider(color = Colors.divider)
        if (devices.isEmpty()) {
            Text(
                stringResource(Res.string.picker_none),
                style = MaterialTheme.typography.labelMedium,
                color = Colors.secondaryText,
                modifier = Modifier.padding(horizontal = Dimens.rowPadding, vertical = Dimens.gap),
            )
        } else {
            PickerRow(enabled = pickable.isNotEmpty(), onClick = {
                if (allOn) {
                    onChange(pickable.map { it.id }, false)
                } else {
                    onChange(pickable.map { it.id }.filterNot { it in checked }, true)
                }
            }) {
                CheckMark(allOn)
                Spacer(Modifier.width(Dimens.navGap))
                Text(
                    stringResource(Res.string.picker_select_all),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Colors.primaryText,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = Colors.divider)
        }
        Column(
            Modifier
                .heightIn(max = Dimens.savedFiltersMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Dimens.tabGap),
        ) {
            devices.forEach { device ->
                val on = device.id in checked
                val enabled = on || canPick(device)
                PickerRow(enabled = enabled, onClick = { onChange(listOf(device.id), !on) }) {
                    CheckMark(on)
                    Spacer(Modifier.width(Dimens.navGap))
                    Box(
                        Modifier
                            .size(Dimens.tabDot)
                            .clip(CircleShape)
                            .background(if (device.isOnline) Colors.online else Colors.offline)
                    )
                    Spacer(Modifier.width(Dimens.gap))
                    Text(
                        device.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) Colors.primaryText else Colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    device.model?.let { model ->
                        Spacer(Modifier.width(Dimens.gap))
                        Text(model, style = MaterialTheme.typography.labelSmall, color = Colors.secondaryText, maxLines = 1)
                    }
                }
            }
        }
        HorizontalDivider(color = Colors.divider)
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.navGap, vertical = Dimens.gap)) {
            Spacer(Modifier.weight(1f))
            DialogButton(stringResource(Res.string.done), primary = true, onClick = onDone)
        }
    }
}

@Composable
private fun PickerRow(enabled: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.placeRowHeight + Dimens.tabGap)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.rowPadding + Dimens.hairline * 2),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
