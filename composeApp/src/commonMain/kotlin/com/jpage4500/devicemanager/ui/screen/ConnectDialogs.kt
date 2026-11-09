package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
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
 * the Swing app's `QrCodeDialog`: a QR the device scans from Wireless debugging ▸ Pair device with
 * QR code, and what has happened since.
 *
 * [PairingSession] does the work — the same one the phone's Connect panel uses — and runs for as
 * long as this is open, so closing the window is what stops listening. Once the device is paired
 * and connected the window closes itself and says so in a toast.
 */
@Composable
fun PairQrDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember { PairingSession() }
    val state by session.state.collectAsState()
    LaunchedEffect(session) { session.run() }

    val paired = state as? PairState.Paired
    LaunchedEffect(paired) {
        if (paired == null) return@LaunchedEffect
        ToastManager.showToast(paired.message, success = true)
        onDismiss()
    }

    DialogContainer(title = stringResource(Res.string.pair_qr_title), onDismiss = onDismiss, onEnter = onDismiss) {
        Column(
            Modifier
                .width(Dimens.qrDialogWidth)
                .background(Colors.menuBg)
                .padding(Dimens.gapLarge * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.pair_qr_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Colors.primaryText,
            )
            Spacer(Modifier.height(Dimens.gapLarge * 2))
            Text(
                text = stringResource(Res.string.pair_qr_steps),
                style = MaterialTheme.typography.bodyMedium,
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
                QrImage(session.qrContent, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(Dimens.gapLarge * 2))

            val current = state
            Text(
                text = when (current) {
                    is PairState.Checking -> stringResource(Res.string.connect_pair_checking)
                    is PairState.Waiting -> stringResource(Res.string.pair_qr_listening)
                    is PairState.Found -> stringResource(Res.string.connect_pair_found, current.address)
                    is PairState.Connecting -> stringResource(Res.string.connect_pair_connecting)
                    is PairState.Paired -> current.message
                    is PairState.Failed -> stringResource(Res.string.pair_qr_failed, current.message)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = when (current) {
                    is PairState.Waiting, is PairState.Paired -> Colors.online
                    is PairState.Failed -> Colors.error
                    is PairState.Checking -> Colors.info
                    else -> Colors.busy
                },
                textAlign = TextAlign.Center,
            )
            if (current is PairState.Failed && current.canRetry) {
                TextButton(onClick = { scope.launch { session.retry() } }) {
                    Text(stringResource(Res.string.connect_pair_retry), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(Dimens.gap))
            Text(
                text = stringResource(Res.string.pair_qr_note),
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

/**
 * the Swing app's `RemoteServerDialog`: every Device Manager this app has been pointed at, each
 * with a tick box that attaches or detaches it.
 *
 * Several can be attached at once — [ServerManager] keeps a connection per server — so the tick is
 * exactly the Swing dialog's "enabled". What it doesn't carry is the colour column: [SavedServer] has
 * no colour, and nothing in this app draws a server's devices in one yet.
 */
@Composable
fun RemoteServersDialog(onDismiss: () -> Unit) {
    val servers by ServerManager.servers.collectAsState()
    val connectedKeys by ServerManager.connectedKeys.collectAsState()
    val scope = rememberCoroutineScope()
    val paste = rememberClipboardPaste()

    val connecting = remember { mutableStateMapOf<String, Boolean>() }
    var selection by remember { mutableStateOf(TableSelection<Any?>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ServerEdit?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }

    val widths = remember { mutableStateMapOf<ServerColumn, Dp>().apply { ServerColumn.entries.forEach { put(it, it.width) } } }
    val headers = ServerColumn.entries.associateWith { stringResource(it.label) }
    val removeTitle = stringResource(Res.string.servers_remove_title)
    val selected = servers.firstOrNull { it.key == selection.lead }

    fun toggle(server: SavedServer) {
        if (server.key in connectedKeys) {
            ServerManager.disconnect(server.key)
            return
        }
        if (connecting[server.key] == true) return
        connecting[server.key] = true
        error = null
        scope.launch {
            ServerManager.connect(server).onFailure { error = "${server.displayName}: ${it.message}" }
            connecting.remove(server.key)
        }
    }

    fun pasteConnection() {
        val text = paste()
        scope.launch {
            val server = SavedServer.fromConnectionString(text)
            when {
                text.isNullOrBlank() -> error = getString(Res.string.servers_clipboard_empty)
                server == null -> error = getString(Res.string.servers_invalid)
                // into the form rather than straight down the wire, the way the Swing app does it
                else -> {
                    error = null
                    editing = ServerEdit(original = null, server = server)
                }
            }
        }
    }

    DialogContainer(title = stringResource(Res.string.servers_title), onDismiss = onDismiss, onEnter = null) {
        Column(
            Modifier
                .width(Dimens.serversDialogWidth)
                .background(Colors.menuBg)
                .padding(Dimens.gapLarge),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Dimens.serversTableHeight)
                    .border(Dp.Hairline, Colors.divider)
            ) {
                DataTable(
                    rows = servers,
                    columns = ServerColumn.entries,
                    widths = widths,
                    sortedBy = null,
                    sortAscending = true,
                    selection = selection,
                    rowKey = { it.key },
                    header = { headers.getValue(it) },
                    typeAheadText = { it.displayName },
                    onSelectionChange = { selection = it },
                    onResize = { column, width -> widths[column] = width },
                    onActivate = { editing = ServerEdit(original = it, server = it) },
                ) { server, column ->
                    val connected = server.key in connectedKeys
                    when (column) {
                        ServerColumn.CONNECTED -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            RowCheckbox(checked = connected, onToggle = { toggle(server) }, label = server.displayName)
                        }
                        ServerColumn.NAME -> TableText(server.name.orEmpty())
                        ServerColumn.HOST -> TableText(server.host)
                        ServerColumn.PORT -> TableText(server.port.toString())
                        ServerColumn.STATUS -> {
                            val status = when {
                                connected -> Res.string.connect_status_connected to Colors.online
                                connecting[server.key] == true -> Res.string.connect_status_connecting to Colors.busy
                                else -> Res.string.connect_status_disconnected to Colors.error
                            }
                            TableText(stringResource(status.first), color = status.second)
                        }
                    }
                }
                if (servers.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.servers_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = Colors.secondaryText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(Dimens.gapLarge * 2),
                    )
                }
            }

            Text(
                text = error ?: " ",
                style = MaterialTheme.typography.bodySmall,
                color = Colors.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = Dimens.gap),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapLarge)) {
                DialogButton(stringResource(Res.string.servers_add)) {
                    editing = ServerEdit(original = null, server = SavedServer(host = ""))
                }
                DialogButton(stringResource(Res.string.servers_paste), onClick = ::pasteConnection)
                DialogButton(stringResource(Res.string.servers_edit), enabled = selected != null) {
                    selected?.let { editing = ServerEdit(original = it, server = it) }
                }
                DialogButton(stringResource(Res.string.remove), enabled = selected != null) {
                    val server = selected ?: return@DialogButton
                    scope.launch {
                        confirm = ConfirmRequest(
                            title = removeTitle,
                            message = getString(Res.string.servers_remove_msg, server.displayName),
                            onConfirm = { ServerManager.forget(server.key) },
                        )
                    }
                }
            }
        }
    }

    editing?.let { edit ->
        ServerEditDialog(
            edit = edit,
            connected = edit.original?.key in connectedKeys,
            onDismiss = { editing = null },
        )
    }
    ConfirmDialog(confirm, onDismiss = { confirm = null })
}

/** a server being added ([original] null) or changed. */
private data class ServerEdit(val original: SavedServer?, val server: SavedServer)

/**
 * add or edit one server: host, port and auth token.
 *
 * No name field, unlike the Swing one: the name is whatever the server calls itself (`/api/info`'s
 * `serverName`), and a connect overwrites anything typed. **Adding connects** — that is what
 * proves the address and token before anything is saved, so a typo says so here rather than
 * leaving a dead row. Editing a server that isn't attached just saves it.
 */
@Composable
private fun ServerEditDialog(edit: ServerEdit, connected: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(edit.server.host) }
    var port by remember { mutableStateOf(edit.server.port.toString()) }
    var token by remember { mutableStateOf(edit.server.token) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hostFocus = remember { FocusRequester() }

    val isAdd = edit.original == null
    val canSave = !busy && host.isNotBlank() && port.toIntOrNull() != null

    fun save() {
        if (!canSave) return
        val server = edit.server.copy(host = host.trim(), port = port.toInt(), token = token.trim())
        val original = edit.original
        // a duplicate is refused rather than merged, as in the Swing app
        if (ServerManager.servers.value.any { it.key == server.key && it.key != original?.key }) {
            scope.launch { error = getString(Res.string.servers_exists, server.key) }
            return
        }
        busy = true
        error = null
        scope.launch {
            if (original != null && original.key != server.key) ServerManager.forget(original.key)
            if (isAdd || connected) {
                ServerManager.connect(server)
                    .onSuccess { onDismiss() }
                    .onFailure { error = it.message ?: "couldn't connect" }
            } else {
                ServerManager.remember(server)
                onDismiss()
            }
            busy = false
        }
    }

    AppDialog(
        title = stringResource(if (isAdd) Res.string.servers_add else Res.string.servers_edit_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(if (isAdd) Res.string.servers_add else Res.string.save),
        confirmEnabled = canSave,
        onConfirm = ::save,
    ) {
        FocusOnShow(hostFocus)
        EditField(Res.string.connect_host, host, { host = it }, ::save, hostFocus)
        EditField(Res.string.connect_port, port, { port = it.filter { c -> c.isDigit() }.take(5) }, ::save)
        EditField(Res.string.connect_token, token, { token = it }, ::save)
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = Colors.error,
                modifier = Modifier.padding(top = Dimens.gap),
            )
        }
    }
}

@Composable
private fun EditField(
    label: StringResource,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    FieldLabel(stringResource(label))
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        onSubmit = onSubmit,
    )
    Spacer(Modifier.height(Dimens.gap))
}

/** the Swing dialog's columns, at its widths, less its colour. */
private enum class ServerColumn(val label: StringResource, val width: Dp) {
    CONNECTED(Res.string.connect_col_tick, 30.dp),
    NAME(Res.string.connect_name, 150.dp),
    HOST(Res.string.connect_host, 150.dp),
    PORT(Res.string.connect_port, 60.dp),
    STATUS(Res.string.connect_col_status, 80.dp),
}
