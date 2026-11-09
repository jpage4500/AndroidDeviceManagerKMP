package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * the Swing app's `ShareServerDialog`: start and stop Share Devices, see where it can be reached,
 * hand a phone the way in, and see who has come.
 *
 * Laid out as the Swing one is — a column of labelled values, the client table, Start/Stop at the
 * bottom. Name, port and token are editable only while stopped, and while running they show what
 * it is running with.
 *
 * **One difference: the address is picked here, not in a prompt.** The Swing dialog asked which
 * address to use every time QR Code or Copy was pressed. Here each address is a row with a radio
 * button, the LAN one already chosen — the only one a phone on the same Wi-Fi can reach, and the
 * one a VPN-heavy laptop lists last.
 */
@Composable
fun ShareDialog(onDismiss: () -> Unit) {
    val state by ShareServer.state.collectAsState()
    val clients by ShareServer.clients.collectAsState()
    val scope = rememberCoroutineScope()
    val copy = rememberClipboardCopy()

    var name by remember { mutableStateOf(ShareServer.savedName().orEmpty()) }
    var computerName by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(ShareServer.savedPort().toString()) }
    var token by remember { mutableStateOf(ShareServer.savedOrNewToken()) }
    // null while the interfaces are being read — a reverse lookup each, which can take seconds
    var networks by remember { mutableStateOf<List<LocalNetwork>?>(null) }
    var host by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var qrFor by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    val running = state.isRunning
    val stopped = state.status == ShareStatus.STOPPED
    val copiedText = stringResource(Res.string.share_copied)

    LaunchedEffect(Unit) {
        computerName = ShareHost.hostName().orEmpty()
        val list = ShareHost.networks()
        networks = list
        host = (list.firstOrNull { it.isLocalNetwork } ?: list.firstOrNull())?.ip
    }
    // while running, the fields say what it is running with rather than what was last typed
    LaunchedEffect(state) {
        if (running) {
            port = state.port.toString()
            token = state.token
        }
    }

    fun toggle() {
        error = null
        when (state.status) {
            ShareStatus.RUNNING -> scope.launch { ShareServer.stop() }
            ShareStatus.STOPPED -> {
                val number = port.toIntOrNull()
                if (number == null || number !in MIN_PORT..MAX_PORT) {
                    scope.launch { error = getString(Res.string.share_port_invalid) }
                    return
                }
                // blank asks for a new token, as in the Swing dialog
                val key = token.trim().ifEmpty { ShareServer.newToken() }
                token = key
                ShareServer.saveName(name)
                scope.launch {
                    ShareServer.start(number, key)?.let { error = getString(Res.string.share_failed, it) }
                }
            }
            // a start or stop already in flight; the button is disabled for it anyway
            else -> Unit
        }
    }

    DialogContainer(title = stringResource(Res.string.share_title), onDismiss = onDismiss, onEnter = null) {
        // scrolls once the window hits its height cap — a laptop on three VPNs lists an address a row
        Box(Modifier.width(Dimens.shareDialogWidth).background(Colors.menuBg)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(Dimens.gapLarge),
                verticalArrangement = Arrangement.spacedBy(Dimens.gap),
            ) {
                LabelledRow(Res.string.share_status) {
                    val (label, color) = when (state.status) {
                        ShareStatus.RUNNING -> Res.string.share_running to Colors.online
                        ShareStatus.STOPPED -> Res.string.share_stopped to Colors.error
                        ShareStatus.STARTING -> Res.string.share_starting to Colors.busy
                        ShareStatus.STOPPING -> Res.string.share_stopping to Colors.busy
                    }
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                    )
                }

                LabelledRow(Res.string.share_name) {
                    if (stopped) {
                        AppTextField(
                            value = name,
                            onValueChange = { name = it.take(MAX_NAME) },
                            modifier = Modifier.fillMaxWidth(),
                            // blank means the computer's own name, which is shown rather than typed in
                            hint = computerName,
                            onSubmit = ::toggle,
                        )
                    } else {
                        ValueBox(state.name)
                    }
                }

                LabelledRow(Res.string.share_host, alignTop = true) {
                    NetworkList(networks, host, onPick = { host = it })
                }

                LabelledRow(Res.string.share_port) {
                    if (stopped) {
                        AppTextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                            modifier = Modifier.fillMaxWidth(),
                            onSubmit = ::toggle,
                        )
                    } else {
                        ValueBox(port)
                    }
                }

                LabelledRow(Res.string.share_token) {
                    if (stopped) {
                        AppTextField(
                            value = token,
                            onValueChange = { token = it.filter { c -> c.isLetterOrDigit() }.take(MAX_TOKEN) },
                            modifier = Modifier.fillMaxWidth(),
                            hint = stringResource(Res.string.share_token_hint),
                            onSubmit = ::toggle,
                        )
                    } else {
                        ValueBox(token)
                    }
                }

                LabelledRow(null) {
                    val chosen = host
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapLarge)) {
                        DialogButton(
                            label = stringResource(Res.string.share_qr),
                            icon = Res.drawable.qr_code,
                            enabled = running && chosen != null,
                        ) {
                            if (chosen != null) qrFor = chosen
                        }
                        DialogButton(
                            label = stringResource(Res.string.share_copy),
                            icon = Res.drawable.copy,
                            enabled = running && chosen != null,
                        ) {
                            if (chosen != null) {
                                copy(ShareServer.connectionString(chosen))
                                ToastManager.showToast(copiedText, success = true)
                            }
                        }
                    }
                }

                Text(
                    stringResource(Res.string.share_clients),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Colors.primaryText,
                    modifier = Modifier.padding(top = Dimens.gap),
                )
                ClientTable(clients)

                Text(
                    text = error ?: state.error?.let { stringResource(Res.string.share_failed, it) } ?: " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DialogButton(
                        label = stringResource(if (state.status == ShareStatus.STOPPED) Res.string.share_start else Res.string.share_stop),
                        primary = stopped,
                        enabled = stopped || running,
                        onClick = ::toggle,
                    )
                }
            }
            Box(Modifier.matchParentSize()) {
                AppVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }

    qrFor?.let { chosen ->
        ShareQrDialog(
            connectionString = ShareServer.connectionString(chosen),
            address = chosen,
            onDismiss = { qrFor = null },
        )
    }
}

/** a QR of this server's connection string, and the string under it for anyone without a camera. */
@Composable
private fun ShareQrDialog(connectionString: String, address: String, onDismiss: () -> Unit) {
    DialogContainer(title = stringResource(Res.string.share_qr_title), onDismiss = onDismiss, onEnter = onDismiss) {
        Column(
            Modifier
                .width(Dimens.qrDialogWidth)
                .background(Colors.menuBg)
                .padding(Dimens.gapLarge * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.share_qr_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Colors.primaryText,
            )
            Spacer(Modifier.height(Dimens.gapLarge * 2))
            // white behind it in either theme: a camera reads dark modules on light, not the reverse
            Box(
                Modifier
                    .size(Dimens.qrSize)
                    .border(Dimens.hairline * 2, Colors.divider)
                    .background(Color.White)
                    .padding(Dimens.gap),
            ) {
                QrImage(connectionString, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(Dimens.gapLarge))
            // selectable, so it can be copied from here as well
            SelectionContainer {
                Text(
                    text = connectionString,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Colors.secondaryText,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Dimens.gapLarge))
            Text(
                text = stringResource(Res.string.share_qr_note, address),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = Colors.secondaryText,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimens.gapLarge))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                DialogButton(stringResource(Res.string.close), onClick = onDismiss)
            }
        }
    }
}

/** a label on the left, right-aligned in a fixed column as the Swing dialog's MigLayout has it. */
@Composable
private fun LabelledRow(label: StringResource?, alignTop: Boolean = false, content: @Composable () -> Unit) {
    Row(verticalAlignment = if (alignTop) Alignment.Top else Alignment.CenterVertically) {
        Text(
            text = label?.let { stringResource(it) }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = Colors.primaryText,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(Dimens.shareLabelWidth)
                .padding(end = Dimens.gapLarge, top = if (alignTop) Dimens.gap else 0.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

/** a value that can't be changed right now, selectable so the token can still be copied out. */
@Composable
private fun ValueBox(text: String) {
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(Colors.headerBackground)
                .padding(horizontal = Dimens.gap, vertical = Dimens.gap),
        )
    }
}

/** every address, one a row; the one picked is what QR Code and Copy hand out. */
@Composable
private fun NetworkList(networks: List<LocalNetwork>?, picked: String?, onPick: (String) -> Unit) {
    if (networks == null || networks.isEmpty()) {
        ValueBox(stringResource(if (networks == null) Res.string.share_fetching else Res.string.share_no_network))
        return
    }
    Column(Modifier.selectableGroup()) {
        networks.forEach { network ->
            val selected = network.ip == picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton) { onPick(network.ip) }
                    .padding(vertical = Dimens.hairline * 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)    // the row handles the click
                Spacer(Modifier.width(Dimens.gap))
                Text(
                    text = describe(network),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) Colors.primaryText else Colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** `192.168.0.10 (mymac.lan) - Wi-Fi (en0)` — the Swing dialog's line for an address. */
private fun describe(network: LocalNetwork): String = buildString {
    append(network.ip)
    if (!network.host.isNullOrEmpty()) append(" (").append(network.host).append(")")
    append(" - ").append(network.typeDesc)
}

@Composable
private fun ClientTable(clients: List<ShareClient>) {
    val widths = remember { mutableStateMapOf<ClientColumn, Dp>().apply { ClientColumn.entries.forEach { put(it, it.width) } } }
    val headers = ClientColumn.entries.associateWith { stringResource(it.label) }
    var selection by remember { mutableStateOf(TableSelection<Any?>()) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(Dimens.shareClientsHeight)
            .border(Dp.Hairline, Colors.divider)
    ) {
        DataTable(
            rows = clients,
            columns = ClientColumn.entries,
            widths = widths,
            sortedBy = null,
            sortAscending = true,
            selection = selection,
            rowKey = { it.ip },
            header = { headers.getValue(it) },
            onSelectionChange = { selection = it },
            onResize = { column, width -> widths[column] = width },
        ) { client, column ->
            when (column) {
                ClientColumn.NAME -> TableText(client.name ?: client.ip)
                ClientColumn.IP -> TableText(client.ip)
                ClientColumn.CONNECTED -> TableText(clientTime(client.connectedMs))
                ClientColumn.LAST -> TableText(clientTime(client.lastMs))
                ClientColumn.COUNT -> TableText(client.requests.toString())
            }
        }
    }
}

/** `9/8 @ 2:03 PM` — the Swing dialog's format. */
private fun clientTime(ms: Long): String = DateUtils.formatDate(DateUtils.timeToDate(ms), "M/d @ h:mm a")

/** the Swing dialog's columns, sized to fill its 500px table. */
private enum class ClientColumn(val label: StringResource, val width: Dp) {
    NAME(Res.string.share_col_name, 150.dp),
    IP(Res.string.share_col_ip, 120.dp),
    CONNECTED(Res.string.share_col_connected, 110.dp),
    LAST(Res.string.share_col_last, 110.dp),
    COUNT(Res.string.share_col_count, 50.dp),
}

// the ports an unprivileged process can bind, as the Swing dialog required
private const val MIN_PORT = 1024
private const val MAX_PORT = 65535
private const val MAX_NAME = 64
private const val MAX_TOKEN = 64
