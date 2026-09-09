package com.jpage4500.devicemanager.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * the phone's front door: every way of reaching a device, in one card.
 *
 * **One composable, two placements** — expanded as the empty state when nothing is connected, and
 * collapsed to a single "Add a device" row under the list when something is. They have to be the
 * same composable or they drift; see docs/MOBILE-UI.md.
 *
 * The routes are ordered by how likely each is to work rather than by how interesting it is, which
 * is why a Device Manager server comes first: it reaches the desk's whole USB fleet, it needs no
 * protocol this app hasn't already written, and it works against today's Swing app.
 */
@Composable
fun ConnectPanel(modifier: Modifier = Modifier, startExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(startExpanded) }
    var route by remember { mutableStateOf<Route?>(null) }

    Column(
        modifier
            .padding(Dimens.gap)
            .clip(RoundedCornerShape(Dimens.corner))
            .background(Colors.headerBackground)
            .fillMaxWidth(),
    ) {
        if (!expanded) {
            OptionRow(
                icon = Res.drawable.add,
                title = stringResource(Res.string.connect_add),
                subtitle = null,
                onClick = { expanded = true },
            )
            return@Column
        }

        Route.entries.forEachIndexed { index, entry ->
            // a rule only *between* blocks: one drawn under an open row would cut the form off from
            // the row that opened it, which is exactly the tie the block is trying to show
            if (index > 0 && route != entry && route != Route.entries[index - 1]) {
                HorizontalDivider(color = Colors.divider)
            }
            RouteBlock(
                entry = entry,
                open = route == entry,
                onToggle = { route = if (route == entry) null else entry },
            )
        }
    }
}

/**
 * one route and, when it is open, the form that belongs to it.
 *
 * [ExpandableBlock] is the drawing: a form that simply appears below a row reads as the next item in
 * the list rather than as that row's contents. The device list's computers open the same way.
 */
@Composable
private fun RouteBlock(entry: Route, open: Boolean, onToggle: () -> Unit) {
    ExpandableBlock(
        open = open,
        header = {
            OptionRow(
                icon = entry.icon,
                title = stringResource(entry.title),
                subtitle = stringResource(entry.subtitle),
                enabled = entry.enabled,
                open = open,
                onClick = onToggle,
                // a route that isn't built yet says so where its chevron would be
                trailing = if (entry.enabled) null else ({ Chip(stringResource(Res.string.connect_later)) }),
            )
        },
        body = {
            when (entry) {
                Route.SERVER -> ServerForm()
                Route.DEVICE -> DeviceForm()
            }
        },
    )
}

/** the two ways in, in the order the panel offers them. */
private enum class Route(
    val icon: DrawableResource,
    val title: StringResource,
    val subtitle: StringResource,
    val enabled: Boolean = true,
) {
    SERVER(Res.drawable.server, Res.string.connect_server, Res.string.connect_server_msg),
    DEVICE(Res.drawable.device_remote, Res.string.connect_device_by_ip, Res.string.connect_device_by_ip_msg),
}

/**
 * a Device Manager instance, in the order of how little work each way is.
 *
 * Scanning the QR the desktop shows is the main path and needs nothing typed; pasting its connection
 * string is the same thing without a camera; typing the three fields out is the last resort and is
 * hidden behind a link, because most people should never see it.
 *
 * `/api/info` answers before anything is saved either way, so a typo doesn't leave a dead entry
 * behind and the row is labelled with the name the server gave rather than with its address.
 */
@Composable
private fun ServerForm() {
    val scope = rememberCoroutineScope()
    // resolved here rather than in the click handler: stringResource is a composable read
    val notOurQr = stringResource(Res.string.connect_scan_none)

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(SavedServer.DEFAULT_PORT.toString()) }
    var token by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Status?>(null) }

    fun connect() {
        if (busy || host.isBlank()) return
        busy = true
        status = null
        scope.launch {
            val server = SavedServer(
                host = host.trim(),
                port = port.toIntOrNull() ?: SavedServer.DEFAULT_PORT,
                token = token.trim(),
            )
            ServerManager.connect(server)
                .onSuccess {
                    val text = getString(Res.string.connect_connected, it.serverName.orEmpty(), it.deviceCount)
                    status = Status(text, isError = false)
                }
                .onFailure { status = Status(it.message ?: "couldn't connect", isError = true) }
            busy = false
        }
    }

    // the camera is the way in that needs no typing at all: Device Manager's Share Server dialog
    // shows a QR of the very connection string SavedServer already reads
    val scan = rememberQrScanner { text ->
        val server = SavedServer.fromConnectionString(text)
        if (server == null) {
            status = Status(notOurQr, isError = true)
        } else {
            host = server.host
            port = server.port.toString()
            token = server.token
            // show what was scanned, so a failure can be corrected rather than retried blind
            manual = true
            connect()
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.rowPadding).padding(bottom = Dimens.gapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CenteredButton(
            label = stringResource(Res.string.connect_scan_qr),
            icon = Res.drawable.qr_code,
            enabled = scan != null && !busy,
            onClick = { scan?.invoke() },
        )
        Spacer(Modifier.height(Dimens.gap))
        Text(
            text = stringResource(
                if (scan != null) Res.string.connect_scan_qr_msg else Res.string.connect_scan_unsupported
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.gap))

        if (!manual) {
            LinkButton(stringResource(Res.string.connect_manual), enabled = !busy) { manual = true }
        }
        AnimatedVisibility(
            visible = manual,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                LabelledField(Res.string.connect_host, host, KeyboardType.Uri, { host = it }, ::connect, Res.string.connect_host_hint)
                LabelledField(Res.string.connect_port, port, KeyboardType.Number, { port = it.digits(5) }, ::connect)
                LabelledField(Res.string.connect_token, token, KeyboardType.Password, { token = it }, ::connect)
                Spacer(Modifier.height(Dimens.gapLarge))
                CenteredButton(
                    label = stringResource(Res.string.connect),
                    enabled = !busy && host.isNotBlank(),
                    onClick = ::connect,
                )
            }
        }
        StatusText(status)
    }
}

/**
 * a device, reached without a computer in the middle.
 *
 * Two halves, and they need different things. **Connecting** is done here: `DirectDeviceTransport`
 * talks to `adbd` itself, so an address plus the "Allow debugging?" dialog on the other device is
 * the whole of it. **Pairing** — the QR the other device scans from its wireless debugging screen —
 * is still done by an adb server, because the handshake behind it needs a TLS exporter no platform
 * here exposes; see docs/ADB-TRANSPORTS.md.
 *
 * So the address field comes first: it is the part that works with nothing else involved. The QR
 * below it is offered only where there is a server to do the cryptography.
 */
@Composable
private fun DeviceForm() {
    val scope = rememberCoroutineScope()
    // any attached adb server will do the pairing cryptography; a Device Manager server can't
    val connections by DeviceManager.connections.collectAsState()
    val canPair = connections.any { it.canPair }

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(DirectDeviceTransport.DEFAULT_PORT.toString()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Status?>(null) }
    var pairStatus by remember { mutableStateOf<Status?>(null) }
    var pairing by remember { mutableStateOf(false) }
    val found = rememberAdbDevices()

    // an android build that ships the adb binary can start a server of its own, which is what makes
    // pairing possible with no computer at all — see LocalAdbServer
    val localState by LocalAdbServer.state.collectAsState()
    val canStartLocal = LocalAdbServer.isAvailable

    /** go straight to the QR when something can already pair, otherwise start our own server. */
    fun pair() {
        if (canPair) {
            pairing = true
            return
        }
        busy = true
        pairStatus = null
        scope.launch {
            LocalAdbServer.start()
                .onSuccess { pairing = true }
                .onFailure { pairStatus = Status(it.message ?: "couldn't start adb", isError = true) }
            busy = false
        }
    }

    fun connect() {
        if (busy || host.isBlank()) return
        busy = true
        status = null
        scope.launch {
            val transport = DirectDeviceTransport(
                host = host.trim(),
                port = port.toIntOrNull() ?: DirectDeviceTransport.DEFAULT_PORT,
            )
            // probed before it is added, so a typo doesn't leave a dead row in the list — and so the
            // status line can name the device rather than the address that was typed
            transport.probe()
                .onSuccess {
                    DeviceManager.addConnection(transport)
                    status = Status(getString(Res.string.connect_direct_connected, it), isError = false)
                }
                .onFailure {
                    transport.close()
                    status = Status(it.message ?: "couldn't connect", isError = true)
                }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.rowPadding).padding(bottom = Dimens.gapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (pairing) {
            PairPanel(onDone = { pairing = false })
            Spacer(Modifier.height(Dimens.gap))
        } else {
            // a device left in `adb tcpip` mode announces itself, so the common case needs no typing
            found.forEach { service ->
                OptionRow(
                    icon = Res.drawable.device_remote,
                    title = service.name,
                    subtitle = "${service.host}:${service.port}",
                    enabled = !busy,
                    onClick = {
                        host = service.host
                        port = service.port.toString()
                        connect()
                    },
                )
            }
            LabelledField(Res.string.connect_host, host, KeyboardType.Uri, { host = it }, ::connect, Res.string.connect_host_hint)
            LabelledField(Res.string.connect_port, port, KeyboardType.Number, { port = it.digits(5) }, ::connect)
            Spacer(Modifier.height(Dimens.gapLarge))
            CenteredButton(
                label = stringResource(Res.string.connect),
                enabled = !busy && host.isNotBlank(),
                onClick = ::connect,
            )
            Spacer(Modifier.height(Dimens.gap))
            Text(
                text = stringResource(Res.string.connect_direct_msg),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
                textAlign = TextAlign.Center,
            )
            StatusText(status)
            Spacer(Modifier.height(Dimens.gapLarge))
            HorizontalDivider(color = Colors.divider)
            Spacer(Modifier.height(Dimens.gapLarge))
            CenteredButton(
                label = stringResource(Res.string.connect_pair_qr),
                icon = Res.drawable.qr_code,
                // pairing needs an adb server to do the cryptography: one already attached, or one
                // this app starts for itself. with neither, the button would show a QR that could
                // never be acted on — see PairingSession and LocalAdbServer
                enabled = !busy && (canPair || canStartLocal),
                onClick = ::pair,
            )
            Spacer(Modifier.height(Dimens.gap))
            Text(
                text = when {
                    canPair -> stringResource(Res.string.connect_pair_steps)
                    localState is LocalAdbServer.State.Starting ->
                        stringResource(Res.string.connect_pair_starting)
                    canStartLocal -> stringResource(Res.string.connect_pair_bundled)
                    else -> stringResource(Res.string.connect_pair_needs_adb)
                },
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
                textAlign = TextAlign.Center,
            )
            StatusText(pairStatus)
        }
    }
}

/**
 * every device on the network announcing a plain adb port, for as long as this is composed.
 *
 * Started on the main thread and stopped when the form goes away, which is what `browseMdns` asks
 * for: iOS schedules `NSNetServiceBrowser` on the calling thread's run loop, and a background
 * thread has none. Duplicates are dropped by address rather than by name, because a resolve can
 * fire more than once for the same device.
 */
@Composable
private fun rememberAdbDevices(): List<MdnsService> {
    var found by remember { mutableStateOf(emptyList<MdnsService>()) }
    DisposableEffect(Unit) {
        val browse = browseMdns(MDNS_ADB_CONNECT) { service ->
            found = (found.filterNot { it.host == service.host && it.port == service.port } + service)
                .sortedBy { it.name }
        }
        onDispose { browse.close() }
    }
    return found
}

/**
 * the QR itself, and what happened to it.
 *
 * The session runs for as long as this is composed: leaving the panel stops the mDNS browse, which
 * is the whole reason it is a `LaunchedEffect` over a suspending [PairingSession.run] rather than a
 * start/stop pair somebody has to remember to call.
 */
@Composable
private fun PairPanel(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember { PairingSession() }
    val state by session.state.collectAsState()
    LaunchedEffect(session) { session.run() }

    val current = state
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // no QR while the server is still in question, and none once it turned out not to be there:
        // a code nobody can act on is an invitation to waste a minute scanning it
        if (current !is PairState.Checking && !(current is PairState.Failed && !current.canRetry)) {
            Text(
                text = stringResource(Res.string.connect_pair_steps),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimens.gap))
            QrImage(session.qrContent, Modifier.fillMaxWidth(0.8f))
            Spacer(Modifier.height(Dimens.gap))
        }

        Text(
            text = when (current) {
                is PairState.Checking -> stringResource(Res.string.connect_pair_checking)
                is PairState.Waiting -> stringResource(Res.string.connect_pair_waiting)
                is PairState.Found -> stringResource(Res.string.connect_pair_found, current.address)
                is PairState.Paired -> current.message
                is PairState.Failed -> current.message
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (current) {
                is PairState.Paired -> Colors.online
                is PairState.Failed -> Colors.error
                else -> Colors.secondaryText
            },
            textAlign = TextAlign.Center,
        )
        Row {
            if (current is PairState.Failed && current.canRetry) {
                LinkButton(stringResource(Res.string.connect_pair_retry)) {
                    scope.launch { session.retry() }
                }
            }
            LinkButton(stringResource(Res.string.connect_pair_cancel), onClick = onDone)
        }
    }
}

// ── the pieces both forms are made of ───────────────────────────────────────────────────────────

/** a connect result, held next to the button that produced it rather than raised as a toast. */
private data class Status(val text: String, val isError: Boolean)

/** the form's one real action, centred — a button hard against the left edge reads as a label. */
@Composable
private fun CenteredButton(
    label: String,
    icon: DrawableResource? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled) {
        if (icon != null) {
            AppIcon(
                res = icon,
                size = Dimens.icon,
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary else Colors.menuDisabledText,
            )
            Spacer(Modifier.width(Dimens.gap))
        }
        Text(label)
    }
}

/** what the last attempt said, under the button that made it rather than beside it. */
@Composable
private fun StatusText(status: Status?) {
    if (status == null) return
    Text(
        text = status.text,
        style = MaterialTheme.typography.bodySmall,
        color = if (status.isError) Colors.error else Colors.online,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Dimens.gap),
    )
}

@Composable
private fun LabelledField(
    label: StringResource,
    value: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    hint: StringResource? = null,
) {
    Row(Modifier.fillMaxWidth().padding(top = Dimens.gap), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            modifier = Modifier.width(Dimens.fieldLabelWidth),
        )
        AppTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            hint = hint?.let { stringResource(it) }.orEmpty(),
            keyboardType = keyboardType,
            onSubmit = onSubmit,
        )
    }
}

/** a secondary action that reads as a link rather than as a button. */
@Composable
private fun LinkButton(
    label: String,
    icon: DrawableResource? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        if (icon != null) {
            AppIcon(res = icon, size = Dimens.icon, tint = if (enabled) Colors.accent else Colors.menuDisabledText)
            Spacer(Modifier.width(Dimens.gap))
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** keep only digits, capped — a port can't be anything else. */
private fun String.digits(max: Int): String = filter { it.isDigit() }.take(max)
