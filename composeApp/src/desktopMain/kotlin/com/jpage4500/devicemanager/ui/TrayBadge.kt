package com.jpage4500.devicemanager.ui

import java.awt.*
import java.awt.geom.*
import java.awt.image.*
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

/**
 * the head's colour where the bar doesn't pick one: white on a dark OS theme, black on a light one.
 *
 * the OS theme stands in for the bar's own colour, which nothing reports. Unknown is taken as dark,
 * because that is mostly Linux, whose panels mostly are.
 */
fun trayIconColor(): Color = if (currentSystemTheme == SystemTheme.LIGHT) Color.BLACK else Color.WHITE

/**
 * the menu-bar icon: an android head with the device count on it.
 *
 * Ported from the Swing app's `UiUtils.getTrayIconWithCount`, with one change: **the count is cut out
 * of the head, not painted in a second colour.** A cut-out is what a macOS template image needs — the
 * menu bar keeps only the alpha and recolours the rest to suit itself — and anywhere that isn't
 * template-aware the hole shows the bar behind it, which is the contrasting colour anyway. With no
 * devices the head has eyes instead, so it still reads as the android.
 *
 * Drawn at 64px and handed over with auto-size on; macOS scales it to the bar's 22pt, which leaves a
 * 2x display with real pixels to draw rather than a doubled 22px bitmap.
 *
 * @param color the head's colour, which only matters where the menu bar doesn't recolour it
 */
fun trayIconImage(count: Int, color: Color): BufferedImage {
    val size = 64
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        // the Swing app's geometry. antennae first; the head then covers their inner ends so they
        // appear to sprout from it
        val headX = 6
        val headY = 10
        val headD = 52
        val centerX = headX + headD / 2.0
        val centerY = headY + headD / 2.0

        g.color = color
        g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawLine(19, 14, 15, 6)
        g.drawLine(45, 14, 49, 6)
        g.fillOval(headX, headY, headD, headD)

        // everything from here on is a hole in the head
        g.composite = AlphaComposite.Clear

        if (count <= 0) {
            val eye = 8.0
            g.fill(Ellipse2D.Double(centerX - 12 - eye / 2, centerY - 4 - eye / 2, eye, eye))
            g.fill(Ellipse2D.Double(centerX + 12 - eye / 2, centerY - 4 - eye / 2, eye, eye))
            return image
        }

        // the largest bold digits that fit a 36px square inside the head: one digit fills it, "45"
        // shrinks to fit, and three digits still land inside the circle
        val text = count.toString()
        val inner = 36
        var fontSize = 40
        var font: Font
        var metrics: FontMetrics
        while (true) {
            font = Font(Font.SANS_SERIF, Font.BOLD, fontSize)
            metrics = g.getFontMetrics(font)
            if (metrics.stringWidth(text) <= inner && metrics.ascent <= inner) break
            if (fontSize <= 10) break
            fontSize -= 2
        }
        // a glyph outline rather than drawString, because text drawn under a Clear composite isn't
        // guaranteed to clear on every pipeline, and a shape is
        val glyphs = font.createGlyphVector(g.fontRenderContext, text)
        val bounds = glyphs.visualBounds
        val x = centerX - bounds.width / 2 - bounds.x
        val y = centerY - bounds.height / 2 - bounds.y
        g.fill(glyphs.getOutline(x.toFloat(), y.toFloat()))
        return image
    } finally {
        g.dispose()
    }
}
