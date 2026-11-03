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
 * the devices picked with +, which start from the Devices page's selection; Run script there arrives
 * here with its selection already chosen. Desktop only — see [ScriptHost].
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
    val targets = runTargets(ready, chosen)
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

        RunsOn(ready = ready, chosen = chosen, onChoose = ScriptManager::choose)

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

        RunResults(
            devices = run?.devices,
            outputFor = outputFor,
            onOutputFor = { outputFor = it },
            onRetry = { ScriptManager.run(script, it, retry = true) },
            emptyTitle = stringResource(Res.string.scripts_never),
            emptyDetail = stringResource(Res.string.scripts_note),
            modifier = Modifier.weight(0.65f).fillMaxWidth(),
        )
    }
}

