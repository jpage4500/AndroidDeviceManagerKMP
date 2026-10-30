package com.jpage4500.devicemanager.ui.shell

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * how wide the sidebar is, and how a drag of its edge changes that.
 *
 * [raw] is what the drag left — under [Dimens.sidebarSnap] it means the rail, anything else is
 * clamped to the sidebar's range. Keeping the raw number is what lets one drag go into the rail and
 * back out again without letting go.
 */
@Stable
class SidebarWidth {
    var raw by mutableStateOf(SettingsManager.getInt(AppPref.SIDEBAR_WIDTH).dp)
        private set

    // where the rail goes back to
    private var lastWide = raw.takeIf { it >= Dimens.sidebarSnap } ?: Dimens.sidebarWidth

    // a drag reports every pixel; the settings file only needs where it stopped
    private val write = Debouncer()

    val rail: Boolean get() = raw < Dimens.sidebarSnap
    val shown: Dp get() = if (rail) Dimens.sidebarRail else raw.coerceIn(Dimens.sidebarMin, Dimens.sidebarMax)

    fun drag(to: Dp) = set(to.coerceIn(0.dp, Dimens.sidebarMax))

    /** a drag that lets go between the snap and the minimum settles at the minimum. */
    fun dragEnded() {
        if (!rail) set(raw.coerceIn(Dimens.sidebarMin, Dimens.sidebarMax))
    }

    fun toggle() = set(if (rail) lastWide else Dimens.sidebarRail)

    private fun set(next: Dp) {
        raw = next
        if (next >= Dimens.sidebarSnap) lastWide = next.coerceIn(Dimens.sidebarMin, Dimens.sidebarMax)
        write.submit { SettingsManager.setInt(AppPref.SIDEBAR_WIDTH, next.value.toInt()) }
    }
}

/**
 * the drag on the sidebar's edge, watched from the row that holds both panes.
 *
 * **On the row, not on a handle at the edge**, because the edge moves: a handle that is itself the
 * thing being dragged reads the pointer in its own coordinates, and when the sidebar snaps to the
 * rail the handle jumps 150dp under a pointer that hasn't moved — which reads as a 150dp drag, and is
 * why the first version could drag into the rail and never back out. The row doesn't move, so the
 * edge simply follows the pointer, offset by where on the edge it was grabbed.
 *
 * Seen on the `Initial` pass so a press on the edge is claimed before the page or the sidebar under
 * it acts on it. A press that doesn't travel past touch slop is a click, and two of them inside the
 * double-tap timeout toggle the rail.
 */
fun Modifier.sidebarEdge(width: SidebarWidth): Modifier = pointerInput(width) {
    var lastTapAt = 0L
    val reach = Dimens.splitterHandle.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val edge = width.shown.toPx()
        // a little more of the grab sits on the sidebar's side, where the cursor shows
        if (down.position.x !in (edge - reach)..(edge + reach / 2)) return@awaitEachGesture
        down.consume()
        val grab = edge - down.position.x
        var dragging = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                change.consume()
                break
            }
            if (!dragging && abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) dragging = true
            if (dragging) width.drag((change.position.x + grab).toDp())
            change.consume()
        }
        when {
            dragging -> {
                width.dragEnded()
                lastTapAt = 0L
            }
            down.uptimeMillis - lastTapAt < viewConfiguration.doubleTapTimeoutMillis -> {
                lastTapAt = 0L
                width.toggle()
            }
            else -> lastTapAt = down.uptimeMillis
        }
    }
}

/**
 * the sidebar: a light list when there's room, a dark rail of icons over labels when there isn't.
 *
 * Both after EngageAdminKMP's, so the two apps read as one family: the open page is a raised white
 * card with its icon in the brand colour, counts sit in pills, and on the rail the logo is what
 * expands it again.
 */
@Composable
fun Sidebar(shell: ShellState, width: SidebarWidth, onSettings: () -> Unit) {
    val devices by DeviceManager.devices.collectAsState()
    val runs by ScriptManager.runs.collectAsState()
    val items = listOf(
        NavEntry(Page.DEVICES, Res.drawable.android, stringResource(Res.string.devices), devices.size),
        NavEntry(Page.FILES, Res.drawable.folder, stringResource(Res.string.page_files), shell.fileTabs.size),
        NavEntry(Page.LOGS, Res.drawable.logs, stringResource(Res.string.page_logs), shell.logTabs.size, live = shell.logTabs.isNotEmpty()),
    ) + listOfNotNull(
        // desktop only — a phone has no scripts folder to run
        if (ScriptHost.supported) {
            val running = runs.values.count { it.running }
            NavEntry(Page.SCRIPTS, Res.drawable.script, stringResource(Res.string.page_scripts), running, live = running > 0)
        } else {
            null
        },
    )
    val settingsLabel = stringResource(Res.string.action_settings)

    Box(Modifier.width(width.shown).fillMaxHeight()) {
        if (width.rail) {
            Rail(items, shell, onExpand = width::toggle, settingsLabel = settingsLabel, onSettings = onSettings)
        } else {
            Expanded(items, shell, onCollapse = width::toggle, settingsLabel = settingsLabel, onSettings = onSettings)
        }
        // the edge: a hairline, and the resize cursor over the part of it a drag can start from.
        // the drag itself is Modifier.sidebarEdge, on the row that holds this
        VerticalDivider(Modifier.align(Alignment.CenterEnd), color = if (width.rail) Colors.railBg else Colors.sidebarBorder)
        Box(Modifier.align(Alignment.CenterEnd).width(Dimens.splitterHandle).fillMaxHeight().resizeCursor())
    }
}

private class NavEntry(
    val page: Page,
    val icon: DrawableResource,
    val label: String,
    val count: Int,
    val live: Boolean = false,
)

@Composable
private fun Expanded(
    items: List<NavEntry>,
    shell: ShellState,
    onCollapse: () -> Unit,
    settingsLabel: String,
    onSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Colors.sidebarBg)
            .padding(horizontal = Dimens.sidebarPadding, vertical = Dimens.sidebarPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.sidebarPadding),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = Dimens.liveDot), verticalAlignment = Alignment.CenterVertically) {
            LogoMark(Dimens.brandMark)
            Spacer(Modifier.width(Dimens.navGap))
            Text(
                stringResource(Res.string.sidebar_title),
                fontSize = Dimens.brandTextSize,
                fontWeight = FontWeight.SemiBold,
                color = Colors.primaryText,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconSquare(Res.drawable.menu, stringResource(Res.string.sidebar_collapse), onCollapse)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.hairline * 2)) {
            items.forEach { item ->
                NavRow(
                    icon = item.icon,
                    label = item.label,
                    count = item.count.takeIf { it > 0 }?.toString(),
                    live = item.live,
                    selected = shell.page == item.page,
                    onClick = { shell.page = item.page },
                )
            }
        }

        HorizontalDivider(color = Colors.sidebarBorder)
        Column {
            NavRow(Res.drawable.settings, settingsLabel, count = null, selected = false, onClick = onSettings)
            Text(
                "v$APP_VERSION",
                style = MaterialTheme.typography.labelSmall,
                color = Colors.secondaryText,
                modifier = Modifier.padding(start = Dimens.navGap, top = Dimens.liveDot),
            )
        }
    }
}

@Composable
private fun NavRow(
    icon: DrawableResource,
    label: String,
    count: String?,
    selected: Boolean,
    onClick: () -> Unit,
    live: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Dimens.navCorner),
        color = when {
            selected -> Colors.listBg
            hovered -> Colors.sidebarBorder.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
        shadowElevation = if (selected) Dimens.hairline else 0.dp,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().height(Dimens.navItemHeight),
    ) {
        Row(Modifier.padding(horizontal = Dimens.navGap), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, size = Dimens.navIcon, tint = if (selected) Colors.tabAccent else Colors.secondaryText)
            Spacer(Modifier.width(Dimens.navGap))
            Text(
                label,
                fontSize = Dimens.navTextSize,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Colors.primaryText else Colors.secondaryText,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (live) {
                Box(Modifier.size(Dimens.liveDot).clip(CircleShape).background(Colors.online))
                Spacer(Modifier.width(Dimens.liveDot))
            }
            if (count != null) {
                Text(
                    count,
                    fontSize = Dimens.countTextSize,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) Colors.primaryText else Colors.secondaryText,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Colors.sidebarBorder)
                        .padding(horizontal = Dimens.liveDot + Dimens.hairline, vertical = Dimens.hairline),
                )
            }
        }
    }
}

@Composable
private fun Rail(
    items: List<NavEntry>,
    shell: ShellState,
    onExpand: () -> Unit,
    settingsLabel: String,
    onSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Colors.railBg)
            .padding(vertical = Dimens.sidebarPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.liveDot),
    ) {
        // the logo doubles as the expand button
        val expand = stringResource(Res.string.sidebar_expand)
        CellTooltip(text = { expand }) {
            Box(Modifier.clip(RoundedCornerShape(Dimens.cardCorner)).clickable(onClick = onExpand).padding(Dimens.tabGap)) {
                LogoMark(Dimens.railLogo)
            }
        }
        Spacer(Modifier.height(Dimens.tabGap))
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.tabGap),
        ) {
            items.forEach { item ->
                RailItem(item.icon, item.label, shell.page == item.page, live = item.live) { shell.page = item.page }
            }
        }
        RailItem(Res.drawable.settings, settingsLabel, selected = false, onClick = onSettings)
    }
}

@Composable
private fun RailItem(
    icon: DrawableResource,
    label: String,
    selected: Boolean,
    live: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (selected || hovered) Colors.railTextOn else Colors.railText
    Column(
        Modifier
            .width(Dimens.railItemWidth)
            .clip(RoundedCornerShape(Dimens.cardCorner))
            .background(
                when {
                    selected -> Colors.railSelected
                    hovered -> Colors.railHover
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.liveDot + Dimens.hairline),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.hairline * 3),
    ) {
        Box {
            AppIcon(icon, size = Dimens.railIcon, tint = tint, contentDescription = label)
            if (live) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = Dimens.tabGap, y = -Dimens.hairline * 2)
                        .size(Dimens.liveDot)
                        .clip(CircleShape)
                        .background(Colors.online)
                )
            }
        }
        Text(
            label,
            fontSize = Dimens.railLabelSize,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * the app's own icon — `resources/images/logo.png`, the artwork every launcher icon is generated
 * from, at 256px so the rail's 34dp logo is still sharp at 3x.
 */
@Composable
private fun LogoMark(size: Dp) {
    Image(
        painter = painterResource(Res.drawable.app_logo),
        contentDescription = stringResource(Res.string.sidebar_title),
        modifier = Modifier.size(size),
    )
}

/** a small square icon button: the collapse control in the expanded header. */
@Composable
private fun IconSquare(icon: DrawableResource, label: String, onClick: () -> Unit) {
    CellTooltip(text = { label }) {
        Box(
            Modifier.size(Dimens.tabAdd).clip(RoundedCornerShape(Dimens.navCorner)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, size = Dimens.icon, tint = Colors.secondaryText, contentDescription = label)
        }
    }
}
