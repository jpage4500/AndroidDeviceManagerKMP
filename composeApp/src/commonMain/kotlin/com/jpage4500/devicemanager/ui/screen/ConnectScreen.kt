package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

/**
 * connect to a device over the network.
 *
 * Ported from the Swing app's `ConnectDialog`: the devices this app has reached before, each with
 * its live state, plus a field for one it hasn't. The address is the *last* one connected to, with
 * its final octet selected — so reaching the phone next to the last one is four keystrokes.
 *
 * The QR-code pairing the Swing app also offers needs SPAKE2 over mDNS, which has no pure-Kotlin
 * implementation — see docs/ADB-TRANSPORTS.md. A device with `adb tcpip 5555` already on is reached
 * by the field below.
 */
@Composable
fun ConnectScreen(onClose: () -> Unit) {
    val devices by DeviceManager.devices.collectAsState()
    val recent by WirelessManager.recent.collectAsState()
    val scope = rememberCoroutineScope()

    val last = remember { WirelessManager.lastAddress() }
    var host by remember { mutableStateOf(TextFieldValue(last?.host ?: DEFAULT_HOST)) }
    var port by remember { mutableStateOf((last?.port ?: WirelessDevice.DEFAULT_PORT).toString()) }
    var busy by remember { mutableStateOf(false) }
    val hostFocus = remember { FocusRequester() }

    // a remembered device is "connected" when the live list holds its address
    val connected = remember(devices) { devices.filter { it.isOnline }.map { it.serial }.toSet() }

    fun connect(atHost: String, atPort: Int) {
        if (busy) return
        busy = true
        scope.launch {
            DeviceManager.connect(atHost, atPort)
                .onSuccess { WirelessManager.add(atHost, atPort) }
                .onFailure { ToastManager.showToast(it.message ?: "Couldn't connect", success = false) }
            busy = false
        }
    }

    Column(Modifier.width(520.dp).padding(Dimens.gapLarge)) {
        Text(stringResource(Res.string.connect_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Dimens.gapLarge))

        if (recent.isEmpty()) {
            Text(
                text = stringResource(Res.string.connect_none_yet),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(Dimens.corner))
                    .background(Colors.listBg)
            ) {
                LazyColumn {
                    items(recent.asReversed(), key = { it.serial }) { entry ->
                        RecentRow(
                            entry = entry,
                            connected = entry.serial in connected,
                            busy = busy,
                            onConnect = { connect(entry.host, entry.port) },
                            onDisconnect = { scope.launch { DeviceManager.disconnect(entry.serial) } },
                            onForget = { WirelessManager.forget(entry.serial) },
                        )
                        HorizontalDivider(color = Colors.divider)
                    }
                }
            }
        }

        Spacer(Modifier.height(Dimens.gapLarge))
        HorizontalDivider(color = Colors.divider)
        Spacer(Modifier.height(Dimens.gapLarge))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.connect_ip), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(Dimens.gap))
            AddressField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.weight(1f).focusRequester(hostFocus),
                onSubmit = { connect(host.text.trim(), port.toIntOrNull() ?: WirelessDevice.DEFAULT_PORT) },
            )
            Spacer(Modifier.width(Dimens.gapLarge))
            Text(stringResource(Res.string.connect_port), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(Dimens.gap))
            AddressField(
                value = TextFieldValue(port),
                // 5 digits, digits only — a port can't be anything else
                onValueChange = { port = it.text.filter { c -> c.isDigit() }.take(5) },
                modifier = Modifier.width(70.dp),
                onSubmit = { connect(host.text.trim(), port.toIntOrNull() ?: WirelessDevice.DEFAULT_PORT) },
            )
            Spacer(Modifier.width(Dimens.gapLarge))
            Button(
                onClick = { connect(host.text.trim(), port.toIntOrNull() ?: WirelessDevice.DEFAULT_PORT) },
                enabled = !busy && host.text.isNotBlank() && port.isNotBlank(),
            ) {
                Text(stringResource(Res.string.connect))
            }
        }

        Spacer(Modifier.height(Dimens.gapLarge))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(stringResource(Res.string.close)) }
        }
    }

    // focus the address and select its last octet, so only that has to be retyped
    LaunchedEffect(Unit) {
        runCatching { hostFocus.requestFocus() }
        val dot = host.text.lastIndexOf('.')
        if (dot >= 0) host = host.copy(selection = TextRange(dot + 1, host.text.length))
    }
}

@Composable
private fun RecentRow(
    entry: WirelessDevice,
    connected: Boolean,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Dimens.touchRowHeight / 2 + Dimens.gap)
                .contextClickable { menuOpen = true }
                .padding(horizontal = Dimens.rowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                res = Res.drawable.device_remote,
                size = Dimens.icon,
                tint = if (connected) Colors.online else Colors.offline,
            )
            Spacer(Modifier.width(Dimens.gap))
            Column(Modifier.weight(1f)) {
                Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, color = Colors.primaryText)
                if (entry.name != null) {
                    Text(entry.serial, style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
                }
            }
            TextButton(onClick = if (connected) onDisconnect else onConnect, enabled = !busy) {
                Text(
                    stringResource(if (connected) Res.string.disconnect else Res.string.connect),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        AppPopupMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Item(stringResource(Res.string.remove)) { onForget() }
        }
    }
}

/** a bare single-line field, styled like the rest of the chrome rather than like Material. */
@Composable
private fun AddressField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = Colors.primaryText),
        cursorBrush = SolidColor(Colors.primaryText),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.corner))
            .background(Colors.listBg)
            .padding(horizontal = Dimens.gap, vertical = 6.dp),
    )
}

private const val DEFAULT_HOST = "192.168.0.100"
