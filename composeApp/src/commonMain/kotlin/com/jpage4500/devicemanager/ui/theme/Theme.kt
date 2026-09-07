package com.jpage4500.devicemanager.ui.theme

import androidx.compose.foundation.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*

/**
 * every size in the app, grouped by region with a reason for the number.
 *
 * one home per value: a size that appears twice is a size that will drift.
 */
object Dimens {
    // device list (mobile)
    val rowHeight = 52.dp
    val rowPadding = 12.dp
    val listSpacing = 1.dp

    // a phone row needs a bigger target than a mouse does
    val touchRowHeight = 68.dp

    // device table (desktop)
    // 28.dp holds a battery bar and a 16.dp icon with a hairline to spare; 45 rows is the case
    // this is sized for, and a taller row means fewer of them on screen
    val tableRowHeight = 28.dp
    // a log row is tighter than a device row: there are 30,000 of them and none holds a battery bar
    val logRowHeight = 19.dp
    val filterPaneWidth = 155.dp

    // splitter
    val splitterHandle = 6.dp
    val splitterMin = 90.dp
    val splitterMax = 420.dp
    val headerHeight = 26.dp
    val cellPadding = 6.dp
    // the Swing table's size: FlatLaf is handed `Arial PLAIN 16` as the app's default font
    // (MainApplication.initializeUI) and the table inherits it, header included
    val tableTextSize = 16.sp
    val headerTextSize = 16.sp
    val columnDefault = 100.dp
    val columnMin = 40.dp
    // the divider is a hairline; the drag target around it is not, because a 1px target is missed
    val resizeHandle = 8.dp
    // the stripe is inset from the window edge and rounded, the way a modern list draws it — a
    // full-bleed band reads as a table rule instead
    val rowStripeInset = 6.dp
    val rowStripeCorner = 4.dp

    val gap = 8.dp
    val gapLarge = 16.dp
    val corner = 6.dp

    // desktop windows. the minimum is the same for every kind — it is what the toolbar needs before
    // it starts clipping buttons off the middle
    val minWindowWidth = 520.dp
    val minWindowHeight = 360.dp

    // the size each kind of window opens at the *first* time, before anyone has sized it — after
    // that it is whatever they left it at, remembered per kind. See WindowKind
    val windowWidth = 1000.dp
    val windowHeight = 640.dp
    // a log line is long and the message column is the one that matters
    val logsWindowWidth = 1200.dp
    val logsWindowHeight = 700.dp
    val filesWindowWidth = 900.dp
    val filesWindowHeight = 600.dp
    // a phone's aspect, so a mirrored screen fills the window rather than sitting in a letterbox:
    // as narrow as a window is allowed to be, and tall
    val mirrorWindowWidth = minWindowWidth
    val mirrorWindowHeight = 820.dp
    // Connect, Settings and Device Details are forms rather than tables, and a form read at table
    // width is a column of labels with a screen of nothing after it
    val formWindowWidth = 560.dp
    val formWindowHeight = 620.dp
    val messageWindowWidth = 760.dp
    val messageWindowHeight = 520.dp
    val toolbarHeight = 44.dp
    val statusBarHeight = 28.dp

    /**
     * clearance for the macOS traffic lights, which the toolbar draws *behind* once the title bar is
     * transparent — see `windowChromeInsetStart()`. 78 is the three buttons plus the gap Finder
     * leaves after them.
     */
    val trafficLightInset = 78.dp

    // toolbar
    val toolbarIcon = 22.dp
    val toolbarButtonWidth = 58.dp

    val icon = 16.dp
    val iconLarge = 24.dp

    // battery bar
    val batteryWidth = 22.dp
    val batteryHeight = 11.dp

    val statusDot = 8.dp

    // scrollbar
    val scrollbarThickness = 8.dp
    val scrollbarMinThumb = 24.dp
    val scrollbarInset = 2.dp

    /**
     * how much of the tiled background watermark shows through — enough to see it, not enough to
     * fight with the rows drawn on top. the Swing app's own figure.
     */
    const val backgroundAlpha = 0.10f

    // tooltips. below-right of the pointer rather than under it — 20.dp down clears the hotspot of
    // a standard arrow cursor, so the bubble never sits beneath the thing it is describing
    val tooltipInsetX = 12.dp
    val tooltipInsetY = 20.dp
    val tooltipCorner = 6.dp
    val tooltipShadow = 8.dp
    val tooltipPadding = 8.dp
    val tooltipPaddingSmall = 4.dp
    // a serial or a long custom field is what needs a tooltip; past this it wraps rather than
    // running off the window
    val tooltipMaxWidth = 420.dp

    // context menus. 24.dp per item is what a desktop menu uses — Material's DropdownMenu forces 48
    val menuCorner = 10.dp
    val menuMinWidth = 180.dp
    val menuVerticalPadding = 5.dp
    val menuShadow = 12.dp
    val menuItemHeight = 24.dp
    val menuItemCorner = 5.dp
    val menuItemInset = 5.dp
    val menuTextPadding = 11.dp
    val menuSeparatorInset = 12.dp
    val menuSeparatorSpacing = 5.dp
    val menuIconSpacing = 7.dp
    // what makes IntrinsicSize.Max size a menu to its widest item rather than its widest label
    val menuTrailingGap = 24.dp
    val menuTextSize = 13.sp
}

/**
 * the fonts the app names, as opposed to the ones Material picks.
 *
 * The Swing app puts `Arial PLAIN 16` on everything and the table inherits it, so [table] is the
 * closest a KMP build gets without shipping a font of its own: the platform's default sans **is**
 * Arial on Windows, Helvetica on macOS and Liberation Sans on Linux, and all three are Arial's
 * metrics — a column holds the same amount of a serial as it does there. Named explicitly rather
 * than left to `FontFamily.Default` because a Material default is free to be the system *UI* font,
 * which on macOS is SF and is a wider face at the same size.
 */
object Fonts {
    val table = FontFamily.SansSerif
}

/**
 * every colour the window chrome draws with, as one value.
 *
 * ported from FileExplorerKMP's `Palette`, which is where these exact greys were tuned. Two things
 * about them are worth keeping rather than re-deriving:
 *
 * - **`rowStripe` is a few percent off `listBg` — darker on a light ground and *lighter* on a dark
 *   one.** It can't be produced by inverting: the gap that reads right on white is invisible on
 *   black.
 * - **The scrollbar thumbs flip ground**, black at low alpha on light and white at low alpha on
 *   dark, and at a *lower* alpha there — a light thumb on dark reads heavier than a dark one on
 *   light at the same opacity.
 */
@Immutable
data class Palette(
    /** the one blue: a selected row, a focus ring, a default button. */
    val accent: Color,
    val toolbarBg: Color,
    val toolbarIcon: Color,
    val listBg: Color,
    val rowStripe: Color,
    val rowSelectedInactive: Color,
    val rowSelectedText: Color,
    val headerBg: Color,
    /** a column header while it is being dragged — pressed, the way a JTable's reads. */
    val headerDragged: Color,
    val headerDivider: Color,
    val statusBarBg: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val divider: Color,
    val scrollbarThumb: Color,
    val scrollbarThumbHover: Color,
    val menuBg: Color,
    val menuBorder: Color,
    val menuHighlight: Color,
    val menuHighlightText: Color,
    val menuDisabledText: Color,
    val menuSeparator: Color,
)

val LightPalette = Palette(
    accent = Color(0xFF0A6CFF),
    toolbarBg = Color(0xFFF2F2F3),
    toolbarIcon = Color(0xFF4B4B4F),
    listBg = Color(0xFFFFFFFF),
    // the Swing table's alternate row and header greys — the header is deliberately much darker
    // than a Material one, which is what makes the column titles read as a bar rather than a row
    rowStripe = Color(0xFFF6F6F6),
    rowSelectedInactive = Color(0xFFDCDCDC),
    rowSelectedText = Color(0xFFFFFFFF),
    headerBg = Color(0xFFC5C5C5),
    headerDragged = Color(0xFFACACAC),
    headerDivider = Color(0xFFA8A8A8),
    statusBarBg = Color(0xFFF1F1F3),
    primaryText = Color(0xFF1D1D1F),
    secondaryText = Color(0xFF6E6E73),
    divider = Color(0xFFD6D6D8),
    scrollbarThumb = Color(0x47000000),
    scrollbarThumbHover = Color(0x73000000),
    menuBg = Color(0xFFF5F5F7),
    menuBorder = Color(0x1F000000),
    menuHighlight = Color(0xFF4B8BF5),
    menuHighlightText = Color(0xFFFFFFFF),
    menuDisabledText = Color(0xFFB0B0B4),
    menuSeparator = Color(0x1A000000),
)

/**
 * the dark theme, spelled out in full rather than as a `copy` of the light one — a dark theme
 * changes nearly every entry, and one left behind is a light value on a dark window with nothing to
 * say which.
 */
val DarkPalette = Palette(
    // Apple's systemBlue for dark appearance; the light theme's blue is heavy on a dark ground
    accent = Color(0xFF0A84FF),
    toolbarBg = Color(0xFF323234),
    toolbarIcon = Color(0xFFC7C7CC),
    listBg = Color(0xFF1C1C1E),
    rowStripe = Color(0xFF212123),
    rowSelectedInactive = Color(0xFF3A3A3E),
    rowSelectedText = Color(0xFFFFFFFF),
    headerBg = Color(0xFF2A2A2C),
    // *lighter* than the bar it sits in, not darker: a darker grey on this ground reads as a hole
    headerDragged = Color(0xFF3E3E42),
    headerDivider = Color(0xFF3C3C3F),
    statusBarBg = Color(0xFF2A2A2C),
    // not pure white on pure black — that pair vibrates at small sizes
    primaryText = Color(0xFFE8E8EA),
    secondaryText = Color(0xFF98989D),
    divider = Color(0xFF3A3A3C),
    scrollbarThumb = Color(0x47FFFFFF),
    scrollbarThumbHover = Color(0x73FFFFFF),
    menuBg = Color(0xFF2C2C2E),
    // the alpha colours flip ground rather than just value: black at low alpha would be invisible
    // here, and a light line on dark reads heavier than a dark line on light at the same opacity
    menuBorder = Color(0x26FFFFFF),
    menuHighlight = Color(0xFF0A84FF),
    menuHighlightText = Color(0xFFFFFFFF),
    menuDisabledText = Color(0xFF6A6A6E),
    menuSeparator = Color(0x1AFFFFFF),
)

/**
 * static rather than plain: a theme change should invalidate the whole subtree at once, which is
 * exactly what `staticCompositionLocalOf` does, and it costs nothing to read in between.
 */
val LocalPalette = staticCompositionLocalOf { LightPalette }

/**
 * the app's colors.
 *
 * the device states are fixed values — they mean something regardless of appearance, and both read
 * on either ground. everything about the *chrome* comes from [LocalPalette], so it follows the OS.
 */
object Colors {
    // the Swing app's own device colours, so the two look like the same product side by side
    val online = Color(0xFF188600)
    val offline = Color(0xFF808080)
    val busy = Color(0xFFFB6D08)
    val unauthorized = Color(0xFFFBF708)
    val error = Color(0xFFFF0000)

    // a toast that's only telling you something; the other two reuse `online` and `error`
    val info = Color(0xFF2F6FBF)

    // --- chrome, from the palette in force ---
    val accent: Color @Composable get() = LocalPalette.current.accent
    val toolbarBg: Color @Composable get() = LocalPalette.current.toolbarBg
    val toolbarIcon: Color @Composable get() = LocalPalette.current.toolbarIcon
    val listBg: Color @Composable get() = LocalPalette.current.listBg
    val rowStripe: Color @Composable get() = LocalPalette.current.rowStripe
    val rowSelected: Color @Composable get() = LocalPalette.current.accent

    /** a selection in an unfocused window: still visible, but no longer claiming the keyboard. */
    val rowSelectedInactive: Color @Composable get() = LocalPalette.current.rowSelectedInactive
    val rowSelectedText: Color @Composable get() = LocalPalette.current.rowSelectedText
    val headerBackground: Color @Composable get() = LocalPalette.current.headerBg
    val headerDragged: Color @Composable get() = LocalPalette.current.headerDragged
    val headerDivider: Color @Composable get() = LocalPalette.current.headerDivider
    val statusBarBg: Color @Composable get() = LocalPalette.current.statusBarBg
    val primaryText: Color @Composable get() = LocalPalette.current.primaryText
    val secondaryText: Color @Composable get() = LocalPalette.current.secondaryText
    val divider: Color @Composable get() = LocalPalette.current.divider
    val scrollbarThumb: Color @Composable get() = LocalPalette.current.scrollbarThumb
    val scrollbarThumbHover: Color @Composable get() = LocalPalette.current.scrollbarThumbHover

    val menuBg: Color @Composable get() = LocalPalette.current.menuBg
    val menuBorder: Color @Composable get() = LocalPalette.current.menuBorder
    val menuHighlight: Color @Composable get() = LocalPalette.current.menuHighlight
    val menuHighlightText: Color @Composable get() = LocalPalette.current.menuHighlightText
    val menuDisabledText: Color @Composable get() = LocalPalette.current.menuDisabledText
    val menuSeparator: Color @Composable get() = LocalPalette.current.menuSeparator
}

/**
 * the Material scheme, built from the palette so a Material widget dropped anywhere matches the
 * chrome around it rather than bringing its own purple.
 */
private fun Palette.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = rowSelectedText,
        background = listBg,
        onBackground = primaryText,
        surface = listBg,
        onSurface = primaryText,
        surfaceVariant = headerBg,
        onSurfaceVariant = secondaryText,
        outline = secondaryText,
        outlineVariant = divider,
    )
}

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(darkTheme),
            content = content,
        )
    }
}
