package com.jpage4500.devicemanager.data

/** one entry in a device folder listing. */
data class DeviceFile(
    val name: String,
    val path: String,
    val size: Long,
    val modifiedMs: Long,
    val mode: Int,
    val uid: Int = 0,
    val gid: Int = 0,
    /** false on a device with no `stat_v2` — the v1 records carry no ownership at all. */
    val ownerKnown: Boolean = true,
) {
    val isDirectory: Boolean get() = AdbFileMode.isDir(mode)
    val isSymlink: Boolean get() = AdbFileMode.isLink(mode)
}

/**
 * the POSIX type field.
 *
 * jadb tests single bits (`mode and (1 shl 14)` for a directory), which misreads anything that isn't
 * a plain file or folder — a socket sets both bits it looks at and reads as a directory *and* a
 * symlink. the type is a 4-bit field and has to be masked out whole.
 */
object AdbFileMode {
    const val S_IFMT = 0xF000
    const val S_IFDIR = 0x4000
    const val S_IFLNK = 0xA000
    const val S_IFREG = 0x8000

    fun isDir(mode: Int): Boolean = mode and S_IFMT == S_IFDIR
    fun isLink(mode: Int): Boolean = mode and S_IFMT == S_IFLNK
    fun isFile(mode: Int): Boolean = mode and S_IFMT == S_IFREG
}
