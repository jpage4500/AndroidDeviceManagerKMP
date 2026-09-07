package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.stringResource

/**
 * the user's own adb commands: the saved list, and a form to add to it.
 *
 * Ported from the Swing app's `CommandScreen`. Editing in the same dialog as the list rather than a
 * second one — there are only two fields, and a list you can't edit in place is a list you stop
 * curating.
 */
@Composable
fun CommandDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val commands by CommandStore.commands.collectAsState()
    var editing by remember { mutableStateOf<CustomCommand?>(null) }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }

    fun startEdit(entry: CustomCommand?) {
        editing = entry
        name = entry?.name.orEmpty()
        command = entry?.command.orEmpty()
    }

    AppDialog(
        title = stringResource(Res.string.commands_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(if (editing == null) Res.string.commands_add else Res.string.commands_save),
        confirmEnabled = name.isNotBlank() && command.isNotBlank(),
        onConfirm = {
            CommandStore.save(CustomCommand(name.trim(), command.trim()), replacing = editing?.name)
            startEdit(null)
        },
        modifier = Modifier.width(460.dp),
    ) {
            Column {
                if (commands.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.commands_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = Colors.secondaryText,
                    )
                } else {
                    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        commands.forEach { entry ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .plainClickable { startEdit(entry) }
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, style = MaterialTheme.typography.bodySmall, color = Colors.primaryText)
                                    Text(entry.command, style = MaterialTheme.typography.labelSmall, color = Colors.secondaryText, maxLines = 1)
                                }
                                AppIcon(
                                    res = Res.drawable.trash,
                                    size = Dimens.icon,
                                    tint = Colors.secondaryText,
                                    modifier = Modifier.plainClickable {
                                        CommandStore.delete(entry.name)
                                        if (editing?.name == entry.name) startEdit(null)
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(Dimens.gapLarge))
                HorizontalDivider(color = Colors.divider)
                Spacer(Modifier.height(Dimens.gapLarge))

                FieldLabel(stringResource(Res.string.commands_name))
                AppTextField(name, { name = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Dimens.gap))
                FieldLabel(stringResource(Res.string.commands_command))
                AppTextField(command, { command = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Dimens.gap))
                Text(
                    text = stringResource(Res.string.commands_help),
                    style = MaterialTheme.typography.labelSmall,
                    color = Colors.secondaryText,
                )
            }
    }
}
