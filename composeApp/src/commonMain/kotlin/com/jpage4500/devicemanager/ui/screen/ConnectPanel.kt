package com.jpage4500.devicemanager.ui.screen

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
import com.jpage4500.devicemanager.adb.pairing.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.setting.*
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
 * **One composable, two placements** — the empty state when nothing is connected at all, and the
 * last item under the list when something is. Both show both routes: an "Add a device" row that had
 * to be opened first put one tap in front of everything, on the screen whose entire job is the
 * first connection. See docs/MOBILE-UI.md.
 *
 * The routes are ordered by how likely each is to work rather than by how interesting it is, which
 * is why a Device Manager server comes first: it reaches the desk's whole USB fleet, it needs no
 * protocol this app hasn't already written, and it works against today's Swing app.
 *
 * **A route hands over the whole screen rather than unfolding inside the card.** Either form is
 * taller than the card it used to open in — a QR code, three fields, a list of what mDNS found, a
 * paragraph saying what to do on the other device — and unfolded on a phone the button at the foot
 * of it was below the bottom of the screen. So a tap opens a [BaseDialog], the device sheet's
 * full-screen kind: the card keeps the two rows and nothing else moves.
 */
@Composable
fun ConnectPanel(modifier: Modifier = Modifier) {
    var route by remember { mutableStateOf<Route?>(null) }

    Column(
        modifier
            .padding(Dimens.gap)
            .clip(RoundedCornerShape(Dimens.corner))
            .background(Colors.headerBackground)
            .fillMaxWidth(),
    ) {
        Route.entries.forEachIndexed { index, entry ->
            // a rule between every pair, with no exception to make: nothing opens inside the card
            // any more, so there is no block for a rule to cut in half
            if (index > 0) {
                HorizontalDivider(color = Colors.divider)
            }
            OptionRow(
                icon = entry.icon,
                title = stringResource(entry.title),
                subtitle = stringResource(entry.subtitle),
                enabled = entry.enabled,
                // false and never true: the chevron points right and stays there, which is what a
                // row that opens a screen says — a row that turns its chevron promises to unfold
                open = false,
                onClick = { route = entry },
                // a route that isn't built yet says so where its chevron would be
                trailing = if (entry.enabled) null else ({ Chip(stringResource(Res.string.connect_later)) }),
            )
        }
    }

    // outside the card on purpose: what a route opens is a screen of its own, not the card's next
    // item, and a dialog composed inside the card would still be one in the tree
    route?.let { entry ->
        RouteDialog(
            entry = entry,
            onDismiss = { route = null },
            // a device that has just been connected is the thing the user came here for, so the
            // dialog gets out of the way and its page opens. Selecting it is what shows that page —
            // App collects the selection and puts up the device sheet
            onConnected = { deviceId ->
                route = null
                DeviceManager.select(deviceId)
            },
        )
    }
}

/**
 * one route's form, with the screen to itself.
 *
 * The row's title and subtitle become the dialog's heading, so the form still says which of the two
 * ways in it is; the band across the top is the way back out, as in every other dialog. A
 * [BaseDialog] rather than a hand-rolled overlay for the same reason the device sheet is one — it is
 * what puts this on the dialog stack, so the back gesture closes the form and not the app.
 */
@Composable
private fun RouteDialog(entry: Route, onDismiss: () -> Unit, onConnected: (String) -> Unit) {
    BaseDialog(
        title = stringResource(entry.title),
        icon = entry.icon,
        subtitle = stringResource(entry.subtitle),
        onDismiss = onDismiss,
        horizontalAlignment = Alignment.CenterHorizontally,
        // the forms are bare columns now: the padding they used to carry for the card lives here,
        // in the one place both of them get it from
        contentPadding = PaddingValues(Dimens.gapLarge),
        fullScreen = true,
    ) {
        when (entry) {
            // a computer brings a whole fleet rather than one device, so there is no single page to
            // open — but the form has nothing left to say once it is attached either, so it closes
            // too and the list behind it fills in
            Route.SERVER -> ServerForm(onDone = onDismiss)
            Route.DEVICE -> DeviceForm(onConnected)
        }
    }
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
 * Scanning the QR the desktop shows is the main path and needs nothing typed; the three fields
 * under it are the same thing by hand. They are **always out**, not behind a "manual entry" link:
 * the link hid the only half of this screen that works when the computer is across the room, and a
 * scan fills those same fields anyway, so they are also where a bad scan is corrected.
 *
 * `/api/info` answers before anything is saved either way, so a typo doesn't leave a dead entry
 * behind and the row is labelled with the name the server gave rather than with its address.
 *
 * **Connecting closes the form**, the way reaching a device does. What the user came for is the
 * desk's devices, and they arrive in the list this form is sitting on top of.
 */
@Composable
private fun ServerForm(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    // resolved here rather than in the click handler: stringResource is a composable read
    val notOurQr = stringResource(Res.string.connect_scan_none)

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(SavedServer.DEFAULT_PORT.toString()) }
    var token by remember { mutableStateOf("") }
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
                    // said as a toast rather than under the button, because the form it would be
                    // under is on its way out — a success line nobody is left to read is no line
                    val text = getString(Res.string.connect_connected, it.serverName.orEmpty(), it.deviceCount)
                    ToastManager.showToast(text, success = true)
                    onDone()
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
            // into the fields rather than straight down the wire, so a scan that turns out to be
            // the wrong server can be corrected rather than retried blind
            host = server.host
            port = server.port.toString()
            token = server.token
            connect()
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // above the button and not under it: it names the three clicks to make on the computer,
        // which is work to be done *before* the camera is any use
        CenteredNote(
            stringResource(
                if (scan != null) Res.string.connect_scan_steps else Res.string.connect_scan_unsupported
            )
        )
        Spacer(Modifier.height(Dimens.gap))
        CenteredButton(
            label = stringResource(Res.string.connect_scan_qr),
            icon = Res.drawable.qr_code,
            enabled = scan != null && !busy,
            onClick = { scan?.invoke() },
        )
        Spacer(Modifier.height(Dimens.gapLarge))

        FormHeader(Res.string.connect_section_manual)
        LabelledField(Res.string.connect_host, host, KeyboardType.Uri, { host = it }, ::connect, Res.string.connect_host_hint)
        LabelledField(Res.string.connect_port, port, KeyboardType.Number, { port = it.digits(5) }, ::connect)
        LabelledField(Res.string.connect_token, token, KeyboardType.Password, { token = it }, ::connect)
        Spacer(Modifier.height(Dimens.gapLarge))
        CenteredButton(
            label = stringResource(Res.string.connect),
            enabled = !busy && host.isNotBlank(),
            onClick = ::connect,
        )
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
 * Three sections, and **the QR is the first of them**. It used to be last and behind a button, which
 * put the one route that needs nothing typed behind the two that do. The adb server that QR needs is
 * started on the way in rather than by a tap: on a build that ships the binary there is no decision
 * in it to make, and a code that is simply up is the difference between pairing being a feature and
 * being a thing to find.
 */
@Composable
private fun DeviceForm(onConnected: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    // this app does the pairing cryptography itself where it can; failing that, an attached adb
    // server will do it, and a Device Manager server never can — see PairingSession
    val connections by DeviceManager.connections.collectAsState()
    val canPair = canPairDirectly || connections.any { it.canPair }

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(DirectDeviceTransport.DEFAULT_PORT.toString()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Status?>(null) }
    val found = rememberAdbDevices()

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
                    onConnected(transport.deviceId)
                }
                .onFailure {
                    transport.close()
                    status = Status(it.message ?: "couldn't connect", isError = true)
                }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        FormHeader(Res.string.connect_pair_qr)
        if (canPair) {
            PairPanel(onConnected)
        } else {
            CenteredNote(stringResource(Res.string.connect_pair_needs_adb))
        }
        Spacer(Modifier.height(Dimens.gapLarge))

        FormHeader(Res.string.connect_section_found)
        // a device left in `adb tcpip` mode announces itself, so the common case needs no typing.
        // the browse never *finishes*, so an empty list says it is still looking rather than nothing
        if (found.isEmpty()) {
            CenteredNote(stringResource(Res.string.connect_found_none))
        }
        // tapping one **fills the fields in** rather than connecting: the address lands where it can
        // be seen and corrected, and Connect stays the one thing that dials. A row that connected on
        // touch also gives no way to pick a device and then change the port
        found.forEach { service ->
            OptionRow(
                icon = Res.drawable.device_remote,
                title = service.name,
                subtitle = "${service.host}:${service.port}",
                enabled = !busy,
                onClick = {
                    host = service.host
                    port = service.port.toString()
                    status = null
                },
            )
        }
        Spacer(Modifier.height(Dimens.gapLarge))

        FormHeader(Res.string.connect_section_address)
        LabelledField(Res.string.connect_host, host, KeyboardType.Uri, { host = it }, ::connect, Res.string.connect_host_hint)
        LabelledField(Res.string.connect_port, port, KeyboardType.Number, { port = it.digits(5) }, ::connect)
        Spacer(Modifier.height(Dimens.gapLarge))
        CenteredButton(
            label = stringResource(Res.string.connect),
            enabled = !busy && host.isNotBlank(),
            onClick = ::connect,
        )
        Spacer(Modifier.height(Dimens.gap))
        CenteredNote(stringResource(Res.string.connect_direct_msg))
        StatusText(status)
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
 * The session runs for as long as this is composed, which is now for as long as the dialog is open:
 * a `LaunchedEffect` over a suspending [PairingSession.run] rather than a start/stop pair somebody
 * has to remember to call, so closing the form is what stops the mDNS browse. That is also why
 * there is no "stop waiting" — there is nothing to back out of, only a form to close.
 */
@Composable
private fun PairPanel(onConnected: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember { PairingSession() }
    val state by session.state.collectAsState()
    LaunchedEffect(session) { session.run() }

    // pairing ends by attaching the device, so the dialog closes and its page opens — the same
    // follow-through the typed-address path gets. Keyed on the state so it fires once, and null on
    // the adb-server route, where a fleet arrives rather than one device
    val paired = state as? PairState.Paired
    LaunchedEffect(paired) {
        paired?.deviceId?.let(onConnected)
    }

    val current = state
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // no QR while the server is still in question, none once it turned out not to be there, and
        // none once it has been used — a code pairs once, and one nobody can act on is an invitation
        // to waste a minute scanning it. pairing a second device is a matter of reopening the form
        val live = current !is PairState.Checking &&
            current !is PairState.Paired &&
            !(current is PairState.Failed && !current.canRetry)
        if (live) {
            CenteredNote(stringResource(Res.string.connect_pair_steps))
            Spacer(Modifier.height(Dimens.gap))
            // wide enough for another phone's camera at arm's length, and no wider: this is the top
            // of a form with two more sections under it, not the whole of the screen
            QrImage(session.qrContent, Modifier.fillMaxWidth(0.6f))
            Spacer(Modifier.height(Dimens.gap))
        }

        Text(
            text = when (current) {
                is PairState.Checking -> stringResource(Res.string.connect_pair_checking)
                is PairState.Waiting -> stringResource(Res.string.connect_pair_waiting)
                is PairState.Found -> stringResource(Res.string.connect_pair_found, current.address)
                is PairState.Connecting -> stringResource(Res.string.connect_pair_connecting)
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
        if (current is PairState.Failed && current.canRetry) {
            LinkButton(stringResource(Res.string.connect_pair_retry)) {
                scope.launch { session.retry() }
            }
        }
    }
}

// ── the pieces both forms are made of ───────────────────────────────────────────────────────────

/** a connect result, held next to the button that produced it rather than raised as a toast. */
private data class Status(val text: String, val isError: Boolean)

/** a heading over a group of the form's rows, with the gap that keeps it off them. */
@Composable
private fun FormHeader(title: StringResource) {
    SectionHeader(stringResource(title))
    Spacer(Modifier.height(Dimens.gap))
}

/** what to go and do elsewhere before the button above will work — a form is full of these. */
@Composable
private fun CenteredNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Colors.secondaryText,
        textAlign = TextAlign.Center,
    )
}

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
