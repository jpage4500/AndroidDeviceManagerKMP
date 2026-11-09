package com.jpage4500.devicemanager.transport.demo

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*

/**
 * one demo device's file system, in memory.
 *
 * Seeded with what a phone that's been in use for a while has — camera roll, screenshots, a few
 * downloads, an APK — and then changed for real by whatever the Files page does to it: a delete is
 * gone, an upload is there with its bytes, a rename moves everything under it. Nothing is written
 * anywhere, so turning Demo Mode off and on again starts over.
 */
internal class DemoFiles(private val device: DemoDevice, private val nowMs: Long) {
    private class Node(val file: DeviceFile, var bytes: ByteArray? = null)

    private val nodes = mutableMapOf<String, Node>()

    init {
        seed()
    }

    fun list(path: String): Result<List<DeviceFile>> {
        val dir = normalize(path)
        val node = nodes[dir]
        if (node == null || !(node.file.isDirectory || node.file.isSymlink)) {
            return Result.failure(IllegalStateException("$dir: No such file or directory"))
        }
        val prefix = if (dir == "/") "/" else "$dir/"
        return Result.success(
            nodes.values
                .map { it.file }
                .filter { it.path.startsWith(prefix) && it.path != dir && !it.path.substring(prefix.length).contains('/') }
        )
    }

    fun exists(path: String): Boolean = nodes.containsKey(normalize(path))

    fun isDirectory(path: String): Boolean = nodes[normalize(path)]?.file?.isDirectory == true

    fun read(path: String): Result<ByteArray> {
        val node = nodes[normalize(path)]
            ?: return Result.failure(IllegalStateException("$path: No such file or directory"))
        if (node.file.isDirectory) return Result.failure(IllegalStateException("$path: Is a directory"))
        return Result.success(node.bytes ?: contentFor(node.file))
    }

    fun write(path: String, bytes: ByteArray, mode: Int = FILE_MODE) {
        val p = normalize(path)
        makeDirs(parentOf(p))
        nodes[p] = Node(DeviceFile(nameOf(p), p, bytes.size.toLong(), DateUtils.nowMs(), AdbFileMode.S_IFREG or (mode and 0xFFF)), bytes)
    }

    fun makeDirs(path: String) {
        val p = normalize(path)
        if (p == "/" || nodes.containsKey(p)) return
        makeDirs(parentOf(p))
        nodes[p] = Node(DeviceFile(nameOf(p), p, 4096, DateUtils.nowMs(), DIR_MODE))
    }

    /** `rm -rf`: the path and everything under it. false when there was nothing there. */
    fun remove(path: String): Boolean {
        val p = normalize(path)
        if (!nodes.containsKey(p)) return false
        nodes.keys.filter { it == p || it.startsWith("$p/") }.forEach { nodes.remove(it) }
        return true
    }

    /** `mv`, into a folder when [to] is one, carrying everything under [from] along. */
    fun move(from: String, to: String): Boolean {
        val src = normalize(from)
        if (!nodes.containsKey(src)) return false
        var dst = normalize(to)
        if (isDirectory(dst)) dst = if (dst == "/") "/${nameOf(src)}" else "$dst/${nameOf(src)}"
        if (dst == src) return true
        val moving = nodes.keys.filter { it == src || it.startsWith("$src/") }
        makeDirs(parentOf(dst))
        moving.forEach { old ->
            val node = nodes.remove(old) ?: return@forEach
            val path = dst + old.removePrefix(src)
            nodes[path] = Node(node.file.copy(name = nameOf(path), path = path), node.bytes)
        }
        return true
    }

    fun copy(from: String, to: String): Boolean {
        val bytes = read(from).getOrNull() ?: return false
        var dst = normalize(to)
        if (isDirectory(dst)) dst = "$dst/${nameOf(normalize(from))}"
        write(dst, bytes)
        return true
    }

    // ── what's on the phone to begin with ───────────────────────────────────────────────────────

    private fun seed() {
        val root = listOf(
            "acct", "apex", "cache", "config", "data", "data_mirror", "debug_ramdisk", "dev", "linkerconfig",
            "metadata", "mnt", "odm", "oem", "postinstall", "proc", "product", "second_stage_resources",
            "storage", "sys", "system", "system_ext", "vendor",
        )
        nodes["/"] = Node(DeviceFile("", "/", 4096, ago(DAY * 200), DIR_MODE))
        root.forEach { dir("/$it", ago(DAY * 200)) }
        link("/sdcard", "/storage/self/primary")
        link("/bin", "/system/bin")
        link("/etc", "/system/etc")
        dir("/data/local/tmp", ago(DAY * 3))
        dir("/storage/emulated", ago(DAY * 200))
        dir("/storage/self", ago(DAY * 200))

        val sd = "/sdcard"
        listOf("Alarms", "Audiobooks", "Documents", "Movies", "Music", "Notifications", "Podcasts", "Recordings", "Ringtones")
            .forEachIndexed { i, name -> dir("$sd/$name", ago(DAY * (120 - i * 7))) }
        dir("$sd/.thumbnails", ago(DAY * 2))
        file("$sd/.nomedia", 0, ago(DAY * 90))

        listOf("com.acme.inventory", "com.android.chrome", "com.google.android.apps.photos", "com.google.android.gm").forEach { pkg ->
            dir("$sd/Android/data/$pkg/files", ago(DAY * 30))
            dir("$sd/Android/data/$pkg/cache", ago(HOUR * 5))
        }
        file("$sd/Android/data/com.acme.inventory/files/sync.log", 48_211, ago(HOUR * 2))
        file("$sd/Android/data/com.acme.inventory/files/settings.json", 1_284, ago(DAY * 4))
        dir("$sd/Android/media", ago(DAY * 60))
        dir("$sd/Android/obb", ago(DAY * 60))

        // the camera roll: Pixels name them PXL_, everything else IMG_
        val prefix = if (device.brand == "google") "PXL_" else "IMG_"
        for (i in 0 until 16) {
            val time = ago(HOUR * (6 + i * 31L) + MINUTE * (i * 17L))
            val stamp = DateUtils.formatDate(DateUtils.timeToDate(time), "yyyyMMdd_HHmmss")
            file("$sd/DCIM/Camera/$prefix$stamp${(100 + i * 7).toString().takeLast(3)}.jpg", 2_400_000L + i * 173_431L, time)
        }
        file("$sd/DCIM/Camera/${prefix}walkthrough.mp4", 84_220_311, ago(DAY * 6))
        dir("$sd/DCIM/.thumbnails", ago(DAY))

        for (i in 0 until 7) {
            val time = ago(HOUR * (2 + i * 19L))
            val stamp = DateUtils.formatDate(DateUtils.timeToDate(time), "yyyyMMdd-HHmmss")
            file("$sd/Pictures/Screenshots/Screenshot_$stamp.png", 412_000L + i * 21_337L, time)
        }
        file("$sd/Pictures/wallpaper.png", 1_843_110, ago(DAY * 40))
        file("$sd/Pictures/team-offsite.jpg", 3_120_442, ago(DAY * 22))

        file("$sd/Download/acme-inventory-3.8.2.apk", 24_318_204, ago(DAY * 9))
        file("$sd/Download/acme-inventory-3.9.0-beta1.apk", 24_771_960, ago(DAY * 2))
        file("$sd/Download/field-manual.pdf", 6_204_118, ago(DAY * 15))
        file("$sd/Download/logs-${DateUtils.formatDate(DateUtils.timeToDate(ago(DAY * 3)), "yyyy-MM-dd")}.zip", 1_022_480, ago(DAY * 3))
        file("$sd/Download/sample-orders.json", 18_309, ago(DAY * 5))
        file("$sd/Documents/inventory-report-oct.pdf", 812_442, ago(DAY * 8))
        file("$sd/Documents/shipping-labels.pdf", 233_901, ago(DAY * 11))
        file("$sd/Documents/notes.txt", 1_046, ago(HOUR * 30))
        file("$sd/Movies/onboarding.mp4", 152_004_331, ago(DAY * 33))
        file("$sd/Recordings/standup.m4a", 4_801_223, ago(DAY * 1))
    }

    private fun dir(path: String, time: Long) {
        makeDirs(parentOf(path))
        nodes[path] = Node(DeviceFile(nameOf(path), path, 4096, time, DIR_MODE))
    }

    private fun file(path: String, size: Long, time: Long) {
        makeDirs(parentOf(path))
        nodes[path] = Node(DeviceFile(nameOf(path), path, size, time, FILE_MODE))
    }

    private fun link(path: String, @Suppress("UNUSED_PARAMETER") target: String) {
        nodes[path] = Node(DeviceFile(nameOf(path), path, 21, ago(DAY * 200), AdbFileMode.S_IFLNK or 0b111_111_111))
    }

    /**
     * the bytes a seeded file downloads as: a drawn screen for a picture, text for a text file, and
     * a placeholder for the rest — never the size the listing claims, which for a video is 100 MB
     * of nothing.
     */
    private fun contentFor(file: DeviceFile): ByteArray {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".png") || name.endsWith(".jpg") -> DemoScreen.render(device, DateUtils.formatDate("h:mm"))
            name.endsWith(".json") -> """{"orders":[{"id":1042,"status":"shipped","items":3},{"id":1043,"status":"packing","items":1}]}""".encodeToByteArray()
            name.endsWith(".txt") || name.endsWith(".log") ->
                "demo file on ${device.nickname ?: device.model}\n${file.path}\n".encodeToByteArray()
            else -> "demo placeholder for ${file.name}\n".encodeToByteArray()
        }
    }

    private fun ago(ms: Long): Long = nowMs - ms - (device.index * 7_919L * 1000) % HOUR

    private fun normalize(path: String): String {
        val parts = mutableListOf<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts += part
            }
        }
        // /sdcard is a link on a real phone; here it is the folder itself, so both spellings land
        val joined = "/" + parts.joinToString("/")
        return joined.replace(Regex("^/storage/(emulated/0|self/primary)"), "/sdcard")
    }

    private fun parentOf(path: String): String = path.substringBeforeLast('/', "").ifEmpty { "/" }

    private fun nameOf(path: String): String = path.substringAfterLast('/')

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val DIR_MODE = AdbFileMode.S_IFDIR or 0b111_111_001
        const val FILE_MODE = AdbFileMode.S_IFREG or 0b110_110_000
    }
}
