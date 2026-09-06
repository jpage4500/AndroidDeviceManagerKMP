package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.util.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
// explicit: ktor's io package declares a CancellationException typealias too, and two wildcard
// imports make the name ambiguous
import kotlinx.coroutines.CancellationException

/**
 * plain HTTP, on one shared client.
 *
 * the adb routes do **not** come through here — they speak adb's own framing over a raw socket, see
 * `adb/`. this is for everything else the app fetches: a Device Manager instance's HTTP API, a
 * release check, a file pulled over http. ported from DashboardWeatherKMP.
 */
object NetworkManager {
    private val log = LoggerConfig.createLogger("NetworkManager")

    // one client for the whole app: a client owns a connection pool and a thread pool, and building
    // one per request is how an app ends up with hundreds of idle sockets
    val httpClient by lazy { createHttpClient(useCookies = false) }

    suspend fun downloadUrlToString(
        url: String?,
        headers: Map<String, String>? = null,
    ): Result<String> {
        if (url == null) return Result.failure(Exception("url is null"))

        return try {
            val response: HttpResponse = httpClient.get(url) {
                headers?.forEach { (key, value) -> header(key, value) }
            }
            // success and failure are logged centrally by HttpLog
            if (response.status.isSuccess()) Result.success(response.bodyAsText())
            else Result.failure(Exception("${response.status}"))
        } catch (e: CancellationException) {
            // never swallow coroutine cancellation — rethrow so it propagates
            throw e
        } catch (e: Exception) {
            log.e("downloadUrlToString: exception: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * stream a download, handing each buffer to [writeChunk] rather than holding the whole thing.
     *
     * [onProgress] reports (downloaded, total), where total is -1 when the server sends no
     * content-length, and is throttled to ~50ms so a progress bar can't flood recomposition.
     *
     * @return true when something arrived; cancellation rethrows
     */
    suspend fun downloadFile(
        url: String,
        expectedSize: Long? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        writeChunk: (buffer: ByteArray, length: Int) -> Unit,
    ): Boolean {
        return try {
            var success = false
            httpClient.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    log.e("downloadFile: failed status=${response.status}, url:$url")
                    return@execute
                }
                val total = response.contentLength() ?: expectedSize ?: -1L
                val channel = response.bodyAsChannel()
                val buf = ByteArray(64 * 1024)
                var downloaded = 0L
                var lastReportMs = 0L
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buf, 0, buf.size)
                    if (read <= 0) break
                    writeChunk(buf, read)
                    downloaded += read
                    val now = DateUtils.nowMs()
                    if (now - lastReportMs >= 50) {
                        onProgress(downloaded, total)
                        lastReportMs = now
                    }
                }
                onProgress(downloaded, total)
                success = downloaded > 0
            }
            success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("downloadFile: exception: ${e.message}")
            false
        }
    }
}
