package com.jpage4500.devicemanager.transport.demo

import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.random.*

/**
 * a believable logcat for a demo device, in `-v threadtime` lines — the same text an adb server's
 * stream carries, so the log page's own parser reads it.
 *
 * Ten minutes of history on start (what a real log page asks for), then a few lines every few
 * hundred ms. Most of it is a made-up inventory app syncing over HTTP, with the system chatter
 * around it, a warning now and then and an occasional crash with its stack trace — enough to show
 * off filters, levels, search and the message viewer.
 */
internal object DemoLog {
    private const val HISTORY_MS = 10 * 60_000L
    private const val HISTORY_BATCH = 250

    private const val PID_SYSTEM = "1534"
    private const val PID_SYSTEMUI = "2063"
    private const val PID_PHONE = "2410"
    private const val PID_GMS = "3120"
    private const val PID_APP = "8841"
    private const val PID_CHROME = "6120"
    private const val PID_SURFACE = "712"

    /** pid → process name, sent ahead of the lines so the App column fills in at once. */
    val PROCESSES = mapOf(
        PID_SURFACE to "/system/bin/surfaceflinger",
        PID_SYSTEM to "system_server",
        PID_SYSTEMUI to "com.android.systemui",
        PID_PHONE to "com.android.phone",
        PID_GMS to "com.google.android.gms",
        PID_CHROME to "com.android.chrome",
        PID_APP to "com.acme.inventory",
    )

    private class Line(val pid: String, val level: Char, val tag: String, val message: String)

    fun stream(device: DemoDevice, resuming: Boolean): Flow<LogBatch> = flow {
        val random = Random(device.index * 31 + DateUtils.nowMs().toInt())
        emit(LogBatch(processes = PROCESSES))
        val now = DateUtils.nowMs()
        var t = now - if (resuming) 5_000L else HISTORY_MS
        val history = mutableListOf<String>()
        while (t < now) {
            next(device, random).forEach { history += format(t, it, random) }
            t += random.nextLong(150, 2_200)
        }
        history.chunked(HISTORY_BATCH).forEach { emit(LogBatch(lines = it)) }
        while (currentCoroutineContext().isActive) {
            delay(random.nextLong(150, 900))
            val at = DateUtils.nowMs()
            emit(LogBatch(lines = (0 until random.nextInt(1, 4)).flatMap { next(device, random) }.map { format(at, it, random) }))
        }
    }.flowOn(ioDispatcher)

    /** the last few lines, for `logcat -d` in Commands. */
    fun dump(device: DemoDevice, count: Int): List<String> {
        val random = Random(device.index)
        val now = DateUtils.nowMs()
        return (0 until count).flatMap { i -> next(device, random).map { format(now - (count - i) * 900L, it, random) } }
    }

    private fun format(ms: Long, line: Line, random: Random): String {
        val time = DateUtils.formatDate(DateUtils.timeToDate(ms), "MM-dd HH:mm:ss.SSS")
        // a process's main thread has tid == pid; the rest are its workers
        val tid = if (random.nextInt(3) == 0) line.pid else (line.pid.toInt() + random.nextInt(1, 60)).toString()
        return "$time ${line.pid.padStart(5)} ${tid.padStart(5)} ${line.level} ${line.tag}: ${line.message}"
    }

    /** one event's worth of lines — usually one, a stack trace when something crashes. */
    private fun next(device: DemoDevice, r: Random): List<Line> {
        val order = 1040 + r.nextInt(80)
        val ms = r.nextInt(40, 900)
        return when (r.nextInt(100)) {
            in 0..13 -> listOf(Line(PID_APP, 'D', "OkHttp", "--> GET https://api.acme.example/v2/orders?page=${r.nextInt(1, 6)}"))
            in 14..25 -> listOf(Line(PID_APP, 'D', "OkHttp", "<-- 200 OK https://api.acme.example/v2/orders?page=${r.nextInt(1, 6)} (${ms}ms, ${r.nextInt(2, 40)}.${r.nextInt(10)}kB body)"))
            in 26..32 -> listOf(Line(PID_APP, 'I', "InventorySync", "sync: ${r.nextInt(3, 60)} items updated, ${r.nextInt(0, 3)} conflicts in ${ms}ms"))
            in 33..37 -> listOf(Line(PID_APP, 'D', "OrderRepository", """response: {"id":$order,"status":"${listOf("packing", "shipped", "delivered").random(r)}","items":[{"sku":"AC-${r.nextInt(1000, 9999)}","qty":${r.nextInt(1, 5)}},{"sku":"AC-${r.nextInt(1000, 9999)}","qty":1}],"warehouse":"${device.custom.values.first()}"}"""))
            in 38..40 -> listOf(Line(PID_APP, 'W', "InventorySync", "sync: timed out after 15000ms, retrying (attempt ${r.nextInt(2, 4)}/3)"))
            in 41..42 -> listOf(Line(PID_APP, 'E', "ScannerService", "camera: failed to open camera 0: CAMERA_IN_USE"))
            in 43..48 -> listOf(Line(PID_SYSTEM, 'I', "ActivityTaskManager", "Displayed com.acme.inventory/.ui.OrderDetailActivity for user 0: +${ms}ms"))
            in 49..52 -> listOf(Line(PID_SYSTEM, 'I', "ActivityManager", "Start proc ${r.nextInt(9000, 9999)}:com.google.android.apps.photos/u0a${r.nextInt(150, 260)} for service {com.google.android.apps.photos/.backup.BackupService}"))
            in 53..58 -> listOf(Line(PID_SYSTEM, 'D', "WifiClientModeImpl", "RSSI changed: -${r.nextInt(42, 78)} dBm"))
            in 59..62 -> listOf(Line(PID_SYSTEM, 'I', "BatteryService", "level=${device.batteryLevel} temp=${device.temperatureTenths} plugged=${if (device.charging) 2 else 0}"))
            in 63..67 -> listOf(Line(PID_SYSTEMUI, 'D', "StatusBar", "disable<e i a s b h r c s > disable2<q i n >"))
            in 68..71 -> listOf(Line(PID_SYSTEMUI, 'V', "NotifCollection", "onEntryAdded: 0|com.acme.inventory|${order}|null|10231"))
            in 72..76 -> listOf(Line(PID_GMS, 'I', "GmsClientEvents", "connect: package=com.acme.inventory, service=com.google.android.gms.location"))
            in 77..79 -> listOf(Line(PID_GMS, 'W', "GCoreFlp", "No location to return for getLastLocation()"))
            in 80..83 -> listOf(Line(PID_PHONE, 'D', "TelephonyRegistry", "notifySignalStrength: subId=1 level=${r.nextInt(1, 5)}"))
            in 84..86 -> listOf(Line(PID_CHROME, 'I', "chromium", "[INFO:CONSOLE(1)] \"service worker registered\", source: https://portal.acme.example/app.js (1)"))
            in 87..90 -> listOf(Line(PID_SURFACE, 'D', "SurfaceFlinger", "Finished setting power mode 2 on display 4619827259835644672"))
            in 91..94 -> listOf(Line(PID_SYSTEM, 'D', "PackageManager", "Instant app installer not found"))
            in 95..97 -> listOf(Line(PID_APP, 'I', "chatty", "uid=10231(com.acme.inventory) RenderThread identical ${r.nextInt(2, 9)} lines"))
            else -> crash()
        }
    }

    private fun crash(): List<Line> = listOf(
        "FATAL EXCEPTION: main",
        "Process: com.acme.inventory, PID: $PID_APP",
        "java.lang.IllegalStateException: Order 0 has no line items",
        "\tat com.acme.inventory.orders.OrderDetailViewModel.load(OrderDetailViewModel.kt:88)",
        "\tat com.acme.inventory.ui.OrderDetailActivity.onResume(OrderDetailActivity.kt:61)",
        "\tat android.app.Activity.performResume(Activity.java:8985)",
        "\tat android.os.Looper.loop(Looper.java:317)",
        "\tat android.app.ActivityThread.main(ActivityThread.java:8705)",
    ).map { Line(PID_APP, 'E', "AndroidRuntime", it) }
}
