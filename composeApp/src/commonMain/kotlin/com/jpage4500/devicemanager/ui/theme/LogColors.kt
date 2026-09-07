package com.jpage4500.devicemanager.ui.theme

import androidx.compose.ui.graphics.*

/**
 * a log line's colour, by level — the Swing app's exact values.
 *
 * the **whole row** takes it, not just the level cell: at 30,000 lines the level column is four
 * pixels of a screen you are scanning, and colouring the message is what makes an error findable.
 *
 * deliberately not `@Composable`: it is called once per visible row per frame, and a composable
 * lambda would put a recompose scope on every one of them.
 *
 * @param default what an ordinary line draws in — debug, and anything unrecognised
 */
fun logLevelColor(level: String?, default: Color): Color = when (level?.firstOrNull()?.uppercaseChar()) {
    'V' -> LogLevelColors.verbose
    'I' -> LogLevelColors.info
    'W' -> LogLevelColors.warn
    'E', 'A', 'F' -> LogLevelColors.error
    else -> default
}

private object LogLevelColors {
    val verbose = Color(0xFF0026FF)
    val info = Color(0xFF188600)
    val warn = Color(0xFFFB6D08)
    val error = Color(0xFFFF0000)
}
