package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

/**
 * the Commands page: one `adb shell` command, run on every device it targets, with a row each.
 *
 * Ported from the Swing app's `CommandScreen` — a field, the commands run lately, and the named
 * ones in `commands.txt` — with the saved commands that were a dialog of their own here, and the
 * results table from Scripts in place of the message viewer. Targets devices the way Scripts does;
 * Commands on the Devices page arrives with its selection chosen. See [CommandManager].
 */
@Composable
fun CommandsScreen() {
    val devices by DeviceManager.devices.collectAsState()
    val chosen by CommandManager.chosen.collectAsState()
    val run by CommandManager.run.collectAsState()
    val saved by CommandStore.commands.collectAsState()
    val recent by CommandManager.recent.collectAsState()
    var fromFile by remember { mutableStateOf(emptyList<CustomCommand>()) }
    val ready = devices.filter { it.isReady }
    val targets = runTargets(ready, chosen)
    var command by remember { mutableStateOf(run?.command.orEmpty()) }
    // the saved command the field was loaded from, so Save… offers its name and replaces it
    var loaded by remember { mutableStateOf<String?>(null) }
    var outputFor by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf<InputRequest?>(null) }
    val focus = remember { FocusRequester() }
    val running = run?.running == true
    val saveTitle = stringResource(Res.string.commands_save_title)
    val renameTitle = stringResource(Res.string.commands_rename_title)

    // the file is read each time the page opens: it's edited outside the app
    LaunchedEffect(Unit) { fromFile = CommandStore.fromFile() }

    fun start(value: String) {
        if (targets.isEmpty() || running) return
        outputFor = null
        CommandManager.run(value, targets)
    }

    fun pick(value: String, name: String?) {
        command = value
        loaded = name
        runCatching { focus.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.pageHeaderHeight).padding(horizontal = Dimens.pageGutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.page_commands),
                fontSize = Dimens.pageTitleSize,
                fontWeight = FontWeight.Bold,
                color = Colors.primaryText,
            )
        }

        Row(
            Modifier.weight(1f).padding(start = Dimens.pageGutter, end = Dimens.pageGutter, bottom = Dimens.pageGutter),
            horizontalArrangement = Arrangement.spacedBy(Dimens.rowPadding),
        ) {
            CommandList(
                saved = saved,
                fromFile = fromFile,
                recent = recent,
                onPick = ::pick,
                onRun = { value ->
                    pick(value, null)
                    start(value)
                },
                onRename = { entry ->
                    input = InputRequest(renameTitle, entry.name) { name ->
                        if (name.isNotEmpty()) CommandStore.save(entry.copy(name = name), replacing = entry.name)
                    }
                },
                modifier = Modifier.width(Dimens.commandListWidth).fillMaxHeight(),
            )

            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Dimens.rowPadding)) {
                RunsOn(ready = ready, chosen = chosen, onChoose = CommandManager::choose, recent = RecentDevices.commands)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                    Text(
                        stringResource(Res.string.commands_prefix),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = Colors.secondaryText,
                    )
                    AppTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.weight(1f),
                        hint = stringResource(Res.string.commands_hint),
                        focusRequester = focus,
                        onSubmit = { start(command) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    OutlinedAction(stringResource(Res.string.commands_save_as), icon = Res.drawable.save) {
                        val value = CommandManager.sanitize(command)
                        if (value.isNotEmpty()) {
                            input = InputRequest(saveTitle, loaded.orEmpty()) { name ->
                                if (name.isNotEmpty()) {
                                    CommandStore.save(CustomCommand(name, value))
                                    loaded = name
                                }
                            }
                        }
                    }
                    if (running) {
                        OutlinedAction(stringResource(Res.string.commands_stop), icon = Res.drawable.stop) { CommandManager.stop() }
                    } else {
                        PrimaryButton(Res.drawable.play, stringResource(Res.string.commands_run)) { start(command) }
                    }
                }

                val current = run
                RunResults(
                    devices = current?.devices,
                    outputFor = outputFor,
                    onOutputFor = { outputFor = it },
                    onRetry = { ids -> current?.let { CommandManager.run(it.command, ids, retry = true) } },
                    emptyTitle = stringResource(Res.string.commands_never),
                    emptyDetail = stringResource(Res.string.commands_never_msg),
                    title = current?.let { stringResource(Res.string.commands_results, it.command) }
                        ?: stringResource(Res.string.run_results),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }

    FocusOnShow(focus)
    InputDialog(input) { input = null }
}

/** Saved, then `commands.txt`, then Recent — each section only when it has something in it. */
@Composable
private fun CommandList(
    saved: List<CustomCommand>,
    fromFile: List<CustomCommand>,
    recent: List<String>,
    onPick: (command: String, name: String?) -> Unit,
    onRun: (String) -> Unit,
    onRename: (CustomCommand) -> Unit,
    modifier: Modifier,
) {
    if (saved.isEmpty() && fromFile.isEmpty() && recent.isEmpty()) {
        Box(modifier.pageCard()) {
            EmptyState(stringResource(Res.string.page_commands), stringResource(Res.string.commands_list_empty))
        }
        return
    }
    val rename = stringResource(Res.string.commands_rename)
    val delete = stringResource(Res.string.commands_delete)
    val forget = stringResource(Res.string.commands_forget)
    LazyColumn(modifier.pageCard().padding(Dimens.liveDot)) {
        item {
            Text(
                stringResource(Res.string.commands_list_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Colors.secondaryText,
                modifier = Modifier.padding(horizontal = Dimens.gap, vertical = Dimens.liveDot),
            )
        }
        if (saved.isNotEmpty()) {
            item { SectionTitle(stringResource(Res.string.commands_saved)) }
            items(saved, key = { "saved:${it.name}" }) { entry ->
                CommandRow(entry.name, entry.command, onClick = { onPick(entry.command, entry.name) }, onDoubleClick = { onRun(entry.command) }) {
                    RowIcon(Res.drawable.edit, rename) { onRename(entry) }
                    RowIcon(Res.drawable.trash, delete) { CommandStore.delete(entry.name) }
                }
            }
        }
        if (fromFile.isNotEmpty()) {
            item { SectionTitle(stringResource(Res.string.commands_file)) }
            items(fromFile, key = { "file:${it.name}" }) { entry ->
                CommandRow(entry.name, entry.command, onClick = { onPick(entry.command, null) }, onDoubleClick = { onRun(entry.command) })
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle(stringResource(Res.string.commands_recent)) }
            items(recent, key = { "recent:$it" }) { value ->
                CommandRow(null, value, onClick = { onPick(value, null) }, onDoubleClick = { onRun(value) }) {
                    RowIcon(Res.drawable.trash, forget) { CommandManager.forget(value) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Colors.secondaryText,
        modifier = Modifier.padding(start = Dimens.gap, end = Dimens.gap, top = Dimens.rowPadding, bottom = Dimens.liveDot),
    )
}

/** a command, under its name if it has one; its actions show while the pointer is over it. */
@Composable
private fun CommandRow(
    name: String?,
    command: String,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.navCorner))
            .background(if (hovered) Colors.navSelected else Color.Transparent)
            .hoverable(interaction)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onDoubleClick = onDoubleClick)
            .padding(horizontal = Dimens.gap, vertical = Dimens.liveDot)
            // room for a RowIcon (icon plus its padding) always, so the actions showing on hover don't grow the row
            .heightIn(min = Dimens.icon + Dimens.liveDot * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (name != null) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = if (name != null) Colors.secondaryText else Colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hovered) actions()
    }
}

@Composable
private fun RowIcon(icon: DrawableResource, label: String, onClick: () -> Unit) {
    CellTooltip(text = { label }) {
        AppIcon(
            icon,
            size = Dimens.icon,
            tint = Colors.secondaryText,
            contentDescription = label,
            modifier = Modifier.clip(RoundedCornerShape(Dimens.navCorner)).clickable(onClick = onClick).padding(Dimens.liveDot),
        )
    }
}
