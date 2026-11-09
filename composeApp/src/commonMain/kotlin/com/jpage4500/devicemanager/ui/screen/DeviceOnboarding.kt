package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * what the Devices page shows with nothing connected: the three ways a device gets here.
 *
 * one device looks exactly like many, so this is the only state the page has to explain itself in.
 * Pairing and reaching another computer both happen in the Connect window, which already does them;
 * the cards say how and open it, rather than growing a second pairing flow here.
 */
@Composable
fun DeviceOnboarding(onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageGutter)
            .clip(shape)
            .border(Dimens.hairline, Colors.sidebarBorder, shape)
            .background(Colors.listBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.pageGutter, vertical = Dimens.pageGutter + Dimens.rowPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.pageGutter),
    ) {
        Column(
            Modifier.widthIn(max = Dimens.onboardingWidth / 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.gap),
        ) {
            Text(
                stringResource(Res.string.onboard_title),
                fontSize = Dimens.pageTitleSize,
                fontWeight = FontWeight.Bold,
                color = Colors.primaryText,
            )
            Text(
                stringResource(Res.string.onboard_msg),
                style = MaterialTheme.typography.bodyMedium,
                color = Colors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }

        Row(
            Modifier.widthIn(max = Dimens.onboardingWidth).height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Dimens.rowPadding + Dimens.hairline * 2),
        ) {
            OnboardCard(
                icon = Res.drawable.android,
                title = Res.string.onboard_usb,
                subtitle = Res.string.onboard_usb_sub,
                steps = listOf(Res.string.onboard_usb_1, Res.string.onboard_usb_2, Res.string.onboard_usb_3),
                note = Res.string.onboard_usb_note,
                modifier = Modifier.weight(1f),
            )
            OnboardCard(
                icon = Res.drawable.network,
                title = Res.string.onboard_wifi,
                subtitle = Res.string.onboard_wifi_sub,
                steps = listOf(Res.string.onboard_wifi_1, Res.string.onboard_wifi_2, Res.string.onboard_wifi_3),
                action = Res.string.onboard_wifi_button,
                onAction = { WindowManager.openConnect() },
                modifier = Modifier.weight(1f),
            )
            OnboardCard(
                icon = Res.drawable.server,
                title = Res.string.onboard_remote,
                subtitle = Res.string.onboard_remote_sub,
                body = Res.string.onboard_remote_msg,
                action = Res.string.onboard_remote_button,
                onAction = { WindowManager.openConnect() },
                modifier = Modifier.weight(1f),
            )
        }

        // which server this is reading — the first thing to check when a plugged-in device doesn't
        // show — and the way to change it
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            val host = SettingsManager.getStringOrEmpty(AppPref.ADB_SERVER_HOST).ifBlank { "127.0.0.1" }
            val port = SettingsManager.getInt(AppPref.ADB_SERVER_PORT)
            Text(
                stringResource(Res.string.onboard_adb, "$host:$port"),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
            )
            Text("·", style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
            Text(
                stringResource(Res.string.onboard_settings),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.primaryText,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.plainClickable(onClick = onSettings),
            )
        }
    }
}

@Composable
private fun OnboardCard(
    icon: DrawableResource,
    title: StringResource,
    subtitle: StringResource,
    modifier: Modifier = Modifier,
    steps: List<StringResource> = emptyList(),
    body: StringResource? = null,
    note: StringResource? = null,
    action: StringResource? = null,
    onAction: () -> Unit = {},
) {
    val shape = RoundedCornerShape(Dimens.cardCorner + Dimens.hairline * 2)
    Column(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .border(Dimens.hairline, Colors.sidebarBorder, shape)
            .padding(Dimens.pageGutter - Dimens.liveDot),
        verticalArrangement = Arrangement.spacedBy(Dimens.rowPadding + Dimens.hairline * 2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(Dimens.onboardingCardIcon)
                    .clip(RoundedCornerShape(Dimens.navCorner))
                    .background(Colors.sidebarBg),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(icon, size = Dimens.navIcon, tint = Colors.toolbarIcon)
            }
            Spacer(Modifier.width(Dimens.navGap))
            Column {
                Text(stringResource(title), fontWeight = FontWeight.Bold, color = Colors.primaryText)
                Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
            }
        }
        steps.forEachIndexed { index, step ->
            Row {
                Text(
                    "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Colors.secondaryText,
                    modifier = Modifier.width(Dimens.navGap * 2),
                )
                Text(stringResource(step), style = MaterialTheme.typography.bodyMedium, color = Colors.primaryText)
            }
        }
        if (body != null) {
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = Colors.primaryText)
        }
        Spacer(Modifier.weight(1f))
        if (note != null) {
            Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
        }
        if (action != null) {
            OutlinedAction(stringResource(action), modifier = Modifier.fillMaxWidth(), onClick = onAction)
        }
    }
}
