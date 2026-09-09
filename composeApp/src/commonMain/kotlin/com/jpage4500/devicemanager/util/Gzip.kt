package com.jpage4500.devicemanager.util

/**
 * gunzip [bytes], or null if they aren't gzip or the stream is truncated.
 *
 * Here because a Device Manager server's `/ws/logs` sends every batch of log lines as gzipped JSON —
 * a busy device prints thousands of lines a second and they compress to about a tenth, so the
 * compression is the difference between a phone keeping up with a log and not. Nothing else in the
 * app needs it yet.
 *
 * Never throws: a batch that won't decompress is a batch to drop, not a stream to end.
 */
expect fun gunzip(bytes: ByteArray): ByteArray?
