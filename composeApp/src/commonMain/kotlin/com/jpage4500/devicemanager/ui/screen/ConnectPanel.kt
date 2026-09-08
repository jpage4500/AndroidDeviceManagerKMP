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
 * That is what this section is *for*, and only half of it exists. Showing a QR for the other device
 * to scan works today, but the pairing behind it is still done by an adb server — and there is no
 * address field, because typing one would go through that server too. Reaching another phone with
 * nothing else involved needs the direct `adbd` transport, which is not written; see
 * docs/MOBILE-UI.md for what that costs.
 */
@Composable
private fun DeviceForm() {
    val transport by DeviceManager.transport.collectAsState()
    var pairing by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.rowPadding).padding(bottom = Dimens.gapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (pairing) {
            PairPanel(onDone = { pairing = false })
        } else {
            CenteredButton(
                label = stringResource(Res.string.connect_pair_qr),
                icon = Res.drawable.qr_code,
                // pairing needs an adb server behind us to do the cryptography, so the button is
                // only offered where it can work — see PairingSession
                enabled = transport.canPair,
                onClick = { pairing = true },
            )
            Spacer(Modifier.height(Dimens.gap))
            Text(
                text = stringResource(
                    if (transport.canPair) Res.string.connect_pair_steps else Res.string.connect_pair_needs_adb
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }

        // there is deliberately no address field here. Typing one would reach the device through a
        // desk's adb server, which is the opposite of what this section is for: it exists to reach
        // another phone with no computer in the middle, and that needs the direct adbd transport
        Spacer(Modifier.height(Dimens.gap))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.connect_direct_later),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.menuDisabledText,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Dimens.gap))
            Chip(stringResource(Res.string.connect_later))
        }
    }
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
