package com.jpage4500.devicemanager.ui.screen

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
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * the Scripts page: the `.sh` files in the scripts folder, and running one across devices.
 *
 * A script runs once per device with that device's serial as `$1` — see [ScriptManager]. It runs on
 * **All** online devices unless Choose… has picked some; the Devices page's Run script arrives here
 * with its selection already chosen. Desktop only — see [ScriptHost].
 */
@Composable
fun ScriptsScreen() {
    val scripts by ScriptManager.scripts.collectAsState()
    val runs by ScriptManager.runs.collectAsState()
    val selectedName by ScriptManager.selected.collectAsState()
    var input by remember { mutableStateOf<InputRequest?>(null) }
    val newTitle = stringResource(Res.string.scripts_new_title)
    val newName = stringResource(Res.string.scripts_new)

    // the folder is read each time the page opens: scripts are edited outside the app
    LaunchedEffect(Unit) { ScriptManager.reload() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.pageHeaderHeight).padding(horizontal = Dimens.pageGutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
        ) {
            Text(
                stringResource(Res.string.page_scripts),
                fontSize = Dimens.pageTitleSize,
                fontWeight = FontWeight.Bold,
                color = Colors.primaryText,
            )
            Spacer(Modifier.weight(1f))
            IconBox(Res.drawable.refresh, stringResource(Res.string.action_refresh)) { ScriptManager.reload() }
            OutlinedAction(stringResource(Res.string.scripts_show_folder)) {
                ScriptHost.folder()?.let(PlatformUtils::openUrl)
            }
            PrimaryButton(Res.drawable.add, newName) {
                input = InputRequest(newTitle, newName) { name ->
                    ScriptHost.create(name)?.let { created ->
                        ScriptManager.reload()
                        ScriptManager.select(created.fileName)
                        ScriptHost.edit(created)
                    }
                }
            }
        }

        if (scripts.isEmpty()) {
            EmptyState(
                stringResource(Res.string.scripts_empty),
                stringResource(Res.string.scripts_empty_msg, ScriptHost.folder().orEmpty()),
            )
        } else {
            Row(
                Modifier.weight(1f).padding(start = Dimens.pageGutter, end = Dimens.pageGutter, bottom = Dimens.pageGutter),
                horizontalArrangement = Arrangement.spacedBy(Dimens.rowPadding),
            ) {
                ScriptList(scripts, runs, selectedName, Modifier.width(Dimens.scriptListWidth).fillMaxHeight())
                val script = scripts.firstOrNull { it.fileName == selectedName }
                if (script != null) {
                    key(script.fileName) {
                        ScriptDetail(script, runs[script.fileName], Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }

    InputDialog(input) { input = null }
}

@Composable
private fun ScriptList(scripts: List<Script>, runs: Map<String, ScriptRun>, selected: String?, modifier: Modifier) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    LazyColumn(
        modifier
            .clip(shape)
            .border(Dimens.hairline, Colors.sidebarBorder, shape)
            .background(Colors.listBg)
            .padding(Dimens.liveDot),
    ) {
        items(scripts, key = { it.fileName }) { script ->
            val on = script.fileName == selected
            val run = runs[script.fileName]
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.navCorner))
                    .background(if (on) Colors.navSelected else Color.Transparent)
                    .clickable { ScriptManager.select(script.fileName) }
                    .padding(horizontal = Dimens.rowPadding, vertical = Dimens.gap),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(Res.drawable.script, size = Dimens.icon, tint = if (on) Colors.tabAccent else Colors.toolbarIcon)
                    Spacer(Modifier.width(Dimens.gap))
                    Text(
                        script.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = Colors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    runSummary(run),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (run != null && run.failed.isNotEmpty() && !run.running) Colors.error else Colors.secondaryText,
                    maxLines = 1,
                    modifier = Modifier.padding(start = Dimens.icon + Dimens.gap),
                )
            }
        }
    }
}

@Composable
private fun runSummary(run: ScriptRun?): String = when {
    run == null -> stringResource(Res.string.scripts_never)
    run.running -> stringResource(
        Res.string.scripts_running,
        run.devices.count { it.state != RunState.RUNNING },
        run.devices.size,
    )
    else -> stringResource(
        Res.string.scripts_last,
        run.devices.count { it.state == RunState.DONE },
        run.failed.size,
    )
}

/** one script: what runs it and on what, its source, and its last run's results. */
@Composable
private fun ScriptDetail(script: Script, run: ScriptRun?, modifier: Modifier) {
    val devices by DeviceManager.devices.collectAsState()
    val chosen by ScriptManager.chosen.collectAsState()
    val ready = devices.filter { it.isReady }
    val targets = chosen?.filter { id -> ready.any { it.id == id } } ?: ready.map { it.id }
    val running = run?.running == true
    var source by remember { mutableStateOf<List<String>>(emptyList()) }
    var outputFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(script.path) {
        source = readLocalFile(script.path)?.decodeToString()?.lines().orEmpty()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.rowPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            Column(Modifier.weight(1f)) {
                Text(script.name, fontSize = Dimens.detailsTitleSize, fontWeight = FontWeight.Bold, color = Colors.primaryText, maxLines = 1)
                Text(
                    "${script.fileName} · ${stringResource(Res.string.scripts_note)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedAction(stringResource(Res.string.scripts_edit)) { ScriptHost.edit(script) }
            if (running) {
                OutlinedAction(stringResource(Res.string.scripts_stop)) { ScriptManager.stop(script) }
            } else {
                PrimaryButton(Res.drawable.play, stringResource(Res.string.scripts_run)) {
                    if (targets.isNotEmpty()) {
                        outputFor = null
                        ScriptManager.run(script, targets)
                    }
                }
            }
        }

        RunsOn(ready = ready, skipped = devices.size - ready.size, chosen = chosen)

        // the source, read-only: editing is the editor's job, and Open in editor is one click
        val shape = RoundedCornerShape(Dimens.cardCorner)
        LazyColumn(
            Modifier
                .weight(0.35f)
                .fillMaxWidth()
                .clip(shape)
                .border(Dimens.hairline, Colors.sidebarBorder, shape)
                .background(Colors.toolbarBg)
                .padding(vertical = Dimens.gap),
        ) {
            itemsIndexed(source) { index, line ->
                Row {
                    Text(
                        "${index + 1}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = Colors.secondaryText,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(Dimens.lineNumberWidth),
                    )
                    Spacer(Modifier.width(Dimens.rowPadding))
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Colors.primaryText)
                }
            }
        }

        Results(
            script = script,
            run = run,
            outputFor = outputFor,
            onOutputFor = { outputFor = it },
            modifier = Modifier.weight(0.65f).fillMaxWidth(),
        )
    }
}

/** "Runs on: All N | Choose…", and Choose…'s chips. */
@Composable
private fun RunsOn(ready: List<Device>, skipped: Int, chosen: List<String>?) {
    val selection by DeviceManager.selectedIds.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
        Text(stringResource(Res.string.scripts_runs_on), style = MaterialTheme.typography.bodyMedium, color = Colors.primaryText)
        SegmentedToggle(
            options = listOf(false, true),
            selected = chosen != null,
            label = { if (it) stringResource(Res.string.scripts_choose) else stringResource(Res.string.scripts_all, ready.size) },
            // Choose… starts from whatever is selected on the Devices page
            onSelect = { choose -> ScriptManager.choose(if (choose) selection.filter { id -> ready.any { it.id == id } } else null) },
        )
        if (chosen == null) {
            if (skipped > 0) {
                Text(stringResource(Res.string.scripts_skips, skipped), style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
            }
            return@Row
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.liveDot),
        ) {
            chosen.forEach { id ->
                DeviceChipPill(DeviceManager.nameOf(id)) {
                    // removing the last chip goes back to All
                    ScriptManager.choose((chosen - id).takeIf { it.isNotEmpty() })
                }
            }
            AddDeviceButton(ready.filterNot { it.id in chosen }) { ScriptManager.choose(chosen + it) }
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

@Composable
private fun AddDeviceButton(choices: List<Device>, onPick: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        AppIcon(
            Res.drawable.add,
            size = Dimens.icon,
            tint = Colors.toolbarIcon,
            contentDescription = stringResource(Res.string.scripts_add_device),
            modifier = Modifier.clip(RoundedCornerShape(Dimens.navCorner)).clickable { menu = true }.padding(Dimens.liveDot),
        )
        AppPopupMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(0.dp, Dimens.chipHeight)) {
            if (choices.isEmpty()) Item(stringResource(Res.string.scripts_add_none), enabled = false) {}
            choices.forEach { device -> Item(device.displayName) { onPick(device.id) } }
        }
    }
}

/** a row per device, and the output of the one clicked under them. */
@Composable
private fun Results(
    script: Script,
    run: ScriptRun?,
    outputFor: String?,
    onOutputFor: (String) -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    Column(modifier.clip(shape).border(Dimens.hairline, Colors.sidebarBorder, shape).background(Colors.listBg)) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.headerHeight).background(Colors.tableHeaderBg).padding(horizontal = Dimens.rowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.scripts_results),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Colors.tableHeaderText,
                modifier = Modifier.weight(1f),
            )
            val failed = run?.failed.orEmpty()
            if (run != null && !run.running && failed.isNotEmpty()) {
                Text(
                    stringResource(Res.string.scripts_run_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = Colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(Dimens.navCorner))
                        .clickable { ScriptManager.run(script, failed.map { it.deviceId }, retry = true) }
                        .padding(horizontal = Dimens.gap, vertical = Dimens.liveDot),
                )
            }
        }
        HorizontalDivider(color = Colors.tableRule)
        if (run == null) {
            EmptyState(stringResource(Res.string.scripts_never), stringResource(Res.string.scripts_note))
            return@Column
        }
        ResultHeader()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(run.devices, key = { _, it -> it.deviceId }) { index, device ->
                ResultRow(device, striped = index % 2 == 1, selected = device.deviceId == outputFor) { onOutputFor(device.deviceId) }
            }
        }
        HorizontalDivider(color = Colors.tableRule)
        val shown = run.devices.firstOrNull { it.deviceId == outputFor }
        Output(shown, Modifier.weight(1f).fillMaxWidth())
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
        Title(Res.string.scripts_col_device, Modifier.width(Dimens.resultDeviceWidth))
        Title(Res.string.scripts_col_state, Modifier.width(Dimens.resultStateWidth))
        Title(Res.string.scripts_col_exit, Modifier.width(Dimens.resultExitWidth))
        Title(Res.string.scripts_col_time, Modifier.width(Dimens.resultTimeWidth))
        Title(Res.string.scripts_col_last, Modifier.weight(1f))
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
        RunState.RUNNING -> stringResource(Res.string.scripts_state_running) to Colors.accent
        RunState.DONE -> stringResource(Res.string.scripts_state_done) to Colors.online
        RunState.FAILED -> stringResource(Res.string.scripts_state_failed) to Colors.error
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
private fun Output(device: DeviceRun?, modifier: Modifier) {
    Column(modifier.background(Colors.toolbarBg)) {
        Text(
            if (device == null) stringResource(Res.string.scripts_pick_result) else stringResource(Res.string.scripts_output, device.name),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Colors.secondaryText,
            modifier = Modifier.padding(horizontal = Dimens.rowPadding, vertical = Dimens.liveDot),
        )
        if (device == null) return@Column
        val lines = device.output
        if (lines.isEmpty()) {
            Text(stringResource(Res.string.scripts_no_output), style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText, modifier = Modifier.padding(horizontal = Dimens.rowPadding))
            return@Column
        }
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

/** 850ms, 12.4s, 3m 04s — how long a run took, to the precision that matters at that length. */
private fun duration(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
    else -> "${ms / 60_000}m ${((ms / 1000) % 60).toString().padStart(2, '0')}s"
}
