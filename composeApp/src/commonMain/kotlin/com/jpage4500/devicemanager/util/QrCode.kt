package com.jpage4500.devicemanager.util

/**
 * a QR code as a square grid of dark and light modules.
 *
 * a value, so it can be built off the UI thread and drawn later — see `ui/components/QrImage.kt`.
 */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    /** true where the module is dark. out of range reads light, so a quiet zone needs no bounds check. */
    operator fun get(x: Int, y: Int): Boolean =
        x in 0 until size && y in 0 until size && modules[y * size + x]
}

/**
 * a QR encoder, byte mode, versions 1–10.
 *
 * Hand-written for the same reason the adb client is: the two things this app needs a QR for are an
 * adb pairing string and a connection string, both short ASCII, and every library that does it is
 * either Android-only or a megabyte of jar for a few hundred lines of arithmetic. Versions past 10
 * are not implemented because nothing here has more than ~80 bytes to say; [encode] returns null
 * rather than guessing if that ever stops being true.
 *
 * The structure follows ISO/IEC 18004: mode and length header, Reed–Solomon blocks interleaved,
 * function patterns, zig-zag data placement, then the mask that scores best.
 */
object QrCode {
    /** how much of the code can be lost and still read. */
    enum class Ecc(internal val formatBits: Int) {
        LOW(1),
        MEDIUM(0),
        QUARTILE(3),
        HIGH(2),
    }

    private const val MIN_VERSION = 1
    private const val MAX_VERSION = 10

    /** @return null when [text] is longer than a version-10 code at [ecc] can carry */
    fun encode(text: String, ecc: Ecc = Ecc.MEDIUM): QrMatrix? {
        val bytes = text.encodeToByteArray()
        val version = pickVersion(bytes.size, ecc) ?: return null
        val dataCodewords = toDataCodewords(bytes, version, ecc)
        val allCodewords = addEccAndInterleave(dataCodewords, version, ecc)
        return Drawing(version, ecc).run {
            drawFunctionPatterns()
            drawCodewords(allCodewords)
            applyBestMask()
            toMatrix()
        }
    }

    // ── the header and the padding ──────────────────────────────────────────────────────────────

    private fun pickVersion(byteCount: Int, ecc: Ecc): Int? {
        for (version in MIN_VERSION..MAX_VERSION) {
            // mode indicator + character count + the bytes themselves. the count field is 8 bits up
            // to version 9 and 16 bits after it
            val needed = 4 + (if (version <= 9) 8 else 16) + byteCount * 8
            if (needed <= numDataCodewords(version, ecc) * 8) return version
        }
        return null
    }

    private fun toDataCodewords(bytes: ByteArray, version: Int, ecc: Ecc): ByteArray {
        val capacity = numDataCodewords(version, ecc)
        val bits = BitBuffer()
        bits.append(0b0100, 4) // byte mode
        bits.append(bytes.size, if (version <= 9) 8 else 16)
        bytes.forEach { bits.append(it.toInt() and 0xFF, 8) }

        // terminator, then to a byte boundary, then the two alternating pad codewords the spec names
        bits.append(0, minOf(4, capacity * 8 - bits.length))
        bits.append(0, (8 - bits.length % 8) % 8)
        var pad = 0xEC
        while (bits.length < capacity * 8) {
            bits.append(pad, 8)
            pad = pad xor 0xEC xor 0x11
        }
        return bits.toBytes()
    }

    // ── Reed–Solomon ────────────────────────────────────────────────────────────────────────────

    /**
     * split into blocks, add each block's ECC, and interleave the lot.
     *
     * the interleaving is what makes a QR survive a thumb over one corner: consecutive codewords end
     * up in different blocks, so damage is spread across blocks rather than destroying one.
     */
    private fun addEccAndInterleave(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
        val blockCount = ECC_BLOCKS[ecc.ordinal][version]
        val eccLength = ECC_CODEWORDS[ecc.ordinal][version]
        val rawCodewords = numRawDataModules(version) / 8
        val shortBlockCount = blockCount - rawCodewords % blockCount
        val shortBlockLength = rawCodewords / blockCount

        val divisor = rsDivisor(eccLength)
        val blocks = ArrayList<ByteArray>(blockCount)
        var at = 0
        for (i in 0 until blockCount) {
            val dataLength = shortBlockLength - eccLength + (if (i < shortBlockCount) 0 else 1)
            val block = data.copyOfRange(at, at + dataLength)
            at += dataLength
            // a short block is padded to the long length so the interleave below is one loop; the
            // pad byte is the one position it skips
            val full = ByteArray(shortBlockLength + 1)
            block.copyInto(full)
            rsRemainder(block, divisor).copyInto(full, full.size - eccLength)
            blocks += full
        }

        val result = ByteArray(rawCodewords)
        var out = 0
        for (i in 0 until shortBlockLength + 1) {
            for (j in 0 until blockCount) {
                if (i != shortBlockLength - eccLength || j >= shortBlockCount) {
                    result[out++] = blocks[j][i]
                }
            }
        }
        return result
    }

    /** the generator polynomial's coefficients, for a code of [degree] ECC codewords. */
    private fun rsDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    /** multiply in GF(2^8) modulo the QR field's own polynomial, x^8 + x^4 + x^3 + x^2 + 1. */
    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    // ── the grid ────────────────────────────────────────────────────────────────────────────────

    private class Drawing(val version: Int, val ecc: Ecc) {
        val size = version * 4 + 17
        val modules = BooleanArray(size * size)

        /** which cells belong to a pattern rather than to the message; masks and data skip these. */
        val function = BooleanArray(size * size)

        fun at(x: Int, y: Int): Boolean = modules[y * size + x]

        fun set(x: Int, y: Int, dark: Boolean) {
            modules[y * size + x] = dark
        }

        fun setFunction(x: Int, y: Int, dark: Boolean) {
            set(x, y, dark)
            function[y * size + x] = true
        }

        fun drawFunctionPatterns() {
            for (i in 0 until size) {
                setFunction(6, i, i % 2 == 0)
                setFunction(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)

            val positions = alignmentPositions()
            for (i in positions.indices) {
                for (j in positions.indices) {
                    // the three corners already hold finder patterns
                    val corner = (i == 0 && j == 0) ||
                        (i == 0 && j == positions.size - 1) ||
                        (i == positions.size - 1 && j == 0)
                    if (!corner) drawAlignment(positions[i], positions[j])
                }
            }
            // reserved now, written for real once the mask is chosen
            drawFormatBits(0)
            drawVersionBits()
        }

        private fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x !in 0 until size || y !in 0 until size) continue
                    val ring = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    setFunction(x, y, ring != 2 && ring != 4)
                }
            }
        }

        private fun drawAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) {
                    setFunction(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                }
            }
        }

        fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val step = (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            val result = IntArray(count)
            result[0] = 6
            var pos = size - 7
            for (i in count - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        /** the 15 BCH-protected bits saying which ECC level and which mask this code uses. */
        fun drawFormatBits(mask: Int) {
            val data = (ecc.formatBits shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412

            for (i in 0..5) setFunction(8, i, bit(bits, i))
            setFunction(8, 7, bit(bits, 6))
            setFunction(8, 8, bit(bits, 7))
            setFunction(7, 8, bit(bits, 8))
            for (i in 9..14) setFunction(14 - i, 8, bit(bits, i))

            for (i in 0..7) setFunction(size - 1 - i, 8, bit(bits, i))
            for (i in 8..14) setFunction(8, size - 15 + i, bit(bits, i))
            // the one module that is always dark
            setFunction(8, size - 8, true)
        }

        private fun drawVersionBits() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val dark = bit(bits, i)
                val a = size - 11 + i % 3
                val b = i / 3
                setFunction(a, b, dark)
                setFunction(b, a, dark)
            }
        }

        /** the message, snaked up and down two columns at a time from the bottom right. */
        fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                // column 6 is the vertical timing pattern, so the pairs shift left by one past it
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0 until 2) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!function[y * size + x] && i < data.size * 8) {
                            set(x, y, bit(data[i ushr 3].toInt(), 7 - (i and 7)))
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        fun applyBestMask() {
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask)
                drawFormatBits(mask)
                val penalty = penalty()
                if (penalty < bestPenalty) {
                    bestPenalty = penalty
                    best = mask
                }
                // masking is its own inverse, so undoing it is applying it again
                applyMask(mask)
            }
            applyMask(best)
            drawFormatBits(best)
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (function[y * size + x]) continue
                    val invert = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (invert) set(x, y, !at(x, y))
                }
            }
        }

        /**
         * the spec's four penalty rules, which decide the mask.
         *
         * none of this affects whether the code is valid — it affects whether a camera can find it.
         * A mask that leaves a long uniform run or something that looks like a finder pattern is a
         * code that reads slowly or not at all.
         */
        private fun penalty(): Int {
            var result = 0

            // rule 1: runs of five or more, and rule 3: anything shaped like a finder pattern
            for (i in 0 until size) {
                result += lineScore { j -> at(j, i) }
                result += lineScore { j -> at(i, j) }
            }

            // rule 2: any 2x2 block of one colour
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val c = at(x, y)
                    if (c == at(x + 1, y) && c == at(x, y + 1) && c == at(x + 1, y + 1)) result += 3
                }
            }

            // rule 4: how far the balance of dark to light is from even
            val dark = modules.count { it }
            val total = size * size
            val fivePercentSteps = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total
            result += fivePercentSteps * 10

            return result
        }

        private inline fun lineScore(cell: (Int) -> Boolean): Int {
            var result = 0
            var runColour = false
            var runLength = 0
            // the last eleven modules, as bits, so the finder-like pattern can be matched as a number
            var window = 0
            for (i in 0 until size) {
                val c = cell(i)
                if (c == runColour) {
                    runLength++
                    if (runLength == 5) result += 3 else if (runLength > 5) result += 1
                } else {
                    runColour = c
                    runLength = 1
                }
                window = ((window shl 1) or (if (c) 1 else 0)) and 0x7FF
                // 1:1:3:1:1 with four light modules on one side, either way round
                if (i >= 10 && (window == 0b10111010000 || window == 0b00001011101)) result += 40
            }
            return result
        }

        fun toMatrix(): QrMatrix = QrMatrix(size, modules)
    }

    private fun bit(value: Int, index: Int): Boolean = ((value ushr index) and 1) != 0

    // ── capacity tables ─────────────────────────────────────────────────────────────────────────

    /** every module a version has, minus the function patterns — so, the message's room in bits. */
    private fun numRawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val alignCount = version / 7 + 2
            result -= (25 * alignCount - 10) * alignCount - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    private fun numDataCodewords(version: Int, ecc: Ecc): Int =
        numRawDataModules(version) / 8 -
            ECC_CODEWORDS[ecc.ordinal][version] * ECC_BLOCKS[ecc.ordinal][version]

    // indexed [Ecc.ordinal][version]; index 0 is a placeholder so version numbers read straight.
    // ISO/IEC 18004 tables 13-22, truncated at version 10
    private val ECC_CODEWORDS = arrayOf(
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18),      // LOW
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26),     // MEDIUM
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24),     // QUARTILE
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28),     // HIGH
    )

    private val ECC_BLOCKS = arrayOf(
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4),               // LOW
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5),               // MEDIUM
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8),               // QUARTILE
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8),               // HIGH
    )
}

/** bits in, bytes out — the one thing the encoder's header needs and Kotlin has no shape for. */
private class BitBuffer {
    private val bytes = ArrayList<Byte>()
    var length = 0
        private set

    fun append(value: Int, bits: Int) {
        for (i in bits - 1 downTo 0) {
            if (length % 8 == 0) bytes.add(0)
            if (((value ushr i) and 1) != 0) {
                val at = length / 8
                bytes[at] = (bytes[at].toInt() or (1 shl (7 - length % 8))).toByte()
            }
            length++
        }
    }

    fun toBytes(): ByteArray = bytes.toByteArray()
}
