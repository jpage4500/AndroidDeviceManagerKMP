package com.jpage4500.devicemanager.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.dialog.common.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.stringResource

/**
 * is there a newer build — a spinner, then one of three answers.
 *
 * Nothing is downloaded here: see [UpdateManager] for why the offer is a link to the release page.
 */
@Composable
fun CheckUpdatesDialog(onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<UpdateManager.Result?>(null) }
    LaunchedEffect(Unit) { result = UpdateManager.checkForUpdates() }

    when (val outcome = result) {
        null -> BaseDialog(
            titleRes = Res.string.about_checking,
            icon = Res.drawable.update,
            onDismiss = onDismiss,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(Dimens.gapLarge),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is UpdateManager.Result.UpToDate -> ConfirmationDialog(
            title = stringResource(Res.string.about_up_to_date_title),
            message = stringResource(Res.string.about_up_to_date, outcome.version),
            icon = Res.drawable.update,
            cancelText = null,
            onDismiss = onDismiss,
        )

        is UpdateManager.Result.Available -> ConfirmationDialog(
            title = stringResource(Res.string.about_update_title),
            message = stringResource(Res.string.about_update_available, outcome.version),
            icon = Res.drawable.update,
            // a release with no page to point at is a broken release, but it shouldn't leave the
            // dialog offering a button that does nothing
            okText = outcome.url.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.about_open) },
            onDismiss = onDismiss,
            onConfirm = { PlatformUtils.openUrl(outcome.url) },
        )

        is UpdateManager.Result.Failed -> ConfirmationDialog(
            title = stringResource(Res.string.about_updates),
            message = stringResource(Res.string.about_update_failed, outcome.message),
            icon = Res.drawable.error,
            cancelText = null,
            onDismiss = onDismiss,
        )
    }
}
