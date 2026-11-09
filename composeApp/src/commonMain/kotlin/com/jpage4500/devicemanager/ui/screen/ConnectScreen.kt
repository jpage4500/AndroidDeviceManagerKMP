package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * connect to a device over the network — the Swing app's `ConnectDialog`, laid out the same.
 *
 * A table of every wireless device: the ones connected now and the ones this app has reached before,
 * each with its live state and a tick box that connects or disconnects it. Under it a row for one it
 * hasn't — IP, port, and a pairing **code** for a device this computer hasn't paired with yet, which
 * turns Connect into Pair. Under that, Connect Remote Server and Pair with QR Code, each a window of
 * its own (see ConnectDialogs.kt).
 *
 * The address field opens on the *last* one connected to with its final octet selected, so reaching
 * the phone next to the last one is four keystrokes.
 *
 * **Nothing here polls.** The Swing dialog re-read the device list on timers to notice a name
 * arriving or a device being allowed; this table is drawn from [DeviceManager.devices], so both
 * simply show up. The only waiting done here is for "Allow" — see [awaitAllowed].
 */
@Composable
fun ConnectScreen(onClose: () -> Unit) {
    val devices by DeviceManager.devices.collectAsState()
    val recent by WirelessManager.recent.collectAsState()
    val scope = rememberCoroutineScope()

    val last = remember { WirelessManager.lastAddress() }
    var host by remember { mutableStateOf(TextFieldValue(last?.host ?: DEFAULT_HOST)) }
    var port by remember { mutableStateOf((last?.port ?: WirelessDevice.DEFAULT_PORT).toString()) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Note?>(null) }
    val hostFocus = remember { FocusRequester() }

    // what a row is doing while a connect or disconnect it started is in flight; the live state
    // takes over again once it finishes
    val pending = remember { mutableStateMapOf<String, ConnectStatus>() }
    var selection by remember { mutableStateOf(TableSelection<Any?>()) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var qrOpen by remember { mutableStateOf(false) }
    var serversOpen by remember { mutableStateOf(false) }

    val rows = remember(devices, recent, pending.toMap()) { wirelessRows(devices, recent, pending) }
    val widths = remember { mutableStateMapOf<ConnectColumn, Dp>().apply { ConnectColumn.entries.forEach { put(it, it.width) } } }
    val headers = ConnectColumn.entries.associateWith { stringResource(it.label) }
    val disconnectTitle = stringResource(Res.string.connect_disconnect_title)
    val deleteTitle = stringResource(Res.string.connect_delete_title)

    val pairing = code.isNotBlank()
    val portNumber = port.toIntOrNull()
    // with a code the port is optional: the pairing port is looked up from the device
    val canSubmit = !busy && host.text.isNotBlank() && (pairing || portNumber != null)

    /**
     * the rest of a connect after adb has answered: an unauthorised device is waited on, anything
     * else that failed says why.
     */
    suspend fun afterConnect(result: Result<String>, atHost: String, atPort: Int) {
        val serial = "$atHost:$atPort"
        result.onSuccess {
            WirelessManager.add(atHost, atPort)
            note = null
        }
        result.onFailure { error ->
            val message = error.message.orEmpty()
            note = when {
                message.isAuthError() -> {
                    note = Note(getString(Res.string.connect_allow, atHost), Colors.busy)
                    if (awaitAllowed(serial)) null else Note(getString(Res.string.connect_not_allowed, serial), Colors.error)
                }
                message.contains("failed to resolve host", ignoreCase = true) ->
                    Note(getString(Res.string.connect_not_found, serial), Colors.error)
                else -> Note(getString(Res.string.connect_failed, serial, message), Colors.error)
            }
        }
    }

    fun connectRow(row: WirelessRow) {
        val rowPort = row.port.toIntOrNull() ?: return
        pending[row.serial] = ConnectStatus.CONNECTING
        scope.launch {
            val result = DeviceManager.connect(row.host, rowPort)
            pending.remove(row.serial)
            afterConnect(result, row.host, rowPort)
        }
    }

    fun disconnectRow(row: WirelessRow) {
        val id = row.deviceId ?: return
        pending[row.serial] = ConnectStatus.DISCONNECTING
        scope.launch {
            DeviceManager.disconnect(id)
            pending.remove(row.serial)
        }
    }

    fun toggle(row: WirelessRow) {
        when (row.status) {
            ConnectStatus.CONNECTED -> scope.launch {
                confirm = ConfirmRequest(
                    title = disconnectTitle,
                    message = getString(Res.string.connect_disconnect_msg, row.name, row.serial),
                    onConfirm = { disconnectRow(row) },
                )
            }
            ConnectStatus.DISCONNECTED -> connectRow(row)
            // mid-connect, mid-disconnect or waiting on "Allow": a second press would only race it
            else -> Unit
        }
    }

    fun submit() {
        if (!canSubmit) return
        val atHost = host.text.trim()
        busy = true
        scope.launch {
            if (pairing) {
                pairAndConnect(atHost, portNumber, code.trim()) { note = it }
                // a code is single use
                code = ""
            } else {
                val atPort = portNumber ?: WirelessDevice.DEFAULT_PORT
                if (rows.any { it.serial == "$atHost:$atPort" && it.status == ConnectStatus.CONNECTED }) {
                    note = Note(getString(Res.string.connect_already), Colors.busy)
                } else {
                    note = Note(getString(Res.string.connect_connecting_to, "$atHost:$atPort"), Colors.busy)
                    afterConnect(DeviceManager.connect(atHost, atPort), atHost, atPort)
                }
            }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Colors.menuBg)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            }
            .padding(Dimens.gapLarge),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(Dp.Hairline, Colors.divider)
        ) {
            DataTable(
                rows = rows,
                columns = ConnectColumn.entries,
                widths = widths,
                sortedBy = null,
                sortAscending = true,
                selection = selection,
                rowKey = { it.serial },
                header = { headers.getValue(it) },
                typeAheadText = { it.name },
                onSelectionChange = { selection = it },
                onResize = { column, width -> widths[column] = width },
                // a double-click on a device that isn't connected connects it, the tick box's job
                onActivate = { if (it.status == ConnectStatus.DISCONNECTED) connectRow(it) },
                rowMenu = { row, _ ->
                    if (row.status == ConnectStatus.DISCONNECTED) {
                        Item(stringResource(Res.string.connect_delete)) {
                            scope.launch {
                                confirm = ConfirmRequest(
                                    title = deleteTitle,
                                    message = getString(Res.string.connect_delete_msg, row.name, row.serial),
                                    onConfirm = { WirelessManager.forget(row.serial) },
                                )
                            }
                        }
                    } else if (row.status == ConnectStatus.CONNECTED) {
                        Item(stringResource(Res.string.disconnect)) { toggle(row) }
                    }
                },
            ) { row, column ->
                when (column) {
                    ConnectColumn.CONNECTED -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        RowCheckbox(
                            checked = row.status == ConnectStatus.CONNECTED,
                            onToggle = { toggle(row) },
                            label = row.name,
                        )
                    }
                    ConnectColumn.NAME -> TableText(row.name)
                    ConnectColumn.IP -> TableText(row.host)
                    ConnectColumn.PORT -> TableText(row.port)
                    ConnectColumn.STATUS -> TableText(stringResource(row.status.label), color = row.status.color)
                    ConnectColumn.LAST_CONNECTED -> TableText(
                        row.lastConnectedMs.takeIf { it > 0 }?.let { DateUtils.formatDate(DateUtils.timeToDate(it), LAST_CONNECTED_FORMAT) }
                    )
                }
            }
        }

        Spacer(Modifier.height(Dimens.gapLarge))
        HorizontalDivider(color = Colors.divider)
        Spacer(Modifier.height(Dimens.gapLarge))

        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldName(stringResource(Res.string.connect_ip))
            AppTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.weight(1.4f),
                focusRequester = hostFocus,
                onSubmit = ::submit,
            )
            Spacer(Modifier.width(Dimens.gapLarge))
            FieldName(stringResource(Res.string.connect_port))
            AppTextField(
                value = port,
                // 5 digits, digits only — a port can't be anything else
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                modifier = Modifier.weight(1f),
                onSubmit = ::submit,
            )
            Spacer(Modifier.width(Dimens.gapLarge))
            FieldName(stringResource(Res.string.connect_code))
            // only needed the first time a device is connected to this computer
            AppTextField(
                value = code,
                onValueChange = { code = it.trim() },
                modifier = Modifier.weight(1f),
                hint = stringResource(Res.string.connect_code_hint),
                onSubmit = ::submit,
            )
            Spacer(Modifier.width(Dimens.gapLarge))
            DialogButton(
                label = stringResource(if (pairing) Res.string.connect_pair else Res.string.connect),
                enabled = canSubmit,
                onClick = ::submit,
            )
        }

        // always a line tall, so a message arriving doesn't shove the buttons down
        Text(
            text = note?.text ?: " ",
            style = MaterialTheme.typography.bodySmall,
            color = note?.color ?: Colors.secondaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.gap),
        )

        Spacer(Modifier.height(Dimens.gap))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DialogButton(
                label = stringResource(Res.string.connect_remote_server),
                icon = Res.drawable.server,
                onClick = { serversOpen = true },
            )
            Spacer(Modifier.weight(1f))
            DialogButton(
                label = stringResource(Res.string.connect_pair_qr_button),
                icon = Res.drawable.qr_code,
                onClick = { qrOpen = true },
            )
        }
    }

    // focus the address and select its last octet, so only that has to be retyped
    LaunchedEffect(Unit) {
        runCatching { hostFocus.requestFocus() }
        val dot = host.text.lastIndexOf('.')
        val start = if (dot >= 0 && dot + 1 < host.text.length) dot + 1 else 0
        host = host.copy(selection = TextRange(start, host.text.length))
    }

    ConfirmDialog(confirm, onDismiss = { confirm = null })
    if (qrOpen) PairQrDialog(onDismiss = { qrOpen = false })
    if (serversOpen) RemoteServersDialog(onDismiss = { serversOpen = false })
}

/**
 * pair with the code a device is showing, then connect to it.
 *
 * Two steps the user sees separately, because they fail separately: a wrong code is a pairing
 * failure, while a device that paired and then couldn't be reached keeps its pairing — the next
 * attempt only has to connect.
 */
private suspend fun pairAndConnect(host: String, port: Int?, code: String, onNote: (Note?) -> Unit) {
    onNote(Note(getString(Res.string.connect_pairing_with, host), Colors.busy))
    val paired = WirelessManager.pair(host, port, code)
    paired.onFailure {
        onNote(Note(getString(Res.string.connect_pair_failed, host, it.message.orEmpty()), Colors.error))
        return
    }
    onNote(Note(getString(Res.string.connect_paired_connecting, host), Colors.busy))
    WirelessManager.connectPaired(host, port)
        .onSuccess { onNote(null) }
        .onFailure { onNote(Note(getString(Res.string.connect_paired_no_connect, host), Colors.error)) }
}

/**
 * wait for the device at [serial] to be allowed — the "Allow USB debugging?" prompt on its screen.
 *
 * adb answers a connect to a device that hasn't accepted this computer with "failed to
 * authenticate", but the device is in the list as unauthorised and turns online the moment someone
 * taps Allow. The Swing app's minute, watched on the live list rather than polled.
 */
private suspend fun awaitAllowed(serial: String): Boolean =
    withTimeoutOrNull(ALLOW_TIMEOUT_MS) {
        DeviceManager.devices.first { list -> list.any { it.serial == serial && it.isOnline } }
    } != null

/** the Swing app's `isAuthError`. */
private fun String.isAuthError(): Boolean =
    listOf("failed to authenticate", "unauthorized", "still authorizing").any { contains(it, ignoreCase = true) }

/** a line under the fields saying what the last attempt is doing, or did. */
private data class Note(val text: String, val color: Color)

/** a row's state: the live one, or what this window is in the middle of doing to it. */
private enum class ConnectStatus(val label: StringResource, val color: Color) {
    CONNECTED(Res.string.connect_status_connected, Colors.online),
    DISCONNECTED(Res.string.connect_status_disconnected, Colors.error),
    CONNECTING(Res.string.connect_status_connecting, Colors.busy),
    DISCONNECTING(Res.string.connect_status_disconnecting, Colors.busy),
    AUTHORIZING(Res.string.connect_status_authorizing, Colors.busy),
}

/** the Swing dialog's columns, at its widths. */
private enum class ConnectColumn(val label: StringResource, val width: Dp) {
    CONNECTED(Res.string.connect_col_tick, 40.dp),
    NAME(Res.string.connect_name, 150.dp),
    IP(Res.string.connect_ip, 120.dp),
    PORT(Res.string.connect_port, 60.dp),
    STATUS(Res.string.connect_col_status, 100.dp),
    LAST_CONNECTED(Res.string.connect_col_last, 130.dp),
}

/** one wireless device, connected or remembered. */
private data class WirelessRow(
    val serial: String,
    val name: String,
    val host: String,
    val port: String,
    val status: ConnectStatus,
    val lastConnectedMs: Long,
    /** the device in the live list, when there is one — what a disconnect acts on. */
    val deviceId: String?,
)

/**
 * the live wireless devices first, then the remembered ones that aren't live, newest first.
 *
 * A device named by mDNS (`adb-XXXX._adb-tls-connect._tcp`) has no port, so its whole serial is the
 * IP column — what the Swing table shows too.
 */
private fun wirelessRows(
    devices: List<Device>,
    recent: List<WirelessDevice>,
    pending: Map<String, ConnectStatus>,
): List<WirelessRow> {
    val bySerial = recent.associateBy { it.serial }
    val live = devices.filter { it.isWireless }.distinctBy { it.serial }.map { device ->
        val remembered = bySerial[device.serial]
        val status = when {
            device.isOnline -> ConnectStatus.CONNECTED
            device.state == DeviceState.UNAUTHORIZED || device.state == DeviceState.AUTHORIZING -> ConnectStatus.AUTHORIZING
            else -> ConnectStatus.DISCONNECTED
        }
        WirelessRow(
            serial = device.serial,
            // a nickname arrives a few seconds after the model; the remembered name covers the gap
            name = if (device.displayName == device.serial) remembered?.name ?: device.serial else device.displayName,
            host = device.serial.substringBeforeLast(':', device.serial),
            port = device.serial.substringAfterLast(':', "").takeIf { p -> p.toIntOrNull() != null }.orEmpty(),
            status = pending[device.serial] ?: status,
            lastConnectedMs = remembered?.lastSeenMs ?: 0,
            deviceId = device.id,
        )
    }
    val liveSerials = live.mapTo(mutableSetOf()) { it.serial }
    val remembered = recent
        .filter { it.serial !in liveSerials }
        .sortedByDescending { it.lastSeenMs }
        .map {
            WirelessRow(
                serial = it.serial,
                name = it.displayName,
                host = it.host,
                port = it.port.toString(),
                status = pending[it.serial] ?: ConnectStatus.DISCONNECTED,
                lastConnectedMs = it.lastSeenMs,
                deviceId = null,
            )
        }
    return live + remembered
}

/** a field's caption, to its left. */
@Composable
private fun FieldName(text: String) {
    Text(
        text = "$text:",
        style = MaterialTheme.typography.bodySmall,
        color = Colors.primaryText,
        modifier = Modifier.padding(end = Dimens.gap),
    )
}

private const val DEFAULT_HOST = "192.168.0.100"

// "10/29 @ 4:39 PM", the Swing table's own format
private const val LAST_CONNECTED_FORMAT = "M/d @ h:mm a"

// how long a device has to be allowed before the wait gives up; the Swing app's number
private const val ALLOW_TIMEOUT_MS = 60_000L
