package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** a key the Input page sends, and what its button says. */
private class KeyButton(val code: Int, val label: StringResource)

// in groups, a gap between each: getting around, editing, the arrows, the hardware buttons
private val KEY_GROUPS = listOf(
    listOf(
        KeyButton(DeviceInput.KEY_BACK, Res.string.key_back),
        KeyButton(DeviceInput.KEY_HOME, Res.string.key_home),
        KeyButton(DeviceInput.KEY_APP_SWITCH, Res.string.key_app_switch),
    ),
    listOf(
        KeyButton(DeviceInput.KEY_ENTER, Res.string.input_enter),
        KeyButton(DeviceInput.KEY_TAB, Res.string.key_tab),
        KeyButton(DeviceInput.KEY_DEL, Res.string.input_delete),
        KeyButton(DeviceInput.KEY_ESCAPE, Res.string.key_escape),
    ),
    listOf(
        KeyButton(DeviceInput.KEY_DPAD_LEFT, Res.string.key_left),
        KeyButton(DeviceInput.KEY_DPAD_UP, Res.string.key_up),
        KeyButton(DeviceInput.KEY_DPAD_DOWN, Res.string.key_down),
        KeyButton(DeviceInput.KEY_DPAD_RIGHT, Res.string.key_right),
    ),
    listOf(
        KeyButton(DeviceInput.KEY_POWER, Res.string.key_power),
        KeyButton(DeviceInput.KEY_WAKEUP, Res.string.key_wake),
        KeyButton(DeviceInput.KEY_VOLUME_DOWN, Res.string.key_volume_down),
        KeyButton(DeviceInput.KEY_VOLUME_UP, Res.string.key_volume_up),
    ),
)

/**
 * the Input page: type text, or press a key, on every device it targets at once.
 *
 * Ported from the Swing app's `InputScreen` — the text field, Password, and Recent — and from the
 * Type text dialog that stood in for it, whose keys it keeps. Targets devices the way Scripts does:
 * the devices picked with +, starting from the Devices page's selection; Type text there arrives
 * with its selection chosen. See [InputManager].
 */
@Composable
fun InputScreen() {
    val devices by DeviceManager.devices.collectAsState()
    val chosen by InputManager.chosen.collectAsState()
    val recent by InputManager.recent.collectAsState()
    val last by InputManager.last.collectAsState()
    val ready = devices.filter { it.isReady }
    val targets = runTargets(ready, chosen)
    var text by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val enterLabel = stringResource(Res.string.input_enter)

    // an empty field sends Enter, as the Swing window did — so a form can be typed and submitted
    // without leaving the keyboard
    fun send() {
        if (text.isEmpty()) {
            InputManager.sendKey(DeviceInput.KEY_ENTER, enterLabel, targets)
        } else {
            InputManager.sendText(text, password, targets)
            text = ""
        }
        runCatching { focus.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.pageHeaderHeight).padding(horizontal = Dimens.pageGutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.page_input),
                fontSize = Dimens.pageTitleSize,
                fontWeight = FontWeight.Bold,
                color = Colors.primaryText,
            )
        }

        Column(
            Modifier.weight(1f).padding(start = Dimens.pageGutter, end = Dimens.pageGutter, bottom = Dimens.pageGutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.rowPadding),
        ) {
            RunsOn(
                ready = ready,
                chosen = chosen,
                onChoose = InputManager::choose,
                label = stringResource(Res.string.input_types_on),
                recent = RecentDevices.input,
            )

            Column(Modifier.fillMaxWidth().pageCard().padding(Dimens.rowPadding)) {
                FieldLabel(stringResource(Res.string.input_text))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                    AppTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        hint = stringResource(Res.string.input_hint),
                        focusRequester = focus,
                        onSubmit = ::send,
                        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    )
                    PrimaryButton(Res.drawable.keyboard, stringResource(Res.string.input_send), ::send)
                }
                Spacer(Modifier.height(Dimens.gap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppCheckbox(password, stringResource(Res.string.input_password)) { password = it }
                    Spacer(Modifier.weight(1f))
                    SendStatus(last, noTargets = targets.isEmpty())
                }

                Spacer(Modifier.height(Dimens.rowPadding))
                FieldLabel(stringResource(Res.string.input_keys))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.rowPadding),
                ) {
                    KEY_GROUPS.forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.liveDot)) {
                            group.forEach { key ->
                                val label = stringResource(key.label)
                                OutlinedAction(label) { InputManager.sendKey(key.code, label, targets) }
                            }
                        }
                    }
                }
            }

            RecentInputs(
                recent = recent,
                onPick = {
                    text = it.text
                    password = it.isPassword
                    runCatching { focus.requestFocus() }
                },
                onSend = { InputManager.sendText(it.text, it.isPassword, targets) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }

    FocusOnShow(focus)
}

/** "hello · sent to 11 of 12 devices · 1 failed", or what's stopping a send. */
@Composable
private fun SendStatus(last: InputSend?, noTargets: Boolean) {
    val (message, failed) = when {
        noTargets -> stringResource(Res.string.input_none_ready) to true
        last == null -> return
        last.running -> stringResource(Res.string.input_sending, last.what, last.total) to false
        last.failed > 0 -> stringResource(Res.string.input_sent, last.what, last.done, last.total) + " · " +
            stringResource(Res.string.input_failed, last.failed) to true
        else -> stringResource(Res.string.input_sent, last.what, last.done, last.total) to false
    }
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (failed) Colors.error else Colors.secondaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** text sent lately: a click puts it back in the field, a double-click sends it again. */
@Composable
private fun RecentInputs(
    recent: List<RecentInput>,
    onPick: (RecentInput) -> Unit,
    onSend: (RecentInput) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.pageCard()) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.headerHeight).background(Colors.tableHeaderBg).padding(horizontal = Dimens.rowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.input_recent),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Colors.tableHeaderText,
                modifier = Modifier.weight(1f),
            )
            if (recent.isNotEmpty()) {
                Text(stringResource(Res.string.input_recent_hint), style = MaterialTheme.typography.labelSmall, color = Colors.secondaryText)
            }
        }
        HorizontalDivider(color = Colors.tableRule)
        if (recent.isEmpty()) {
            EmptyState(stringResource(Res.string.input_recent), stringResource(Res.string.input_recent_empty))
            return@Column
        }
        val forget = stringResource(Res.string.input_forget)
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(recent, key = { _, it -> it.text }) { index, entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(Dimens.tableRowHeight)
                        .background(if (index % 2 == 1) Colors.tableHeaderBg else Colors.listBg)
                        .combinedClickable(onClick = { onPick(entry) }, onDoubleClick = { onSend(entry) })
                        .padding(horizontal = Dimens.rowPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (entry.isPassword) {
                        AppIcon(Res.drawable.eye_closed, size = Dimens.icon, tint = Colors.secondaryText)
                        Spacer(Modifier.width(Dimens.gap))
                    }
                    Text(
                        InputManager.mask(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = Colors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CellTooltip(text = { forget }) {
                        AppIcon(
                            Res.drawable.trash,
                            size = Dimens.icon,
                            tint = Colors.secondaryText,
                            contentDescription = forget,
                            modifier = Modifier.clip(RoundedCornerShape(Dimens.navCorner))
                                .clickable { InputManager.forget(entry) }
                                .padding(Dimens.liveDot),
                        )
                    }
                }
            }
        }
    }
}

/** the bordered white card every page's panels sit in. */
@Composable
internal fun Modifier.pageCard(): Modifier {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    return clip(shape).border(Dimens.hairline, Colors.sidebarBorder, shape).background(Colors.listBg)
}
