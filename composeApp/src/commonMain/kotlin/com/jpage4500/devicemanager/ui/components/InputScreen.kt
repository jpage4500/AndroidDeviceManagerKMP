package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

/**
 * type text, or send a key, to one or more devices at once.
 *
 * Ported from the Swing app's `InputScreen`. Typing onto **several** devices is the point — it is
 * how a bench of 45 gets the same URL or the same login without 45 taps.
 *
 * Recent entries are kept so the same string can be sent again without retyping.
 */
@Composable
fun InputDialog(deviceIds: List<String>, onDismiss: () -> Unit) {
    if (deviceIds.isEmpty()) return
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val recent = remember { SettingsManager.getStringOrEmpty(AppPref.RECENT_INPUT).split('\n').filter { it.isNotBlank() } }
    val focus = remember { FocusRequester() }

    fun send(value: String) {
        if (value.isBlank()) return
        rememberRecent(value)
        scope.launch {
            deviceIds.forEach { id -> DeviceManager.runCommand(id, DeviceInput.shell(DeviceInput.Text(value)), "Typing on") }
        }
        onDismiss()
    }

    fun key(code: Int, label: String) {
        scope.launch {
            deviceIds.forEach { id -> DeviceManager.runCommand(id, DeviceInput.shell(DeviceInput.Key(code)), "$label on") }
        }
    }

    AppDialog(
        title = stringResource(Res.string.input_title, deviceIds.size),
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.input_send),
        confirmEnabled = text.isNotBlank(),
        onConfirm = { send(text) },
    ) {
            Column {
                FieldLabel(stringResource(Res.string.input_text))
                AppTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = focus,
                    onSubmit = { send(text) },
                )
                Spacer(Modifier.height(Dimens.gap))

                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gap / 2)) {
                    KeyButton(stringResource(Res.string.key_back)) { key(DeviceInput.KEY_BACK, "Back") }
                    KeyButton(stringResource(Res.string.key_home)) { key(DeviceInput.KEY_HOME, "Home") }
                    KeyButton(stringResource(Res.string.input_enter)) { key(DeviceInput.KEY_ENTER, "Enter") }
                    KeyButton(stringResource(Res.string.input_delete)) { key(DeviceInput.KEY_DEL, "Delete") }
                }

                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(Dimens.gapLarge))
                    Text(
                        text = stringResource(Res.string.input_recent),
                        style = MaterialTheme.typography.labelSmall,
                        color = Colors.secondaryText,
                    )
                    LazyColumn(Modifier.heightIn(max = 120.dp)) {
                        items(recent) { entry ->
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.bodySmall,
                                color = Colors.primaryText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .plainClickable { text = entry }
                                    .padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            FocusOnShow(focus)
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) = DialogButton(label, onClick = onClick)

/** keep the last few strings typed, newest first, so the same one can be sent again. */
private fun rememberRecent(value: String) {
    val recent = SettingsManager.getStringOrEmpty(AppPref.RECENT_INPUT)
        .split('\n')
        .filter { it.isNotBlank() && it != value }
    SettingsManager.setString(AppPref.RECENT_INPUT, (listOf(value) + recent).take(MAX_RECENT).joinToString("\n"))
}

private const val MAX_RECENT = 10
