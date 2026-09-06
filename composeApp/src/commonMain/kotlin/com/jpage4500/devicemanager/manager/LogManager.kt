package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.util.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.getString

private val log = LoggerConfig.createLogger("LogManager")

/**
 * the log file, and getting it off the machine.
 *
 * three jobs, all of them about the same file: stamp what this build and machine are at startup,
 * record a crash synchronously enough that it survives the process dying, and upload the result when
 * someone reports a problem. ported from DashboardWeatherKMP.
 *
 * uploading needs LOGGING_URL and LOGGING_API, which come from the environment or
 * `~/.gradle/gradle.properties` at build time (see composeApp/build.gradle.kts). a checkout without
 * them compiles and runs; the upload simply refuses.
 */
object LogManager {
    const val PATH_CRASHES = "crashes"

    // max size of an uploaded log. approximate — counted in characters, but a log file is
    // effectively ascii, so one character is one byte
    private const val MAX_UPLOAD_SIZE = 3 * 1024 * 1024

    // only include the rotated log if it was written within this window; older than that and it
    // holds nothing relevant to whatever is being reported now
    private const val MAX_ROTATED_AGE_MS = 12 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** call once from the entry point, after [LoggerConfig.initialize]. */
    fun onAppStart() {
        log.i("--------------------------------------------")
        log.i("**** APP_STARTED: version: $APP_VERSION ****")
        handlePendingCrashes()
    }

    fun uploadLogs(text: String? = null, listener: ((Result<String>) -> Unit)? = null) {
        scope.launch {
            if (LOGGING_URL.isEmpty() || LOGGING_API.isEmpty()) {
                log.w("uploadLogs: logging URL not set")
                ToastManager.showToast(getString(Res.string.log_upload_not_configured), success = false)
                listener?.invoke(Result.failure(Exception("logging not configured")))
                return@launch
            }

            if (text != null) log.i("SUPPORT: $text")
            writeHeader()

            val dateStr = DateUtils.formatDate("yyyyMMdd-HHmm")
            val platformLc = PlatformUtils.getPlatformName().lowercase()

            val fileStr = StorageManager.readFile(PATH_LOGS, FILE_LOG)
            if (fileStr == null) {
                listener?.invoke(Result.failure(Exception("no log file to upload")))
                return@launch
            }

            // the `logs` bucket is shared with the other apps and every upload is x-upsert, so an
            // un-prefixed "<platform>-logs-<minute>.txt" can overwrite another app's report
            val fileName = "${Branding.analyticsId}-$platformLc-logs-$dateStr.txt"
            listener?.invoke(uploadLog(fileName, withRotatedLog(fileStr).encodeToByteArray()))
        }
    }

    /** what this build and this machine are, in the one line someone reading a log starts from. */
    private fun writeHeader() {
        log.i(
            // APP: macOS v1.0.23, OS:macOS/26.5.1, MODEL:aarch64, 2.1G/7.6G, 8CPU, Build: 2026.09.06 3:24 PM
            "APP: ${PlatformUtils.getPlatformName()} v$APP_VERSION" +
                ", OS:${PlatformUtils.getPlatformName()}/${PlatformUtils.getPlatformVersion()}" +
                ", MODEL:${PlatformUtils.getDeviceModel()}" +
                ", ${PlatformUtils.getMemoryInfo()}" +
                // core count belongs here rather than on any per-device line: it never changes, and
                // it's what makes a timing in someone else's log readable
                ", ${PlatformUtils.getCoreCount()}CPU" +
                (if (PlatformUtils.isTablet()) " +TABLET" else "") +
                (if (PlatformUtils.isDebugBuild()) " +DEBUG" else "") +
                ", Build:$BUILD_DATE"
        )
        log.i("--------------------------------------------")
    }

    /**
     * the log rotates at 2 MB, which means the lines leading up to a problem are often in
     * FILE_BACKUP and would never be uploaded — worst case the current log is nearly empty because
     * it just rotated. prepend the tail of the rotated log when it's recent enough to be worth
     * anything, keeping the combined size under [MAX_UPLOAD_SIZE].
     */
    private fun withRotatedLog(fileStr: String): String {
        val budget = MAX_UPLOAD_SIZE - fileStr.length
        if (budget <= 0) return fileStr

        val metadata = StorageManager.getFileDetails(PATH_LOGS, FILE_BACKUP) ?: return fileStr
        val ageMs = DateUtils.nowMs() - metadata.lastModified
        if (ageMs > MAX_ROTATED_AGE_MS) {
            log.d("withRotatedLog: skipping $FILE_BACKUP, age: ${ageMs / 1000 / 60} mins")
            return fileStr
        }

        val rotatedStr = StorageManager.readFile(PATH_LOGS, FILE_BACKUP) ?: return fileStr
        // keep the newest entries and drop the leading partial line
        val tail = when {
            rotatedStr.length <= budget -> rotatedStr
            else -> rotatedStr.takeLast(budget).substringAfter('\n', "")
        }
        if (tail.isEmpty()) return fileStr

        log.d("withRotatedLog: including ${tail.length} of ${rotatedStr.length} chars from $FILE_BACKUP")
        return "$tail\n----- log rotated -----\n$fileStr"
    }

    suspend fun uploadLog(fileName: String, fileData: ByteArray): Result<String> {
        return try {
            val url = "$LOGGING_URL/storage/v1/object/logs/$fileName"
            log.d("uploadLog: uploading to $url, size: ${fileData.size} bytes")
            val response = NetworkManager.httpClient.post(url) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $LOGGING_API")
                    append("apikey", LOGGING_API)
                    append(HttpHeaders.ContentType, "text/plain")
                    append("x-upsert", "true")
                }
                setBody(fileData)
            }

            if (response.status.isSuccess()) {
                log.d("uploadLog: file uploaded")
                Result.success(response.bodyAsText())
            } else {
                log.e("uploadLog: upload failed: ${response.status}")
                Result.failure(Exception("upload failed: ${response.status}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("uploadLog: Exception: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * called by [installCrashHandler] when an uncaught exception is on its way out.
     *
     * synchronous, because the process is about to die and an async write can't be trusted to
     * flush. never throws: a failure here would mask the original throwable, which is worse.
     */
    fun recordCrash(throwable: Throwable, threadName: String) {
        try {
            val timestamp = DateUtils.formatDate("yyyyMMdd-HHmmss")
            val content = buildString {
                appendLine("CRASH at $timestamp")
                appendLine("APP: $APP_VERSION")
                appendLine("PLATFORM: ${PlatformUtils.getPlatformName()} ${PlatformUtils.getPlatformVersion()}")
                appendLine("THREAD: $threadName")
                appendLine("EXCEPTION: ${throwable::class.simpleName}: ${throwable.message}")
                appendLine("STACK:")
                append(throwable.stackTraceToString())
            }
            StorageManager.writeFileSync(PATH_CRASHES, "crash-$timestamp.txt", content)

            // also log it normally, to note where in the run the crash happened
            log.e("CRASH: ${throwable::class.simpleName}: ${throwable.message}", throwable)
        } catch (_: Throwable) {
            // swallow — re-throwing the original exception is what matters
        }
    }

    /**
     * append last session's crash reports to the log and send them up.
     *
     * the append is synchronous so [uploadLogs] is guaranteed to see them. a crash file is deleted
     * only once the upload succeeded, so a failed upload retries on the next launch.
     */
    private fun handlePendingCrashes() {
        val files = StorageManager.listFiles(PATH_CRASHES)
        if (files.isEmpty()) return

        log.w("handlePendingCrashes: ${files.size} crash file(s) from previous session")

        for (name in files) {
            val content = StorageManager.readFile(PATH_CRASHES, name) ?: continue
            val block = "\n----- CRASH from previous session ($name) -----\n$content\n"
            StorageManager.writeFileSync(PATH_LOGS, FILE_LOG, block, append = true)
        }
        // those bypassed AppLogWriter, so its cached log size no longer matches the file
        invalidateLogFileSize()

        scope.launch {
            ToastManager.showToast(getString(Res.string.reporting_crash), success = false)
        }

        uploadLogs(text = "CRASH") { result ->
            if (result.isSuccess) {
                files.forEach { StorageManager.deleteFile(PATH_CRASHES, it) }
            } else {
                log.w("handlePendingCrashes: upload failed, keeping crash files for retry")
            }
        }
    }
}
