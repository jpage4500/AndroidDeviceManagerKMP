package com.jpage4500.devicemanager.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.screen.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.*
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.*

private val log = LoggerConfig.createLogger("AppTray")

// how often the OS theme is re-read for the icon's colour. a native call, so cheap; nothing tells
// us when it changes
private const val THEME_POLL_MS = 5_000L

// a click on the icon while the popup is open closes the popup through focus loss *before* the click
// arrives, and would reopen it. a click this soon after a focus-loss close is that click
private const val REOPEN_GUARD_MS = 400L

/**
 * the menu-bar icon, and every device under it.
 *
 * Ported from the Swing app's tray. **Not Compose's `Tray`**: that builds an AWT `PopupMenu`, which
 * on macOS is a native menu — text and separators only, so no icon per device and no row of buttons.
 * The Swing app hit the same wall and had dorkbox draw its menu with Swing instead. This is that,
 * drawn with Compose: a plain AWT [TrayIcon] with no menu of its own, and a click on it opens
 * [TrayPopup], an undecorated window under the icon (or above it, from a taskbar at the bottom).
 *
 * The icon is [trayIconImage], the device count on an android head. On macOS it is a template image,
 * so the menu bar colours it; everywhere else it follows the OS theme.
 *
 * Only shown while [AppPref.MINIMIZE_TO_TRAY] is on, because a tray icon that can't be dismissed is
 * a tray icon someone has to look at forever.
 */
@Composable
fun AppTray(onQuit: () -> Unit) {
    val enabled by SettingsManager.getBoolAsState(AppPref.MINIMIZE_TO_TRAY)
    if (!enabled) return
    if (!SystemTray.isSupported()) {
        LaunchedEffect(Unit) { log.w("AppTray: no system tray on this platform") }
        return
    }

    val devices by DeviceManager.devices.collectAsState()
    // outlives the popup, so a screenshot started from it isn't cancelled when it closes
    val scope = rememberCoroutineScope()

    val iconColor by produceState(trayIconColor()) {
        while (true) {
            delay(THEME_POLL_MS)
            value = trayIconColor()
        }
    }

    // where the click that opened the popup was, on screen; null while it's closed
    var anchor by remember { mutableStateOf<Point?>(null) }
    var closedAt by remember { mutableStateOf(0L) }

    val trayIcon = remember {
        TrayIcon(trayIconImage(devices.size, iconColor)).apply { isImageAutoSize = true }
    }

    DisposableEffect(trayIcon) {
        val listener = object : MouseAdapter() {
            // a mac menu opens on the press; Windows and Linux open theirs on the release
            override fun mousePressed(e: MouseEvent) {
                if (isMac()) toggle(e.locationOnScreen)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (!isMac()) toggle(e.locationOnScreen)
            }

            fun toggle(at: Point) {
                when {
                    anchor != null -> anchor = null
                    System.currentTimeMillis() - closedAt < REOPEN_GUARD_MS -> Unit
                    else -> anchor = at
                }
            }
        }
        trayIcon.addMouseListener(listener)
        runCatching { SystemTray.getSystemTray().add(trayIcon) }
            .onFailure { log.e("AppTray: add: ${it.message}") }
        onDispose {
            trayIcon.removeMouseListener(listener)
            SystemTray.getSystemTray().remove(trayIcon)
        }
    }

    LaunchedEffect(devices.size, iconColor) {
        trayIcon.image = trayIconImage(devices.size, iconColor)
    }
    val tooltip = stringResource(Res.string.tray_tooltip, devices.size)
    LaunchedEffect(tooltip) { trayIcon.toolTip = tooltip }

    anchor?.let { at ->
        key(at) {
            TrayPopup(
                anchor = at,
                devices = devices,
                scope = scope,
                onDismiss = {
                    anchor = null
                    closedAt = System.currentTimeMillis()
                },
                onQuit = onQuit,
            )
        }
    }
}

/**
 * the popup: Open, the devices with their four actions, Quit.
 *
 * An undecorated dialog rather than a window, because an ownerless dialog gets no taskbar button on
 * Windows. It closes itself when it loses focus, the way a menu closes on a click elsewhere, and on
 * Esc. Sized to its content, up to the height of the screen it opened on, past which the device
 * list scrolls — 45 rows don't fit under a menu bar.
 */
@Composable
private fun TrayPopup(
    anchor: Point,
    devices: List<Device>,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onQuit: () -> Unit,
) {
    val screen = remember(anchor) { usableBoundsAt(anchor) }
    val inset = Dimens.trayShadowInset
    val margin = Dimens.trayScreenMargin
    val maxHeight = (screen.height.dp - inset * 2 - margin * 2).coerceAtLeast(Dimens.trayRowHeight * 4)
    val state = rememberDialogState(
        position = WindowPosition(
            (anchor.x - inset.value.toInt()).dp,
            (if (opensDown(anchor, screen)) screen.y else anchor.y).dp,
        ),
        size = DpSize(Dimens.trayWidth + inset * 2, Dp.Unspecified),
    )
    val currentDismiss by rememberUpdatedState(onDismiss)

    DialogWindow(
        onCloseRequest = onDismiss,
        state = state,
        title = "",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        DisposableEffect(window) {
            // placed again whenever it's sized, which with an unspecified height is once the content
            // has been measured — and again if a device arriving makes it taller
            val placer = object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = place(window, anchor, screen)
                override fun componentShown(e: ComponentEvent) = place(window, anchor, screen)
            }
            val focus = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent) = Unit
                override fun windowLostFocus(e: WindowEvent) = currentDismiss()
            }
            window.addComponentListener(placer)
            window.addWindowFocusListener(focus)
            place(window, anchor, screen)
            // a click on a menu-bar icon doesn't activate the app, and a window that never had focus
            // never loses it — so it takes focus to have something to lose
            window.toFront()
            window.requestFocus()
            onDispose {
                window.removeComponentListener(placer)
                window.removeWindowFocusListener(focus)
            }
        }

        AppTheme {
            TrayMenu(devices, scope, maxHeight, onDismiss, onQuit)
        }
    }
}

@Composable
private fun TrayMenu(
    devices: List<Device>,
    scope: CoroutineScope,
    maxHeight: Dp,
    onDismiss: () -> Unit,
    onQuit: () -> Unit,
) {
    val servers by ServerManager.servers.collectAsState()
    val connectedKeys by ServerManager.connectedKeys.collectAsState()
    val menu = remember(onDismiss) { AppMenuScope(onDismiss) }

    // this computer's devices first, then each connected server's under its name, as the Swing app
    // lists them — each group by name
    val byName = compareBy(String.CASE_INSENSITIVE_ORDER, Device::displayName)
    val connected = servers.filter { it.key in connectedKeys }.sortedBy { it.displayName.lowercase() }
    val serverConnections = connected.map { it.connectionId }.toSet()
    val local = devices.filter { it.connectionId !in serverConnections }.sortedWith(byName)
    val byConnection = devices.groupBy { it.connectionId }

    val shape = RoundedCornerShape(Dimens.menuCorner)
    Column(
        Modifier
            .padding(Dimens.trayShadowInset)
            .width(Dimens.trayWidth)
            .heightIn(max = maxHeight)
            .shadow(Dimens.trayShadowInset, shape)
            .clip(shape)
            .background(Colors.menuBg)
            .border(Dp.Hairline, Colors.menuBorder, shape)
            .padding(vertical = Dimens.menuVerticalPadding),
    ) {
        menu.Item(
            stringResource(Res.string.tray_open),
            leading = { AppIcon(Res.drawable.open, tint = it) },
        ) { showDevices() }
        menu.Separator()

        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            local.forEach { device ->
                key(device.id) { TrayDeviceRow(device, scope, onDismiss) }
            }
            connected.forEach { server ->
                key(server.key) {
                    menu.Header(stringResource(Res.string.tray_server, server.displayName))
                    byConnection[server.connectionId].orEmpty().sortedWith(byName).forEach { device ->
                        key(device.id) { TrayDeviceRow(device, scope, onDismiss) }
                    }
                }
            }
        }

        menu.Separator()
        menu.Item(
            stringResource(Res.string.tray_quit),
            leading = { AppIcon(Res.drawable.power, tint = it) },
        ) { onQuit() }
    }
}

/**
 * one device: its status icon and name, then Mirror, Browse, Logs and Screenshot.
 *
 * A menu row with buttons on the end, as `TrayUiFactory` drew it in the Swing app. The row
 * highlights as a menu item does and a click on it away from the buttons opens the device list; a
 * device that isn't ready can't be acted on, so its row is drawn disabled.
 */
@Composable
private fun TrayDeviceRow(device: Device, scope: CoroutineScope, onDismiss: () -> Unit) {
    val enabled = device.isReady
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlighted = hovered && enabled
    val contentColor = when {
        !enabled -> Colors.menuDisabledText
        highlighted -> Colors.menuHighlightText
        else -> Colors.primaryText
    }

    fun act(action: () -> Unit) {
        onDismiss()
        action()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.menuItemInset)
            .height(Dimens.trayRowHeight)
            .clip(RoundedCornerShape(Dimens.menuItemCorner))
            .background(if (highlighted) Colors.menuHighlight else Colors.menuBg)
            .hoverable(interaction, enabled = enabled)
            .plainClickable(enabled = enabled) { act(::showDevices) }
            .padding(start = Dimens.menuTextPadding, end = Dimens.menuItemInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            res = if (device.isWireless) Res.drawable.device_remote else Res.drawable.android,
            // the status colour even on the highlight: it's what says the device is there
            tint = stateColor(device.state),
        )
        Spacer(Modifier.width(Dimens.menuIconSpacing))
        Text(
            text = device.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = Dimens.menuTextSize,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Dimens.menuIconSpacing))
        TrayButton(Res.drawable.mirror, Res.string.action_mirror, enabled, contentColor) {
            act { MirrorLauncher.mirror(device.id) }
        }
        TrayButton(Res.drawable.browse, Res.string.action_browse, enabled, contentColor) {
            act {
                PageManager.openFiles(device.id)
                WindowManager.bringToFront()
            }
        }
        TrayButton(Res.drawable.logs, Res.string.action_view_logs, enabled, contentColor) {
            act {
                PageManager.openLogs(listOf(device.id))
                WindowManager.bringToFront()
            }
        }
        TrayButton(Res.drawable.screenshot, Res.string.action_screenshot, enabled, contentColor) {
            act { scope.launch { DeviceManager.saveScreenshot(device) } }
        }
    }
}

/** one of a device row's actions: an icon with a hover of its own, inside the row's. */
@Composable
private fun TrayButton(
    icon: DrawableResource,
    label: StringResource,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .padding(start = Dimens.trayButtonGap)
            .size(Dimens.trayButton)
            .clip(RoundedCornerShape(Dimens.menuItemCorner))
            .background(if (hovered && enabled) tint.copy(alpha = 0.18f) else Color.Transparent)
            .hoverable(interaction, enabled = enabled)
            .plainClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, tint = tint, contentDescription = stringResource(label))
    }
}

/** the device list in front, on its Devices page — the tray's Open, and a click on a device row. */
private fun showDevices() {
    PageManager.openDevices()
    WindowManager.bringToFront()
}

/** true when the click was in the top half of its screen — a menu bar — so the popup hangs below. */
private fun opensDown(anchor: Point, screen: Rectangle): Boolean = anchor.y < screen.centerY

/** the part of the screen holding [point] that windows may use: less the menu bar, dock or taskbar. */
private fun usableBoundsAt(point: Point): Rectangle {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val config = env.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(point) }
        ?: env.defaultScreenDevice.defaultConfiguration
    val bounds = config.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
    )
}

/**
 * the popup's corner, from where the icon was clicked: its left edge at the click, under the menu
 * bar or above the taskbar, and pulled back onto the screen at either side.
 *
 * AWT coordinates are the same logical units Compose's dp map to on a desktop window, which is why
 * the [Dimens] values are used as plain numbers here.
 */
private fun place(window: Window, anchor: Point, screen: Rectangle) {
    val inset = Dimens.trayShadowInset.value.toInt()
    val margin = Dimens.trayScreenMargin.value.toInt()
    // the window is wider than the menu by the shadow's band on each side; it's the menu that lines up
    val minX = screen.x + margin - inset
    val maxX = screen.x + screen.width - margin + inset - window.width
    val x = (anchor.x - inset).coerceAtMost(maxX).coerceAtLeast(minX)
    val y = if (opensDown(anchor, screen)) {
        screen.y
    } else {
        (screen.y + screen.height - window.height).coerceAtLeast(screen.y)
    }
    if (window.x != x || window.y != y) window.setLocation(x, y)
}
