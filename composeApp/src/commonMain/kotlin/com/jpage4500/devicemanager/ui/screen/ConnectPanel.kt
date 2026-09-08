package com.jpage4500.devicemanager.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
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
    val servers by ServerManager.servers.collectAsState()
    val activeKey by ServerManager.activeKey.collectAsState()

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

        if (servers.isNotEmpty()) {
            SectionLabel(stringResource(Res.string.connect_saved))
            servers.asReversed().forEach { server ->
                SavedServerRow(server = server, active = server.key == activeKey)
            }
            HorizontalDivider(color = Colors.divider)
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
 * An expanded section is drawn as a single block — its own ground, a rounded edge and an accent
 * stripe down the left of both halves — because a form that simply appears below a row reads as the
 * next item in the list rather than as that row's contents.
 */
@Composable
private fun RouteBlock(entry: Route, open: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.corner)
    val stripe = Colors.accent
    val stripeWidth = with(LocalDensity.current) { Dimens.accentStripe.toPx() }
    val background by animateColorAsState(
        if (open) Colors.listBg else Color.Transparent,
        label = "blockBackground",
    )

    Column(
        Modifier
            .fillMaxWidth()
            // the same inset open or shut: making it conditional moved the icon and the title down
            // and to the right at the instant the form appeared, which read as a stutter
            .padding(Dimens.gap / 2)
            .clip(shape)
            .background(background)
            // painted rather than laid out: the stripe has to be as tall as the row *and* the form,
            // and a sibling in a Row can't know that height while the form is still animating
            .drawBehind { if (open) drawRect(stripe, size = Size(stripeWidth, size.height)) },
    ) {
        OptionRow(
            icon = entry.icon,
            title = stringResource(entry.title),
            subtitle = stringResource(entry.subtitle),
            enabled = entry.enabled,
            open = open,
            onClick = onToggle,
        )
        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            when (entry) {
                Route.SERVER -> ServerForm()
                Route.DEVICE -> DeviceForm()
                // pairing is drawn, disabled — the shape is real and the reason it doesn't work yet
                // is on the row itself. see docs/MOBILE-UI.md
                Route.PAIR -> Unit
            }
        }
    }
}

/** the three ways in, in the order the panel offers them. */
private enum class Route(
    val icon: DrawableResource,
    val title: StringResource,
    val subtitle: StringResource,
    val enabled: Boolean = true,
) {
    SERVER(Res.drawable.server, Res.string.connect_server, Res.string.connect_server_msg),
    DEVICE(Res.drawable.device_remote, Res.string.connect_device_by_ip, Res.string.connect_device_by_ip_msg),
    PAIR(Res.drawable.qr_code, Res.string.connect_pair_qr, Res.string.connect_pair_qr_msg, enabled = false),
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
    val paste = rememberClipboardPaste()
    // resolved here rather than in the click handler: stringResource is a composable read
    val nothingPasted = stringResource(Res.string.connect_paste_none)

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

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.rowPadding).padding(bottom = Dimens.gapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // the camera is the way in that needs no typing at all — the desktop shows a QR of the same
        // connection string its Share Server button copies. Drawn as the main action even while it
        // is unbuilt, because that is where it belongs and nothing has to move when it lands
        CenteredButton(
            label = stringResource(Res.string.connect_scan_qr),
            icon = Res.drawable.qr_code,
            enabled = false,
            onClick = {},
        )
        Spacer(Modifier.height(Dimens.gap))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.connect_scan_qr_msg),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Dimens.gap))
            Chip(stringResource(Res.string.connect_later))
        }

        Spacer(Modifier.height(Dimens.gap))
        // pasting the connection string is the same one-step path without a camera, so it connects
        // straight away rather than only filling the fields in
        LinkButton(stringResource(Res.string.connect_paste), Res.drawable.paste, enabled = !busy) {
            val server = SavedServer.fromConnectionString(paste())
            if (server == null) {
                status = Status(nothingPasted, isError = true)
            } else {
                host = server.host
                port = server.port.toString()
                token = server.token
                // reveal what was pasted, so a failure can be corrected rather than retried blind
                manual = true
                connect()
            }
        }

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
 * a device at an address.
 *
 * what this actually does depends on what the app is reading: against an adb server it is
 * `host:connect`, and the server dials the device on our behalf. Against a Device Manager server
 * there is no endpoint for it, and the transport says so rather than pretending.
 */
@Composable
private fun DeviceForm() {
    val scope = rememberCoroutineScope()
    val last = remember { WirelessManager.lastAddress() }

    var host by remember { mutableStateOf(last?.host ?: "") }
    var port by remember { mutableStateOf((last?.port ?: WirelessDevice.DEFAULT_PORT).toString()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Status?>(null) }

    fun connect() {
        if (busy || host.isBlank()) return
        busy = true
        status = null
        scope.launch {
            val atPort = port.toIntOrNull() ?: WirelessDevice.DEFAULT_PORT
            DeviceManager.connect(host.trim(), atPort)
                .onSuccess {
                    // the test-data address isn't a device, so it doesn't go in the remembered list
                    if (host.trim() != FakeTransport.HOST) WirelessManager.add(host.trim(), atPort)
                    status = Status(it, isError = false)
                }
                .onFailure { status = Status(it.message ?: "couldn't connect", isError = true) }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.rowPadding).padding(bottom = Dimens.gapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LabelledField(Res.string.connect_host, host, KeyboardType.Uri, { host = it }, ::connect, Res.string.connect_ip_hint)
        LabelledField(Res.string.connect_port, port, KeyboardType.Number, { port = it.digits(5) }, ::connect)
        Spacer(Modifier.height(Dimens.gapLarge))
        CenteredButton(
            label = stringResource(Res.string.connect),
            enabled = !busy && host.isNotBlank(),
            onClick = ::connect,
        )
        StatusText(status)
    }
}

@Composable
private fun SavedServerRow(server: SavedServer, active: Boolean) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchRowHeight)
            .padding(horizontal = Dimens.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            res = Res.drawable.server,
            size = Dimens.iconLarge,
            tint = if (active) Colors.online else Colors.offline,
        )
        Spacer(Modifier.width(Dimens.rowPadding))
        Column(Modifier.weight(1f)) {
            Text(server.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                server.key,
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            enabled = !busy,
            onClick = {
                if (active) {
                    ServerManager.disconnect()
                } else {
                    busy = true
                    scope.launch {
                        ServerManager.connect(server).onFailure {
                            ToastManager.showToast(it.message ?: "couldn't connect", success = false)
                        }
                        busy = false
                    }
                }
            },
        ) {
            Text(
                stringResource(if (active) Res.string.disconnect else Res.string.connect),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = { ServerManager.forget(server.key) }) {
            Text(stringResource(Res.string.connect_forget), style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Colors.secondaryText,
        modifier = Modifier.padding(horizontal = Dimens.rowPadding, vertical = Dimens.gap),
    )
}

@Composable
private fun OptionRow(
    icon: DrawableResource,
    title: String,
    subtitle: String?,
    enabled: Boolean = true,
    open: Boolean? = null,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Colors.accent else Colors.menuDisabledText
    // the chevron turns rather than swapping icon, so the row says which way it is going
    val turn by animateFloatAsState(if (open == true) 90f else 0f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchRowHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.rowPadding, vertical = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(res = icon, size = Dimens.iconLarge, tint = tint)
        Spacer(Modifier.width(Dimens.rowPadding))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Colors.primaryText else Colors.menuDisabledText,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.secondaryText,
                )
            }
        }
        when {
            !enabled -> Chip(stringResource(Res.string.connect_later))
            open != null -> AppIcon(
                res = Res.drawable.arrow_right,
                modifier = Modifier.rotate(turn),
                size = Dimens.icon,
                tint = Colors.secondaryText,
            )
        }
    }
}

/** keep only digits, capped — a port can't be anything else. */
private fun String.digits(max: Int): String = filter { it.isDigit() }.take(max)
