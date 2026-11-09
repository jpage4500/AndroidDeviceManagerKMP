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
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.dialog.common.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlin.math.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * the Scripts page: the `.sh` files in the scripts folder, and running one across devices.
 *
 * A script runs once, with the serial of each device it runs on as `$1 $2 …` — see [ScriptManager].
 * Runs on starts at All devices; picking devices with + (or arriving from the Devices page's Run
 * script, with its selection) narrows it to those. A question a running script waits on comes up
 * as a prompt. Desktop only — see [ScriptHost].
 */
@Composable
fun ScriptsScreen() {
    val scripts by ScriptManager.scripts.collectAsState()
    val runs by ScriptManager.runs.collectAsState()
    val selectedName by ScriptManager.selected.collectAsState()
    val prompts by ScriptManager.prompts.collectAsState()
    var creating by remember { mutableStateOf(false) }
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
            PrimaryButton(Res.drawable.add, newName) { creating = true }
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

    if (creating) {
        NewScriptDialog(onDismiss = { creating = false }) { name ->
            ScriptHost.create(name)?.let { created ->
                ScriptManager.reload()
                ScriptManager.select(created.fileName)
                ScriptHost.edit(created)
            }
        }
    }
    prompts.firstOrNull()?.let { prompt -> key(prompt.id) { ScriptPromptDialog(prompt) } }
}

/** New script: its name — OK stays off until there is one. */
@Composable
private fun NewScriptDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val valid = name.isNotBlank()
    BaseDialog(
        title = stringResource(Res.string.scripts_new),
        icon = Res.drawable.script,
        okText = stringResource(Res.string.ok),
        cancelText = stringResource(Res.string.cancel),
        okEnabled = valid,
        onConfirm = { onCreate(name.trim()) },
        onDismiss = onDismiss,
    ) {
        AppTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.settingPadding),
            hint = stringResource(Res.string.scripts_new_name),
            focusRequester = focus,
            onSubmit = {
                if (valid) {
                    onCreate(name.trim())
                    onDismiss()
                }
            },
        )
        FocusOnShow(focus)
    }
}

/** a question a running script is waiting on; the answer goes to its stdin. */
@Composable
private fun ScriptPromptDialog(prompt: ScriptPrompt) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    BaseDialog(
        title = prompt.title,
        icon = Res.drawable.script,
        okText = stringResource(Res.string.scripts_prompt_send),
        cancelText = stringResource(Res.string.scripts_prompt_dismiss),
        onConfirm = { ScriptManager.answer(prompt, text) },
        onDismiss = { ScriptManager.dismiss(prompt) },
    ) {
        DialogMessage(prompt.text)
        Spacer(Modifier.height(Dimens.gap))
        AppTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.settingPadding),
            focusRequester = focus,
            visualTransformation = if (prompt.secret) PasswordVisualTransformation() else VisualTransformation.None,
            // an empty answer is a bare Enter — "press ENTER to continue", or take the default
            onSubmit = { ScriptManager.answer(prompt, text) },
        )
        FocusOnShow(focus)
    }
}

@Composable
private fun ScriptList(scripts: List<Script>, runs: Map<String, ScriptRun>, selected: String?, modifier: Modifier) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    // ticks so "Last run: 5m ago" ages while the page is open; a minute is its finest unit
    var now by remember { mutableStateOf(DateUtils.nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = DateUtils.nowMs()
        }
    }
    // a run that just ended reads "just now", not the age of the last tick
    LaunchedEffect(runs) { now = DateUtils.nowMs() }
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
                    runSummary(run, now),
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
private fun runSummary(run: ScriptRun?, now: Long): String = when {
    run == null -> stringResource(Res.string.scripts_never)
    run.running -> stringResource(Res.string.scripts_running)
    else -> stringResource(Res.string.scripts_last, ago(now - (run.devices.mapNotNull { it.endedMs }.maxOrNull() ?: run.startedMs)))
}

/** how long ago, to the coarsest unit that still says something: just now, 5m, 3h, 2d. */
@Composable
private fun ago(ms: Long): String {
    val mins = ms / 60_000
    return when {
        mins < 1 -> stringResource(Res.string.ago_now)
        mins < 60 -> stringResource(Res.string.ago_mins, mins.toInt())
        mins < 24 * 60 -> stringResource(Res.string.ago_hours, (mins / 60).toInt())
        else -> stringResource(Res.string.ago_days, (mins / (24 * 60)).toInt())
    }
}

/** one script: what runs it and on what, its source, and its last run's results. */
@Composable
private fun ScriptDetail(script: Script, run: ScriptRun?, modifier: Modifier) {
    val devices by DeviceManager.devices.collectAsState()
    val chosen by ScriptManager.chosen.collectAsState()
    val ready = devices.filter { it.isReady }
    // All devices is every ready one when Run is pressed; otherwise the ready ones picked
    val all by ScriptManager.all.collectAsState()
    val targets = runTargets(ready, chosen, all)
    val running = run?.running == true
    val copy = rememberClipboardCopy()
    val copied = stringResource(Res.string.scripts_copied)
    var source by remember { mutableStateOf<List<String>>(emptyList()) }
    var outputFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(script.path) {
        source = readLocalFile(script.path)?.decodeToString()?.lines()?.dropLastWhile { it.isEmpty() }.orEmpty()
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
            IconBox(Res.drawable.copy, stringResource(Res.string.copy)) {
                copy(source.joinToString("\n"))
                ToastManager.showToast(copied)
            }
            OutlinedAction(stringResource(Res.string.scripts_edit)) { ScriptHost.edit(script) }
            if (running) {
                OutlinedAction(stringResource(Res.string.scripts_stop)) { ScriptManager.stop(script) }
            } else {
                PrimaryButton(Res.drawable.play, stringResource(Res.string.scripts_run)) {
                    outputFor = null
                    ScriptManager.run(script, targets)
                }
            }
        }

        RunsOn(ready = ready, chosen = chosen, onChoose = ScriptManager::choose, all = all, onAll = ScriptManager::setAll)

        if (run == null) {
            // nothing to show under the source until the first run, so it gets the room
            SourceView(source, Modifier.weight(1f).fillMaxWidth())
            NeverRun(stringResource(Res.string.scripts_note))
        } else {
            SourceAndResults(
                source = source,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { resultsModifier ->
                RunResults(
                    devices = run.devices,
                    outputFor = outputFor,
                    onOutputFor = { outputFor = it },
                    // a script's run is one row: running it again is running it again
                    onRetry = { ScriptManager.run(script, targets) },
                    emptyTitle = stringResource(Res.string.scripts_never),
                    emptyDetail = stringResource(Res.string.scripts_note),
                    modifier = resultsModifier,
                    onSend = { rowId, text -> ScriptManager.send(script.fileName, rowId, text) },
                )
            }
        }
    }
}

/**
 * the source over the results, with a divider between them to drag — where it was left is
 * [AppPref.SCRIPT_SOURCE_PCT], shared by every script.
 */
@Composable
private fun SourceAndResults(source: List<String>, modifier: Modifier, results: @Composable (Modifier) -> Unit) {
    var fraction by remember { mutableStateOf(SettingsManager.getInt(AppPref.SCRIPT_SOURCE_PCT) / 100f) }
    BoxWithConstraints(modifier) {
        val total = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        Column(Modifier.fillMaxSize()) {
            SourceView(source, Modifier.weight(fraction).fillMaxWidth())
            HorizontalSplitter(
                onDrag = { delta -> fraction = (fraction + delta / total).coerceIn(MIN_SPLIT, 1f - MIN_SPLIT) },
                onDragStopped = { SettingsManager.setInt(AppPref.SCRIPT_SOURCE_PCT, (fraction * 100).roundToInt()) },
                modifier = Modifier.padding(vertical = Dimens.liveDot),
            )
            results(Modifier.weight(1f - fraction).fillMaxWidth())
        }
    }
}

// neither pane drags shut: a sliver of each stays to grab
private const val MIN_SPLIT = 0.12f

/**
 * the script's text, read-only — editing is the editor's job, and Open in editor is one click.
 *
 * one Text for the numbers and one for the lines, unwrapped: the two stay level however long a
 * line is, a long one scrolls sideways rather than pushing its number's neighbours down, and a
 * selection copies exactly what's in the file, with no numbers in it.
 */
@Composable
private fun SourceView(lines: List<String>, modifier: Modifier) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val numbers = remember(lines) { lines.indices.joinToString("\n") { "${it + 1}" } }
    val text = remember(lines) { lines.joinToString("\n") }
    Row(
        modifier
            .clip(shape)
            .border(Dimens.hairline, Colors.sidebarBorder, shape)
            .background(Colors.toolbarBg)
            .verticalScroll(rememberScrollState())
            .padding(vertical = Dimens.gap),
    ) {
        Text(
            numbers,
            style = style,
            color = Colors.secondaryText,
            textAlign = TextAlign.End,
            softWrap = false,
            modifier = Modifier.width(Dimens.lineNumberWidth),
        )
        Spacer(Modifier.width(Dimens.rowPadding))
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                text,
                style = style,
                color = Colors.primaryText,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(end = Dimens.rowPadding),
            )
        }
    }
}

/** the results' place before the first run: one line, not a pane. */
@Composable
private fun NeverRun(note: String) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(Dimens.hairline, Colors.sidebarBorder, shape)
            .background(Colors.listBg)
            .padding(horizontal = Dimens.rowPadding, vertical = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.scripts_never),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Colors.primaryText,
        )
        Text(
            " · $note",
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

