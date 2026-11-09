package com.jpage4500.devicemanager.ui.dialog.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.style.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

/** are you sure — for the handful of actions that can't be undone. */
@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    icon: DrawableResource? = Res.drawable.about,
    okText: String? = stringResource(Res.string.ok),
    cancelText: String? = stringResource(Res.string.cancel),
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)? = null,
) {
    BaseDialog(
        title = title,
        icon = icon,
        okText = okText,
        cancelText = cancelText,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    ) {
        DialogMessage(message)
    }
}

/** body copy inside a dialog — a message, a description above a field, a warning. */
@Composable
fun DialogMessage(text: String, textAlign: TextAlign = TextAlign.Start) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.settingPadding),
        style = MaterialTheme.typography.bodyMedium,
        fontSize = Dimens.dialogTextSize,
        textAlign = textAlign,
        color = Colors.primaryText,
    )
}
