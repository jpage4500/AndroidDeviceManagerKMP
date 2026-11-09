package com.jpage4500.devicemanager.ui.theme

import androidx.compose.foundation.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*

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

    // the label column of a stacked form field — wide enough for "Auth token" at bodySmall
    val fieldLabelWidth = 88.dp

    // the bar down the left of an expanded section, tying its form to the row that opened it
    val accentStripe = 3.dp

    // device table (desktop)
    // one line of 13sp text and a battery bar with room to breathe; 45 rows is the case this is
    // sized for, and a taller row means fewer of them on screen
    val tableRowHeight = 32.dp
    // a log row is tighter than a device row: there are 30,000 of them and none holds a battery bar
    val logRowHeight = 19.dp

    // splitter
    val splitterHandle = 6.dp
    val splitterMin = 90.dp
    val splitterMax = 420.dp
    // EngageAdminKMP's table: a 38dp header of small semibold titles over 13sp rows, so the two apps'
    // tables read the same. The Swing table's 16sp Arial is what this used to copy
    val headerHeight = 38.dp
    val cellPadding = 8.dp
    val tableTextSize = 13.sp
    val headerTextSize = 12.sp
    val sortArrow = 12.dp
    // the column separators in the header stop short of its edges — a mark to grab, not a grid line
    val headerSeparator = 16.dp
    val columnDefault = 100.dp
    // one character and the cell's padding either side — the log's Level column, which only ever
    // holds a letter. Narrower and a column can't be found again to widen
    val columnMin = 24.dp
    // the divider is a hairline; the drag target around it is not, because a 1px target is missed
    val resizeHandle = 8.dp

    val gap = 8.dp
    val gapLarge = 16.dp
    val corner = 6.dp

    // the device sheet's four action tiles. taller than a touch row on purpose: the icon sits over
    // the label rather than beside it, and a square-ish tile is what says "tap me"
    val tileHeight = 82.dp
    val tileIcon = 30.dp
    val tileCorner = 12.dp

    // a section header that opens and shuts. taller than the label needs, because it is now a thing
    // you tap rather than a thing you read past
    val sectionHeaderHeight = 34.dp

    // the column down the right of the device details, holding whatever a row offers: the copy hint
    // on most of them, the rename pencil on the name
    val infoGutter = 36.dp

    /**
     * settings rows and dialogs — see `ui/setting/` and `ui/dialog/`.
     *
     * Two sizes rather than one, keyed off [LocalCompactUi]. DashboardWeatherKMP, where these came
     * from, is a phone and a wall tablet, so its row is a 26.dp icon and a 22.sp label read at arm's
     * length. The same row in a 560.dp desktop form window is cartoonish, and this app's desktop is
     * the workload that matters — so the phone keeps the original numbers and the desktop gets a
     * form line sized like the rest of its chrome.
     */
    val settingPadding: Dp @Composable get() = if (LocalCompactUi.current) 8.dp else 12.dp
    val settingRowPadding: Dp @Composable get() = if (LocalCompactUi.current) 6.dp else 5.dp
    val settingItemGap: Dp @Composable get() = if (LocalCompactUi.current) 10.dp else 8.dp
    val settingIcon: Dp @Composable get() = if (LocalCompactUi.current) 26.dp else 18.dp
    val settingLabelSize: TextUnit @Composable get() = if (LocalCompactUi.current) 20.sp else 14.sp
    val settingValueSize: TextUnit @Composable get() = if (LocalCompactUi.current) 16.sp else 13.sp

    val dialogTitleSize: TextUnit @Composable get() = if (LocalCompactUi.current) 24.sp else 17.sp
    val dialogTitleIcon: Dp @Composable get() = if (LocalCompactUi.current) 32.dp else 22.dp
    val dialogTextSize: TextUnit @Composable get() = if (LocalCompactUi.current) 16.sp else 14.sp
    val dialogButtonSize: TextUnit @Composable get() = if (LocalCompactUi.current) 18.sp else 14.sp
    val dialogCorner = 16.dp
    // how much of the app log the viewer shows at once before it scrolls. deliberately less
    // than the dialog's own ceiling, so the Close button is always on screen under it
    val logViewHeight = 420.dp
    /** how much of the screen a dialog takes on a desktop or a tablet; a phone always fills it. */
    const val dialogWidthFraction = 0.5f
    /** and how much of its height, at most — the rest is what says there is an app behind it. */
    const val dialogHeightFraction = 0.9f

    // desktop windows. the minimum is the same for every kind — it is what the toolbar needs before
    // it starts clipping buttons off the middle
    val minWindowWidth = 520.dp
    val minWindowHeight = 360.dp

    // the size each kind of window opens at the *first* time, before anyone has sized it — after
    // that it is whatever they left it at, remembered per kind. See WindowKind
    // the one window now holds the sidebar and the log and file pages as well as the device table — a
    // log line is long, and this is the size the redesign was drawn at
    val windowWidth = 1280.dp
    val windowHeight = 800.dp
    // a phone's aspect, so a mirrored screen fills the window rather than sitting in a letterbox:
    // as narrow as a window is allowed to be, and tall
    val mirrorWindowWidth = minWindowWidth
    val mirrorWindowHeight = 820.dp
    // Connect, Settings and Device Details are forms rather than tables, and a form read at table
    // width is a column of labels with a screen of nothing after it
    val formWindowWidth = 560.dp
    val formWindowHeight = 620.dp
    // Connect is the Swing ConnectDialog's table (680px of columns) over a row of fields, so it opens
    // at that table's width rather than a form's
    val connectWindowWidth = 720.dp
    val connectWindowHeight = 420.dp
    // the Swing dialogs' own measurements: a 200px QR in a 450px panel, a 600x300 server table
    val qrDialogWidth = 450.dp
    val qrSize = 200.dp
    val serversDialogWidth = 600.dp
    val serversTableHeight = 300.dp
    // Share Devices: a column of right-aligned labels over the client table — shorter than the Swing
    // dialog's 150px so Start Server shows without scrolling; a desk rarely has more than a few clients
    val shareDialogWidth = 600.dp
    val shareClientsHeight = 90.dp

    // the status History dialog: wide enough for a summary and its per-device detail on one line,
    // tall enough for 15 rows — the Swing list's visible count
    val historyDialogWidth = 720.dp
    val historyTableHeight = 380.dp
    val historyFilterWidth = 200.dp
    val shareLabelWidth = 110.dp
    // a dialog that is its own window is a form too, so it opens at the same width. the height caps
    // rather than fills: the window packs to whatever its form needs, and only a long one (the app
    // log) reaches the ceiling and scrolls inside it
    val dialogWindowWidth = formWindowWidth
    val dialogWindowMaxHeight = 640.dp
    // Settings: the Swing dialog was 540x480 with five tabs; seven need the width, and the height is
    // what keeps General's rows on screen without scrolling
    val settingsWindowWidth = 720.dp
    val settingsWindowHeight = 560.dp
    val settingsTabPadding = 10.dp
    val settingsDropdownWidth = 180.dp
    val messageWindowWidth = 760.dp
    val messageWindowHeight = 520.dp
    val toolbarHeight = 44.dp
    val statusBarHeight = 28.dp

    // a toast in the status bar — FileExplorerKMP's pill. capped so a long path can't push the
    // Devices bar's own figures off its edges; the tooltip has the rest
    val statusToastHeight = 20.dp
    val statusToastCorner = 10.dp
    val statusToastPaddingH = 8.dp
    val statusToastSpacing = 5.dp
    val statusToastIcon = 12.dp
    val statusToastActionInset = 2.dp
    val statusToastActionPaddingH = 6.dp
    val statusToastMaxWidth = 520.dp

    // --- the desktop shell — see docs/REDESIGN.md ---

    // the design's resting width, and the range a drag may take it through: 140 still fits "Devices"
    // and its count pill, past 320 the page loses more than the sidebar gains
    val sidebarWidth = 216.dp
    val sidebarMin = 140.dp
    val sidebarMax = 320.dp

    // the rail: EngageAdminKMP's, wide enough for a 60dp item holding an icon over a 10sp label
    val sidebarRail = 72.dp

    // narrower than this is the rail (106dp): halfway between the rail and the minimum,
    // EngageAdminKMP's number — far enough under the minimum that a drag towards narrow doesn't flip
    // to icons the moment it passes it
    val sidebarSnap = (sidebarRail + sidebarMin) / 2
    val railItemWidth = 60.dp
    val railIcon = 20.dp
    val railLabelSize = 10.sp
    val railLogo = 34.dp
    val sidebarPadding = 12.dp
    val navItemHeight = 38.dp
    val navIcon = 18.dp
    val navCorner = 8.dp
    // icon to label, and the label's inset from the item's edge
    val navGap = 10.dp
    val liveDot = 6.dp
    val brandMark = 28.dp
    val pageTitleSize = 20.sp
    val brandTextSize = 15.sp
    val navTextSize = 13.sp
    val countTextSize = 11.sp

    // --- pages: the design's 24px gutter, its header and the controls in it ---
    val pageGutter = 24.dp
    val pageHeaderHeight = 64.dp
    val controlHeight = 34.dp
    val chipHeight = 28.dp
    val chipDot = 6.dp
    val searchWidth = 200.dp
    val selectionBarHeight = 48.dp
    val cardCorner = 10.dp
    // two lines a row — name over model — unless the compact density is on
    val deviceRowHeight = 44.dp
    val stateDot = 8.dp
    // the Devices icon view: a tile is the phone frame with everything inside it, and never
    // narrower than a name needs however small the frame is dragged
    val deviceTileMinWidth = 72.dp
    val deviceIconSliderWidth = 120.dp
    val viewToggleSegment = 32.dp
    // the onboarding cards when nothing is connected
    val onboardingWidth = 980.dp
    val onboardingCardIcon = 34.dp
    // the details panel beside the device table
    val detailsPanelWidth = 300.dp
    // tall enough to read a phone's screen at a glance, short enough to leave the facts on screen
    val previewHeight = 220.dp
    val previewSpinner = 14.dp
    val previewSpinnerLarge = 28.dp
    val previewSpinnerStroke = 2.dp
    val previewOffIcon = 28.dp
    val previewCornerButton = 22.dp
    val detailsTitleSize = 16.sp
    val factLabelWidth = 84.dp
    val checkbox = 16.dp
    val checkboxCorner = 4.dp
    val checkboxTick = 10.dp
    // the log tab's two rows of controls: search and the buttons, then the filters
    val filterBarHeight = 44.dp
    // search takes what the row has left, but never less than room for "tag:OkHttp" and its count
    val logSearchMinWidth = 220.dp
    val searchArrow = 22.dp
    // the design's switch: a 28×16 track and a 12dp thumb
    val switchWidth = 28.dp
    val switchHeight = 16.dp
    val switchThumb = 12.dp
    // Files: the places list down the left, and the folder filter beside the path
    val placesWidth = 190.dp
    val placeRowHeight = 30.dp
    val folderFilterWidth = 180.dp
    // Scripts: the list down the left, and the results table's fixed columns
    val scriptListWidth = 260.dp
    // Commands: the saved and recent list down the left — wider than Scripts', a command is longer
    // than a script's name
    val commandListWidth = 300.dp
    val resultDeviceWidth = 180.dp
    val resultStateWidth = 90.dp
    val resultExitWidth = 56.dp
    val resultTimeWidth = 72.dp
    val lineNumberWidth = 36.dp
    // + Add filter's panel: the design's 360, and tall enough for six filters before it scrolls
    val savedFiltersWidth = 360.dp
    val savedFiltersMaxHeight = 300.dp
    // the filter editor's rules and summary: about eight rules before they scroll
    val filterRulesMaxHeight = 360.dp
    // an App rule's ▾: wide enough for a package name, about ten apps before it scrolls
    val appPickerWidth = 300.dp
    val appPickerMaxHeight = 240.dp
    val pillMaxWidth = 220.dp
    val pillClose = 10.dp
    val levelButton = 24.dp

    // tabs: the open one stands taller than the rest, which is half of how it reads as open
    val tabStripHeight = 42.dp
    val tabHeight = 34.dp
    val tabHeightOpen = 40.dp
    val tabMaxWidth = 230.dp
    // a tab narrower than this can't show a name and its ×; past it the rest go into "N more",
    // which is sized for "12 more"
    val tabMinWidth = 120.dp
    val tabMoreWidth = 96.dp
    // the + device checklist on Logs, Files, Scripts, Commands and Input — the design's "Capture logs from"
    val tabPickerWidth = 280.dp
    val tabAccent = 3.dp
    val tabDot = 8.dp
    val tabBadge = 18.dp
    val tabCorner = 8.dp
    val tabGap = 4.dp
    val tabClose = 24.dp
    val tabCloseIcon = 12.dp
    // the × drawn rather than the circled icon: a cross this wide in a stroke this thick
    val tabCloseCross = 8.dp
    val tabCloseStroke = 1.5.dp
    val tabAdd = 32.dp
    // the open tab's ground covers this much of the strip's rule, and edges are this thick
    val hairline = 1.dp

    /**
     * clearance for the macOS traffic lights, which the toolbar draws *behind* once the title bar is
     * transparent — see `windowChromeInsetStart()`. 78 is the three buttons plus the gap Finder
     * leaves after them.
     */
    val trafficLightInset = 78.dp

    // toolbar
    val toolbarIcon = 22.dp
    val toolbarButtonWidth = 58.dp
    val toolbarButtonPadding = 6.dp

    val icon = 16.dp
    val iconLarge = 24.dp

    // the tap, hold and swipe feedback drawn over a mirrored screen. dp where Animations.java used
    // desktop pixels, and about twice its figures: this is read at arm's length, over a screenshot
    // of whatever the device happens to be showing, and a thin ring over a busy screen is invisible
    val mirrorRing = 60.dp
    val mirrorRingInner = 24.dp
    // the hold's ring is bigger again than the tap's — the size is half of what tells them apart
    val mirrorHoldRing = 92.dp
    val mirrorArrowHead = 64.dp
    val mirrorArrowWidth = 52.dp
    val mirrorSwipeDot = 28.dp
    val mirrorStroke = 8.dp
    val mirrorGlowStroke = 20.dp

    // the bubble a key press raises in the middle of the frame. its text is a ninth of the frame's
    // width, the Swing app's own sizing, and never smaller than this
    val mirrorKeyTextMin = 34.sp
    val mirrorKeyPadding = 26.dp
    val mirrorKeyCorner = 32.dp
    val mirrorKeyGlow = 8.dp

    // the frame's own outline: a screenshot of a white app on a light window has no edge of its own,
    // and without one the mirror reads as part of the app rather than as the device's screen
    val mirrorBorder = 1.dp
    // the bar under it. icons rather than labels, so it needs the height of a touch target and no
    // more — the row of text buttons it replaced was half again as tall for the same four keys
    val mirrorBarHeight = 44.dp
    val mirrorBarButton = 40.dp
    val mirrorBarIcon = 22.dp
    // between the buttons. wider than a form's gap because these are hit without looking, with the
    // device's screen above holding the attention
    val mirrorBarSpacing = 20.dp
    // the field that raises the phone's keyboard, which nobody is meant to see: a focus target has
    // to be laid out to take focus, so it is a dp of nothing rather than no field at all
    val mirrorTypeField = 1.dp
    // the frame rate, small enough to ignore until it is the thing you are watching
    val mirrorFpsSize = 10.sp

    // battery bar
    val batteryWidth = 22.dp
    val batteryHeight = 11.dp

    /**
     * the phone row's whole battery block: the bar, the gap, the charging slot, and room for "100%"
     * over the top of them.
     *
     * fixed, so the bar sits at the same place in every row — left to itself the block was as wide
     * as its widest part, which meant "100%" pushed it further out than "99%" and a device that
     * started charging shifted its own bar sideways.
     */
    val batteryGaugeWidth = 44.dp

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
    // and 44 on a phone, which is a thumb: this menu is the desktop's context menu, and a 24.dp row
    // is a miss rather than a choice
    val menuItemHeight: Dp @Composable get() = if (LocalCompactUi.current) 44.dp else 24.dp
    val menuItemCorner = 5.dp
    val menuItemInset = 5.dp
    val menuTextPadding = 11.dp
    val menuSeparatorInset = 12.dp
    val menuSeparatorSpacing = 5.dp
    val menuIconSpacing = 7.dp
    // what makes IntrinsicSize.Max size a menu to its widest item rather than its widest label
    val menuTrailingGap = 24.dp
    val menuTextSize: TextUnit @Composable get() = if (LocalCompactUi.current) 17.sp else 13.sp
    /** a heading over a group of items — smaller than the items, the way a section label reads. */
    val menuHeaderSize: TextUnit @Composable get() = if (LocalCompactUi.current) 14.sp else 11.sp

    // the menu-bar popup. a device row is a menu row with four buttons on the end, so it's taller
    // than a plain item by enough to give each button a target bigger than its 16dp icon
    val trayWidth = 320.dp
    val trayRowHeight = 28.dp
    val trayButton = 24.dp
    val trayButtonGap = 2.dp
    // room around the menu inside its transparent window for the shadow to fall into
    val trayShadowInset = 8.dp
    // kept clear of the screen's edge, and of the menu bar or taskbar it opens from
    val trayScreenMargin = 4.dp
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
    /**
     * the ground behind a row that heads a group of other rows — a computer in the phone's device
     * list, and anything else that owns the rows under it.
     *
     * A step off [listBg] and no more: far enough that a computer doesn't read as one of its own
     * devices, nowhere near [headerBg], which is a desktop table's column bar and would read as a
     * selection on a list. The device rows inside a group keep painting [listBg], so it is the
     * *devices* that stay flat and the group around them that lifts.
     */
    val groupBg: Color,
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
    /** the band behind a dialog's title, so the heading reads as a bar rather than a first row. */
    val dialogTitleBg: Color,
    /**
     * a setting's current value.
     *
     * red on purpose, and the one place in the app that uses red for something that isn't wrong: in
     * a list of thirty rows the value is the only part anyone scans, and the weather app this came
     * from proved it reads faster than the grey a form would use. Lighter on a dark ground — pure
     * red on near-black is the pair that vibrates.
     */
    val settingValue: Color,
    /** the desktop sidebar's ground, a step off the page so the two read as separate panes. */
    val sidebarBg: Color,
    /** the rule between the sidebar and the page, and around a tab and the panel it opens onto. */
    val sidebarBorder: Color,
    /** the sidebar item for the page that's showing. */
    val navSelected: Color,
    /** a tab that isn't open — grey, so the white open one is the one that reads. */
    val tabIdle: Color,
    /**
     * the green cap on the open tab, and the switches and checks of the redesign.
     *
     * darker than the Android green the primary button uses: `#3DDC84` is 1.6:1 on white and can't
     * carry a 3dp line, this is 3.4:1 — enough for a mark that isn't text.
     */
    val tabAccent: Color,
    /** the table's header: a pale band of muted titles, EngageAdminKMP's. */
    val tableHeaderBg: Color,
    val tableHeaderText: Color,
    /** the hairline under every table row, and the short separators between header titles. */
    val tableRule: Color,
    /** a saved log filter that's on, as a pill in the filter bar. */
    val pillBg: Color,
    val pillBorder: Color,
    /** a selected table row — a green tint with dark text, not the blue band with white text. */
    val rowSelectedBg: Color,
    /** the bar over the device table while something is selected, and its edge. */
    val selectionBar: Color,
    val selectionBarBorder: Color,
    /** a filter chip that's on: the inverse of the page. */
    val chipOn: Color,
    val chipOnText: Color,
)

val LightPalette = Palette(
    accent = Color(0xFF0A6CFF),
    toolbarBg = Color(0xFFF2F2F3),
    toolbarIcon = Color(0xFF4B4B4F),
    listBg = Color(0xFFFFFFFF),
    // the alternate row is barely there — the hairline under each row does the separating
    rowStripe = Color(0xFFFAFBFC),
    // a shade further than rowStripe: an alternate row is meant to be barely there, a heading is not
    groupBg = Color(0xFFEFEFEF),
    rowSelectedInactive = Color(0xFFDCDCDC),
    rowSelectedText = Color(0xFFFFFFFF),
    headerBg = Color(0xFFC5C5C5),
    headerDragged = Color(0xFFE6E9EE),
    headerDivider = Color(0xFFE3E6EA),
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
    dialogTitleBg = Color(0xFFEFEFEF),
    settingValue = Color(0xFFD32F2F),
    sidebarBg = Color(0xFFF4F5F7),
    sidebarBorder = Color(0xFFE3E6EA),
    navSelected = Color(0xFFE4E7EB),
    // a step darker than the design's #EDEFF2: the open tab takes the toolbar grey under it, and
    // the design's value sat too close to that to tell the two apart
    tabIdle = Color(0xFFE2E5E9),
    tabAccent = Color(0xFF1F9D55),
    pillBg = Color(0xFFE8F1FD),
    pillBorder = Color(0xFFB8D0F5),
    tableHeaderBg = Color(0xFFF7F8FA),
    tableHeaderText = Color(0xFF4A505C),
    tableRule = Color(0xFFEEF0F3),
    // a step stronger than the design's #EAF6EE, which vanished against the striped rows
    rowSelectedBg = Color(0xFFD6EFDF),
    selectionBar = Color(0xFFEEF7F1),
    selectionBarBorder = Color(0xFFBFE3CB),
    chipOn = Color(0xFF16181D),
    chipOnText = Color(0xFFFFFFFF),
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
    // *up* from the near-black list, and short of the divider grey — a dark theme's steps have to be
    // bigger than a light one's to be seen at all, and one that reaches the rules reads as a hole
    groupBg = Color(0xFF2A2A2C),
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
    dialogTitleBg = Color(0xFF3A3A3C),
    settingValue = Color(0xFFFF7B72),
    // the redesign is light-first; these keep the dark theme whole until it gets its own pass
    sidebarBg = Color(0xFF161618),
    sidebarBorder = Color(0xFF2C2C2E),
    navSelected = Color(0xFF2C2C30),
    tabIdle = Color(0xFF242426),
    tabAccent = Color(0xFF3DDC84),
    pillBg = Color(0xFF16233A),
    pillBorder = Color(0xFF2D4A78),
    tableHeaderBg = Color(0xFF232325),
    tableHeaderText = Color(0xFFA9AEB8),
    tableRule = Color(0xFF2A2A2D),
    rowSelectedBg = Color(0xFF1F3A2A),
    selectionBar = Color(0xFF16241C),
    selectionBarBorder = Color(0xFF2B5A3E),
    chipOn = Color(0xFFE7EAEE),
    chipOnText = Color(0xFF111318),
)

/**
 * static rather than plain: a theme change should invalidate the whole subtree at once, which is
 * exactly what `staticCompositionLocalOf` does, and it costs nothing to read in between.
 */
val LocalPalette = staticCompositionLocalOf { LightPalette }

/**
 * true where the UI is a phone's rather than a desktop's.
 *
 * The same value `App(compact)` branches on, published so a component deep in the tree can size
 * itself without every caller threading a flag through. Read by [Dimens]' setting and dialog
 * metrics, which are the only sizes in the app that genuinely differ between a thumb and a mouse.
 */
val LocalCompactUi = staticCompositionLocalOf { false }

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

    // the mirror's input feedback, in the Swing app's own mint. fixed rather than taken from the
    // palette: it is drawn over a device's screenshot, not over any of this app's chrome
    val mirrorInk = Color(0xFF00FFB4)
    val mirrorGlow = Color(0x5000FFB4)

    // a held press is warmer than a tap, which is how the two are told apart at a glance
    val mirrorHoldInk = Color(0xFFFFA000)
    val mirrorHoldGlow = Color(0x46FFA000)
    val mirrorHoldFill = Color(0xFFFFB919)

    // a sleeping device's preview: the black of a dark panel in either theme, with dim grey on it
    val previewScreenOff = Color(0xFF000000)
    val previewScreenOffText = Color(0xFF8A8F99)
    // the preview's corner button sits on a screenshot of anything, so it brings its own dark disc
    val previewScrim = Color(0x8C000000)
    val previewScrimIcon = Color(0xFFFFFFFF)

    // a mark or a count drawn on a coloured ground — the sidebar's brand square, a live badge
    val onAccent = Color(0xFFFFFFFF)

    // the collapsed sidebar is a dark rail in either theme — EngageAdminKMP's, so the two apps read
    // as one family. dark on purpose: a light 72dp strip next to a light page reads as a margin
    val railBg = Color(0xFF1C2033)
    val railSelected = Color(0xFF2F3552)
    val railHover = Color(0xFF2A2F47)
    val railText = Color(0xFFAEB4C8)
    val railTextOn = Color(0xFFFFFFFF)

    // the one filled button a page has (Connect): Android's green, which carries dark text — white
    // on #3DDC84 is 1.9:1
    val primaryButton = Color(0xFF3DDC84)
    val onPrimaryButton = Color(0xFF0B0D11)

    // the dot on a Devices filter chip that counts a problem: red for a device that can't be used,
    // amber for one that works but wants looking at
    val chipAlert = Color(0xFFE5484D)
    val chipWarn = Color(0xFFF5A524)

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
    val groupBackground: Color @Composable get() = LocalPalette.current.groupBg
    val headerBackground: Color @Composable get() = LocalPalette.current.headerBg
    val headerDragged: Color @Composable get() = LocalPalette.current.headerDragged
    val headerDivider: Color @Composable get() = LocalPalette.current.headerDivider
    val statusBarBg: Color @Composable get() = LocalPalette.current.statusBarBg

    /** a status bar button under the pointer — the Swing `HoverLabel`'s grey, from either ground. */
    val statusBarHover: Color @Composable get() = LocalPalette.current.secondaryText.copy(alpha = 0.16f)
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

    val dialogTitleBg: Color @Composable get() = LocalPalette.current.dialogTitleBg
    val settingValue: Color @Composable get() = LocalPalette.current.settingValue

    val sidebarBg: Color @Composable get() = LocalPalette.current.sidebarBg
    val sidebarBorder: Color @Composable get() = LocalPalette.current.sidebarBorder
    val navSelected: Color @Composable get() = LocalPalette.current.navSelected
    val tabIdle: Color @Composable get() = LocalPalette.current.tabIdle
    val tabAccent: Color @Composable get() = LocalPalette.current.tabAccent
    val pillBg: Color @Composable get() = LocalPalette.current.pillBg
    val pillBorder: Color @Composable get() = LocalPalette.current.pillBorder
    val tableHeaderBg: Color @Composable get() = LocalPalette.current.tableHeaderBg
    val tableHeaderText: Color @Composable get() = LocalPalette.current.tableHeaderText
    val tableRule: Color @Composable get() = LocalPalette.current.tableRule
    val rowSelectedBg: Color @Composable get() = LocalPalette.current.rowSelectedBg
    val selectionBar: Color @Composable get() = LocalPalette.current.selectionBar
    val selectionBarBorder: Color @Composable get() = LocalPalette.current.selectionBarBorder
    val chipOn: Color @Composable get() = LocalPalette.current.chipOn
    val chipOnText: Color @Composable get() = LocalPalette.current.chipOnText
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

/** which palette the app wears — [AppPref.THEME]. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * whether the dark palette is in force.
 *
 * light unless asked otherwise: the redesign was drawn light-first, and following the OS put half
 * its users on a dark theme that hasn't had its pass yet — see docs/REDESIGN.md.
 */
@Composable
fun isDarkTheme(): Boolean {
    SettingsManager.observePref(AppPref.THEME)
    return when (SettingsManager.getEnum<ThemeMode>(AppPref.THEME)) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
}

@Composable
fun AppTheme(
    darkTheme: Boolean = isDarkTheme(),
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
