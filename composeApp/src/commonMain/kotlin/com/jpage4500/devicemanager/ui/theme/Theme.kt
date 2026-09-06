package com.jpage4500.devicemanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*

/**
 * every size in the app, grouped by region with a reason for the number.
 *
 * one home per value: a size that appears twice is a size that will drift.
 */
object Dimens {
    // device list
    val rowHeight = 52.dp
    val rowPadding = 12.dp
    val listSpacing = 1.dp

    // device table (desktop)
    // 26.dp holds a battery bar and a 16.dp icon with a hairline to spare; 45 rows is the case
    // this is sized for, and a taller row means fewer of them on screen
    val tableRowHeight = 26.dp
    val headerHeight = 28.dp
    val cellPadding = 6.dp
    val columnDefault = 100.dp
    val columnMin = 40.dp
    // the divider is a hairline; the drag target around it is not, because a 1px target is missed
    val resizeHandle = 6.dp
    val statusBarHeight = 26.dp

    // toolbar
    val toolbarIcon = 22.dp
    val toolbarButtonWidth = 58.dp

    val icon = 16.dp
    val iconLarge = 24.dp

    // battery bar
    val batteryWidth = 22.dp
    val batteryHeight = 11.dp

    // a phone row needs a bigger target than a mouse does
    val touchRowHeight = 68.dp

    val gap = 8.dp
    val gapLarge = 16.dp
    val corner = 6.dp

    // desktop window
    val windowWidth = 1000.dp
    val windowHeight = 640.dp
    val minWindowWidth = 520.dp
    val minWindowHeight = 360.dp
    val toolbarHeight = 44.dp

    val statusDot = 8.dp
}

/**
 * the app's colors.
 *
 * deliberately thin: the desktop app is meant to look like the OS, so almost everything comes from
 * the Material scheme and only the states adb has an opinion about get a color of their own.
 */
object Colors {
    val online = Color(0xFF3F8A56)
    val offline = Color(0xFF8A8F99)
    val unauthorized = Color(0xFFC08A2E)
    val error = Color(0xFFB3453B)

    // a toast that's only telling you something; the other two reuse `online` and `error`
    val info = Color(0xFF2F6FBF)
}

private val lightScheme = lightColorScheme()
private val darkScheme = darkColorScheme()

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme else lightScheme,
        content = content,
    )
}
