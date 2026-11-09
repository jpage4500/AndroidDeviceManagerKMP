package com.jpage4500.devicemanager.util

import kotlinx.cinterop.*
import platform.zlib.*

private val log = LoggerConfig.createLogger("Gzip")

/**
 * zlib's `inflate`, which is in the iOS SDK.
 *
 * Kotlin/Native has no `GZIPInputStream`, and Foundation's own `decompressed(using:)` speaks raw
 * DEFLATE rather than the gzip container the server writes — using it would mean picking the gzip
 * header apart by hand and hoping the far end never sets a flag bit. zlib reads the container
 * properly: [WINDOW_BITS] is its largest window plus 32, which is the value that says "detect
 * whichever of a gzip or a zlib header this is".
 */
@OptIn(ExperimentalForeignApi::class)
actual fun gunzip(bytes: ByteArray): ByteArray? {
    if (bytes.isEmpty()) return null
    return memScoped {
        val stream = alloc<z_stream>()
        // zeroed by hand, and not for tidiness: `alloc` hands back whatever was in that memory, and
        // `inflateInit2` *calls* zalloc if it isn't null. Garbage there is a jump to a garbage
        // address; null is what tells zlib to use its own allocator
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        val started = inflateInit2_(
            stream.ptr,
            WINDOW_BITS,
            ZLIB_VERSION,
            sizeOf<z_stream>().toInt(),
        )
        if (started != Z_OK) {
            log.w("gunzip: inflateInit2 answered $started")
            return@memScoped null
        }
        try {
            inflateAll(stream, bytes)
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}

/**
 * inflate the whole of [bytes], a chunk of output at a time.
 *
 * Chunked because nothing says how big the answer is: gzipped log lines come back at roughly ten
 * times their compressed size, but a batch is whatever the device printed in the last 500ms.
 */
@OptIn(ExperimentalForeignApi::class)
private fun inflateAll(stream: z_stream, bytes: ByteArray): ByteArray? {
    val pieces = mutableListOf<ByteArray>()
    var total = 0
    var failed = false
    bytes.usePinned { input ->
        stream.next_in = input.addressOf(0).reinterpret()
        stream.avail_in = bytes.size.toUInt()
        val out = ByteArray(CHUNK)
        while (true) {
            var status = Z_OK
            var produced = 0
            out.usePinned { output ->
                stream.next_out = output.addressOf(0).reinterpret()
                stream.avail_out = CHUNK.toUInt()
                status = inflate(stream.ptr, Z_NO_FLUSH)
                produced = CHUNK - stream.avail_out.toInt()
            }
            if (produced > 0) {
                pieces += out.copyOf(produced)
                total += produced
            }
            if (status == Z_STREAM_END) break
            if (status != Z_OK) {
                log.w("gunzip: inflate answered $status after $total bytes")
                failed = true
                break
            }
            // it has run out of input without reaching the end: the batch arrived truncated, and
            // what did decompress is worth keeping
            if (produced == 0 && stream.avail_in == 0u) break
        }
    }
    if (failed && total == 0) return null
    val whole = ByteArray(total)
    var at = 0
    pieces.forEach { piece ->
        piece.copyInto(whole, at)
        at += piece.size
    }
    return whole
}

/** zlib's 32KB window, plus the 32 that says "work out which header this is". */
private const val WINDOW_BITS = 15 + 32

/** how much is inflated at a time; a batch of log lines is a few KB compressed. */
private const val CHUNK = 32 * 1024

/** zlib's one-shot `compress2`, at its fastest level — the output only ever lives as long as a frame. */
@OptIn(ExperimentalForeignApi::class)
actual fun zlibDeflate(bytes: ByteArray): ByteArray {
    val bound = compressBound(bytes.size.convert()).toInt()
    val out = ByteArray(bound)
    val written = memScoped {
        val length = alloc<uLongVar>()
        length.value = bound.convert()
        val status = bytes.usePinned { input ->
            out.usePinned { output ->
                compress2(
                    output.addressOf(0).reinterpret(),
                    length.ptr,
                    input.addressOf(0).reinterpret(),
                    bytes.size.convert(),
                    Z_BEST_SPEED,
                )
            }
        }
        if (status != Z_OK) {
            log.w("zlibDeflate: compress2 answered $status")
            -1
        } else {
            length.value.toInt()
        }
    }
    return if (written < 0) ByteArray(0) else out.copyOf(written)
}
