package com.jpage4500.devicemanager.util

/**
 * a 24-bit RGB PNG from a block of pixels.
 *
 * Here for the demo devices, which have no screen to capture and so draw one — see
 * `transport/demo/DemoScreen`. Every row is written with PNG's "Sub" filter, which turns a run of
 * one colour into a run of zeros; that is what lets a drawn home screen deflate to a few tens of KB.
 */
object PngEncoder {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // PNG colour type 2: truecolour, no alpha
    private const val COLOR_RGB: Byte = 2
    private const val FILTER_SUB: Byte = 1

    /** [pixels] row by row, each `0xRRGGBB`; anything above the low 24 bits is ignored. */
    fun encode(width: Int, height: Int, pixels: IntArray): ByteArray {
        val stride = width * 3 + 1
        val raw = ByteArray(stride * height)
        for (y in 0 until height) {
            val row = y * stride
            raw[row] = FILTER_SUB
            var prevR = 0
            var prevG = 0
            var prevB = 0
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val i = row + 1 + x * 3
                raw[i] = (r - prevR).toByte()
                raw[i + 1] = (g - prevG).toByte()
                raw[i + 2] = (b - prevB).toByte()
                prevR = r
                prevG = g
                prevB = b
            }
        }

        val header = ByteArray(13)
        putInt(header, 0, width)
        putInt(header, 4, height)
        header[8] = 8 // bits per channel
        header[9] = COLOR_RGB
        // compression, filter method and interlace are all 0

        val parts = listOf(
            SIGNATURE,
            chunk("IHDR", header),
            chunk("IDAT", zlibDeflate(raw)),
            chunk("IEND", ByteArray(0)),
        )
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach {
            it.copyInto(out, at)
            at += it.size
        }
        return out
    }

    /** length, type, data, then a CRC over type and data. */
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArray(12 + data.size)
        putInt(out, 0, data.size)
        type.encodeToByteArray().copyInto(out, 4)
        data.copyInto(out, 8)
        putInt(out, 8 + data.size, crc32(out, 4, 4 + data.size))
        return out
    }

    private fun putInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    private fun crc32(bytes: ByteArray, offset: Int, length: Int): Int {
        var c = -1
        for (i in offset until offset + length) {
            c = CRC_TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        return c.inv()
    }
}
