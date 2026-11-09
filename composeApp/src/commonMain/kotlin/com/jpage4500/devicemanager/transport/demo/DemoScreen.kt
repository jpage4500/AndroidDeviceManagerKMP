package com.jpage4500.devicemanager.transport.demo

import com.jpage4500.devicemanager.util.*
import kotlin.math.*

/**
 * a demo device's screen, drawn as a PNG.
 *
 * Shapes and nothing else — there is no font here, so text is drawn as the grey bars a wireframe
 * uses, and the clock as seven-segment digits. Enough that a screenshot, a mirror or a preview
 * tile reads as a phone at a glance, and nothing a reader would mistake for a real app.
 *
 * The colours here are pixels on the invented device, not the app's own UI, which is why they are
 * not in `Colors`.
 */
internal object DemoScreen {
    /** which made-up app is in front. */
    enum class Kind { HOME, LIST, CHAT, CARDS }

    // drawn at half the panel's size or less: a mirror shows it scaled anyway, and this keeps a
    // frame to a few tens of KB and a few milliseconds
    private const val MAX_LONG_SIDE = 1280
    private const val PHONE_WIDTH = 540

    private const val WHITE = 0xFFFFFF
    private const val INK = 0x1D1B20
    private const val SURFACE = 0xF7F2FA
    private const val CARD = 0xFFFFFF
    private const val MUTED = 0xCAC4D0

    /** the size [render] draws at for [device], keeping its shape. */
    fun size(device: DemoDevice): Pair<Int, Int> {
        val w = device.screenWidth
        val h = device.screenHeight
        return if (device.isTablet) {
            val scale = min(1.0, MAX_LONG_SIDE.toDouble() / max(w, h))
            (w * scale).roundToInt() to (h * scale).roundToInt()
        } else {
            PHONE_WIDTH to (PHONE_WIDTH.toDouble() * h / w).roundToInt()
        }
    }

    fun render(device: DemoDevice, time: String): ByteArray {
        val (w, h) = size(device)
        val r = Raster(w, h)
        val accent = hsv(device.hue, 0.55, 0.85)
        when (device.screen) {
            Kind.HOME -> home(r, device, time)
            Kind.LIST -> list(r, accent)
            Kind.CHAT -> chat(r, accent)
            Kind.CARDS -> cards(r, accent, device.index)
        }
        val light = device.screen != Kind.HOME
        statusBar(r, time, if (light) INK else WHITE, device.batteryLevel)
        // the gesture bar
        val u = r.unit
        r.roundRect(w / 2 - 54 * u, h - 14 * u, 108 * u, 5 * u, 3 * u, if (light) INK else WHITE, 200)
        return PngEncoder.encode(w, h, r.pixels)
    }

    private fun statusBar(r: Raster, time: String, color: Int, battery: Int) {
        val u = r.unit
        r.clock(time, 22 * u, 12 * u, 13 * u, color)
        // wifi, signal, battery at the right
        val right = r.width - 20 * u
        r.roundRect(right - 26 * u, 12 * u, 24 * u, 12 * u, 3 * u, color, 90)
        r.roundRect(right - 26 * u, 12 * u, (24 * u * battery / 100).coerceAtLeast(2), 12 * u, 3 * u, color)
        for (bar in 0 until 4) {
            val bh = (4 + bar * 3) * u
            r.fill(right - 60 * u + bar * 6 * u, 24 * u - bh, 4 * u, bh, color)
        }
        r.circle(right - 78 * u, 20 * u, 5 * u, color)
    }

    private fun home(r: Raster, device: DemoDevice, time: String) {
        val u = r.unit
        val w = r.width
        val h = r.height
        r.gradient(hsv(device.hue, 0.65, 0.55), hsv((device.hue + 40) % 360, 0.55, 0.25))
        r.circle(w * 4 / 5, h / 4, w / 2, hsv((device.hue + 20) % 360, 0.45, 0.85), 60)
        r.circle(w / 6, h * 3 / 4, w / 2, hsv((device.hue + 320) % 360, 0.5, 0.7), 45)

        // the clock widget
        val landscape = w > h
        val clockHeight = (if (landscape) 70 else 92) * u
        r.clock(time, 36 * u, 90 * u, clockHeight, WHITE)
        r.roundRect(36 * u, 90 * u + clockHeight + 18 * u, 170 * u, 14 * u, 7 * u, WHITE, 170)

        // the app grid
        val columns = if (landscape) 8 else 4
        val rows = if (landscape) 2 else 4
        val cell = (w - 40 * u) / columns
        val icon = min(cell * 62 / 100, 70 * u)
        // under the clock, clear of the search bar and dock at the bottom
        val top = if (landscape) h - 3 * (cell + 10 * u) else h * 28 / 100
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val n = row * columns + col + device.index
                val cx = 20 * u + col * cell + cell / 2
                val cy = top + row * (icon + 46 * u)
                appIcon(r, cx, cy, icon, n)
                r.roundRect(cx - icon * 4 / 10, cy + icon / 2 + 10 * u, icon * 8 / 10, 7 * u, 4 * u, WHITE, 150)
            }
        }

        // search pill and dock
        val dockTop = h - (if (landscape) 110 else 150) * u
        r.roundRect(24 * u, dockTop - 70 * u, w - 48 * u, 50 * u, 25 * u, WHITE, 215)
        r.circle(54 * u, dockTop - 45 * u, 12 * u, hsv(device.hue, 0.6, 0.8))
        r.roundRect(78 * u, dockTop - 49 * u, 140 * u, 8 * u, 4 * u, MUTED)
        val dockCell = (w - 40 * u) / 4
        for (col in 0 until 4) {
            appIcon(r, 20 * u + col * dockCell + dockCell / 2, dockTop + 40 * u, icon, col * 5 + 3)
        }
    }

    private fun appIcon(r: Raster, cx: Int, cy: Int, size: Int, n: Int) {
        val color = hsv((n * 67) % 360, 0.55 + (n % 3) * 0.12, 0.88)
        val half = size / 2
        if (n % 4 == 1) r.circle(cx, cy, half, color) else r.roundRect(cx - half, cy - half, size, size, size / 4, color)
        // a glyph, so twenty icons aren't twenty plain swatches
        when (n % 5) {
            0 -> r.circle(cx, cy, size / 5, WHITE)
            1 -> r.roundRect(cx - size / 5, cy - size / 5, size * 2 / 5, size * 2 / 5, size / 12, WHITE)
            2 -> r.fill(cx - size / 4, cy - size / 14, size / 2, size / 7, WHITE)
            3 -> {
                r.fill(cx - size / 4, cy - size / 5, size / 2, size / 9, WHITE)
                r.fill(cx - size / 4, cy, size / 3, size / 9, WHITE)
            }
            else -> r.circle(cx, cy, size / 4, WHITE, 140)
        }
    }

    /** a settings-style list. */
    private fun list(r: Raster, accent: Int) {
        val u = r.unit
        val w = r.width
        r.fill(0, 0, w, r.height, SURFACE)
        r.roundRect(24 * u, 70 * u, 190 * u, 22 * u, 8 * u, INK)
        r.roundRect(20 * u, 120 * u, w - 40 * u, 52 * u, 26 * u, 0xECE6F0)
        r.circle(48 * u, 146 * u, 9 * u, 0x79747E)
        var y = 200 * u
        var n = 0
        while (y < r.height - 80 * u) {
            r.circle(44 * u, y + 30 * u, 20 * u, hsv((n * 53 + 180) % 360, 0.4, 0.9))
            r.circle(44 * u, y + 30 * u, 8 * u, WHITE)
            r.roundRect(84 * u, y + 16 * u, (150 + (n * 37) % 120) * u, 11 * u, 5 * u, INK, 220)
            r.roundRect(84 * u, y + 36 * u, (190 + (n * 53) % 140) * u, 8 * u, 4 * u, 0x79747E, 160)
            if (n == 1) r.roundRect(w - 76 * u, y + 18 * u, 48 * u, 26 * u, 13 * u, accent)
            y += 74 * u
            n++
        }
    }

    /** a conversation. */
    private fun chat(r: Raster, accent: Int) {
        val u = r.unit
        val w = r.width
        val h = r.height
        r.fill(0, 0, w, h, SURFACE)
        r.fill(0, 0, w, 110 * u, 0xF3EDF7)
        r.circle(52 * u, 76 * u, 20 * u, accent)
        r.roundRect(86 * u, 64 * u, 150 * u, 12 * u, 6 * u, INK)
        r.roundRect(86 * u, 84 * u, 90 * u, 8 * u, 4 * u, 0x79747E, 160)
        var y = 140 * u
        var n = 0
        while (y < h - 190 * u) {
            val mine = n % 3 == 1
            val lines = 1 + (n * 5) % 3
            val bw = (min(w - 120 * u, (180 + (n * 71) % 220) * u))
            val bh = (22 + lines * 20) * u
            val x = if (mine) w - 20 * u - bw else 20 * u
            r.roundRect(x, y, bw, bh, 18 * u, if (mine) accent else 0xE7E0EC)
            for (line in 0 until lines) {
                val lw = if (line == lines - 1) bw * 5 / 10 else bw - 36 * u
                r.roundRect(x + 18 * u, y + 16 * u + line * 20 * u, lw, 8 * u, 4 * u, if (mine) WHITE else INK, 170)
            }
            y += bh + 16 * u
            n++
        }
        r.roundRect(16 * u, h - 110 * u, w - 90 * u, 52 * u, 26 * u, 0xECE6F0)
        r.circle(w - 44 * u, h - 84 * u, 24 * u, accent)
    }

    /** an inventory-style app: a bar, a grid of cards, a floating button. */
    private fun cards(r: Raster, accent: Int, seed: Int) {
        val u = r.unit
        val w = r.width
        val h = r.height
        r.fill(0, 0, w, h, 0xF2F2F2)
        r.fill(0, 0, w, 116 * u, accent)
        r.roundRect(24 * u, 74 * u, 160 * u, 18 * u, 8 * u, WHITE)
        r.circle(w - 40 * u, 82 * u, 12 * u, WHITE, 200)
        val columns = if (w > h) 4 else 2
        val gap = 14 * u
        val cw = (w - gap * (columns + 1)) / columns
        val ch = cw * 5 / 4
        var row = 0
        while (136 * u + row * (ch + gap) < h) {
            for (col in 0 until columns) {
                val x = gap + col * (cw + gap)
                val y = 136 * u + row * (ch + gap)
                val n = row * columns + col + seed
                r.roundRect(x, y, cw, ch, 14 * u, CARD)
                r.roundRect(x, y, cw, ch * 55 / 100, 14 * u, hsv((n * 41) % 360, 0.35, 0.92))
                r.fill(x, y + ch * 55 / 100 - 14 * u, cw, 14 * u, hsv((n * 41) % 360, 0.35, 0.92))
                r.roundRect(x + 14 * u, y + ch * 62 / 100, cw * 7 / 10, 10 * u, 5 * u, INK, 210)
                r.roundRect(x + 14 * u, y + ch * 74 / 100, cw * 4 / 10, 8 * u, 4 * u, 0x79747E, 160)
                r.roundRect(x + 14 * u, y + ch * 86 / 100, cw * 3 / 10, 10 * u, 5 * u, accent)
            }
            row++
        }
        r.roundRect(w - 96 * u, h - 140 * u, 68 * u, 68 * u, 20 * u, accent)
        r.fill(w - 72 * u, h - 108 * u, 20 * u, 4 * u, WHITE)
        r.fill(w - 64 * u, h - 116 * u, 4 * u, 20 * u, WHITE)
    }

    /** hue in degrees, saturation and value 0..1, as `0xRRGGBB`. */
    private fun hsv(hue: Int, s: Double, v: Double): Int {
        val c = v * s
        val x = c * (1 - abs((hue / 60.0) % 2 - 1))
        val m = v - c
        val (r, g, b) = when (hue / 60) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun channel(f: Double) = ((f + m) * 255).roundToInt().coerceIn(0, 255)
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}

/** pixels and the handful of shapes [DemoScreen] draws with, clipped to the edges. */
private class Raster(val width: Int, val height: Int) {
    val pixels = IntArray(width * height)

    /** one layout pixel: the layouts are written for a 540-wide phone. */
    val unit: Int = max(1, min(width, height) / 540)

    fun fill(x: Int, y: Int, w: Int, h: Int, color: Int, alpha: Int = 255) {
        val x0 = x.coerceIn(0, width)
        val x1 = (x + w).coerceIn(0, width)
        val y0 = y.coerceIn(0, height)
        val y1 = (y + h).coerceIn(0, height)
        for (py in y0 until y1) {
            span(py, x0, x1, color, alpha)
        }
    }

    fun roundRect(x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int, alpha: Int = 255) {
        val r = radius.coerceAtMost(min(w, h) / 2).coerceAtLeast(0)
        for (dy in 0 until h) {
            val py = y + dy
            if (py < 0 || py >= height) continue
            val fromEdge = min(dy, h - 1 - dy)
            val inset = if (fromEdge < r) {
                val d = r - fromEdge - 0.5
                (r - sqrt(max(0.0, r.toDouble() * r - d * d))).roundToInt()
            } else {
                0
            }
            span(py, (x + inset).coerceIn(0, width), (x + w - inset).coerceIn(0, width), color, alpha)
        }
    }

    fun circle(cx: Int, cy: Int, radius: Int, color: Int, alpha: Int = 255) {
        for (dy in -radius until radius) {
            val py = cy + dy
            if (py < 0 || py >= height) continue
            val half = sqrt(max(0.0, radius.toDouble() * radius - (dy + 0.5) * (dy + 0.5))).roundToInt()
            span(py, (cx - half).coerceIn(0, width), (cx + half).coerceIn(0, width), color, alpha)
        }
    }

    fun gradient(top: Int, bottom: Int) {
        for (py in 0 until height) {
            span(py, 0, width, mix(top, bottom, py * 255 / max(1, height - 1)), 255)
        }
    }

    /** "9:41" or "14:05" as seven-segment digits, [h] tall, with its top-left at [x], [y]. */
    fun clock(time: String, x: Int, y: Int, h: Int, color: Int) {
        var at = x
        val w = h / 2
        val t = max(1, h / 8)
        for (c in time) {
            if (c == ':') {
                fill(at + t / 2, y + h / 3 - t / 2, t, t, color)
                fill(at + t / 2, y + h * 2 / 3 - t / 2, t, t, color)
                at += t * 2 + t
                continue
            }
            val digit = c.digitToIntOrNull() ?: continue
            val on = SEGMENTS[digit]
            // a, b, c, d, e, f, g — the usual order, clockwise from the top, then the middle
            if (on and 0x40 != 0) fill(at, y, w, t, color)
            if (on and 0x20 != 0) fill(at + w - t, y, t, h / 2, color)
            if (on and 0x10 != 0) fill(at + w - t, y + h / 2, t, h - h / 2, color)
            if (on and 0x08 != 0) fill(at, y + h - t, w, t, color)
            if (on and 0x04 != 0) fill(at, y + h / 2, t, h - h / 2, color)
            if (on and 0x02 != 0) fill(at, y, t, h / 2, color)
            if (on and 0x01 != 0) fill(at, y + h / 2 - t / 2, w, t, color)
            at += w + t * 2
        }
    }

    private fun span(py: Int, x0: Int, x1: Int, color: Int, alpha: Int) {
        val row = py * width
        if (alpha >= 255) {
            pixels.fill(color, row + x0, row + x1)
        } else {
            for (px in x0 until x1) {
                pixels[row + px] = mix(pixels[row + px], color, alpha)
            }
        }
    }

    private fun mix(from: Int, to: Int, amount: Int): Int {
        val inv = 255 - amount
        val r = (((from shr 16) and 0xFF) * inv + ((to shr 16) and 0xFF) * amount) / 255
        val g = (((from shr 8) and 0xFF) * inv + ((to shr 8) and 0xFF) * amount) / 255
        val b = ((from and 0xFF) * inv + (to and 0xFF) * amount) / 255
        return (r shl 16) or (g shl 8) or b
    }

    companion object {
        // bits a..g for 0-9
        private val SEGMENTS = intArrayOf(0x7E, 0x30, 0x6D, 0x79, 0x33, 0x5B, 0x5F, 0x70, 0x7F, 0x7B)
    }
}
