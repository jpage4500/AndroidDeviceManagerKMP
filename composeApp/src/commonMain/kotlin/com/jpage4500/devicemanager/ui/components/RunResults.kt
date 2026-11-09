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
 * a row per device, and the output of the one clicked under them — a script's run and a command's.
 *
 * @param devices null when nothing has run yet, which shows [emptyTitle] and [emptyDetail]
 * @param title the header's text; "Results" unless the page has something better, like the command
 * @param onRetry the failed devices, to run again — offered once the run has finished
 * @param onSend a line typed for the shown row while it runs — its stdin; null hides the field
 */
@Composable
fun RunResults(
    devices: List<DeviceRun>?,
    outputFor: String?,
    onOutputFor: (String) -> Unit,
    onRetry: (List<String>) -> Unit,
    emptyTitle: String,
    emptyDetail: String,
    modifier: Modifier,
    title: String = stringResource(Res.string.run_results),
    onSend: ((deviceId: String, text: String) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    Column(modifier.clip(shape).border(Dimens.hairline, Colors.sidebarBorder, shape).background(Colors.listBg)) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.headerHeight).background(Colors.tableHeaderBg).padding(horizontal = Dimens.rowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Colors.tableHeaderText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val running = devices.orEmpty().any { it.state == RunState.RUNNING }
            val failed = devices.orEmpty().filter { it.state == RunState.FAILED }
            if (!running && failed.isNotEmpty()) {
                Text(
                    stringResource(Res.string.run_again_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = Colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(Dimens.navCorner))
                        .clickable { onRetry(failed.map { it.deviceId }) }
                        .padding(horizontal = Dimens.gap, vertical = Dimens.liveDot),
                )
            }
        }
        HorizontalDivider(color = Colors.tableRule)
        if (devices == null) {
            EmptyState(emptyTitle, emptyDetail)
            return@Column
        }
        // one row is the whole run: its output is what there is to see, without a click
        val shownId = outputFor ?: devices.singleOrNull()?.deviceId
        ResultHeader()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(devices, key = { _, it -> it.deviceId }) { index, device ->
                ResultRow(device, striped = index % 2 == 1, selected = device.deviceId == shownId) { onOutputFor(device.deviceId) }
            }
        }
        HorizontalDivider(color = Colors.tableRule)
        val shown = devices.firstOrNull { it.deviceId == shownId }
        Output(shown, onSend, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun ResultHeader() {
    @Composable
    fun Title(res: StringResource, modifier: Modifier) = Text(
        stringResource(res),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Colors.tableHeaderText,
        modifier = modifier,
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.rowPadding, vertical = Dimens.liveDot)) {
        Title(Res.string.run_col_device, Modifier.width(Dimens.resultDeviceWidth))
        Title(Res.string.run_col_state, Modifier.width(Dimens.resultStateWidth))
        Title(Res.string.run_col_exit, Modifier.width(Dimens.resultExitWidth))
        Title(Res.string.run_col_time, Modifier.width(Dimens.resultTimeWidth))
        Title(Res.string.run_col_last, Modifier.weight(1f))
    }
}

@Composable
private fun ResultRow(device: DeviceRun, striped: Boolean, selected: Boolean, onClick: () -> Unit) {
    // ticks while running, so the time column counts up
    var now by remember { mutableStateOf(DateUtils.nowMs()) }
    if (device.state == RunState.RUNNING) {
        LaunchedEffect(device.deviceId) {
            while (true) {
                delay(1_000)
                now = DateUtils.nowMs()
            }
        }
    }
    val (stateLabel, stateColor) = when (device.state) {
        RunState.RUNNING -> stringResource(Res.string.run_state_running) to Colors.accent
        RunState.DONE -> stringResource(Res.string.run_state_done) to Colors.online
        RunState.FAILED -> stringResource(Res.string.run_state_failed) to Colors.error
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.tableRowHeight)
            .background(
                when {
                    selected -> Colors.rowSelectedBg
                    striped -> Colors.tableHeaderBg
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(device.name, style = MaterialTheme.typography.bodySmall, color = Colors.primaryText, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(Dimens.resultDeviceWidth))
        Text(stateLabel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = stateColor,
            modifier = Modifier.width(Dimens.resultStateWidth))
        Text(device.exitCode?.toString() ?: "", style = MaterialTheme.typography.bodySmall, color = Colors.primaryText,
            modifier = Modifier.width(Dimens.resultExitWidth))
        Text(duration((device.endedMs ?: now) - device.startedMs), style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText, modifier = Modifier.width(Dimens.resultTimeWidth))
        Text(device.lastLine.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Output(device: DeviceRun?, onSend: ((String, String) -> Unit)?, modifier: Modifier) {
    Column(modifier.background(Colors.toolbarBg)) {
        Text(
            if (device == null) stringResource(Res.string.run_pick_result) else stringResource(Res.string.run_output, device.name),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Colors.secondaryText,
            modifier = Modifier.padding(horizontal = Dimens.rowPadding, vertical = Dimens.liveDot),
        )
        if (device == null) return@Column
        val lines = device.output
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty()) {
                Text(stringResource(Res.string.run_no_output), style = MaterialTheme.typography.bodySmall,
                    color = Colors.secondaryText, modifier = Modifier.padding(horizontal = Dimens.rowPadding))
            } else {
                val list = rememberLazyListState()
                // follows the tail while the script is still printing
                LaunchedEffect(lines) { list.scrollToItem(lines.lastIndex) }
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Dimens.rowPadding), state = list) {
                        items(lines) { line ->
                            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Colors.primaryText)
                        }
                    }
                }
            }
        }
        if (onSend != null && device.state == RunState.RUNNING) {
            ReplyField(Modifier.fillMaxWidth().padding(Dimens.gap)) { onSend(device.deviceId, it) }
        }
    }
}

/** a line for a running script's stdin: Enter sends it, empty or not — an empty one is a bare Enter. */
@Composable
private fun ReplyField(modifier: Modifier, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AppTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier,
        hint = stringResource(Res.string.run_reply_hint),
        onSubmit = {
            onSend(text)
            text = ""
        },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
}

/** 850ms, 12.4s, 3m 04s — how long a run took, to the precision that matters at that length. */
private fun duration(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
    else -> "${ms / 60_000}m ${((ms / 1000) % 60).toString().padStart(2, '0')}s"
}
