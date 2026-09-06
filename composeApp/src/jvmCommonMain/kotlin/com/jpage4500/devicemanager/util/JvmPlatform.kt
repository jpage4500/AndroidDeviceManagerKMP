package com.jpage4500.devicemanager.util

/** the JVM heap, which is the memory this app actually runs out of. desktop and android agree. */
fun jvmMemoryInfo(): MemoryInfo {
    val runtime = Runtime.getRuntime()
    val total = runtime.maxMemory()
    // free = what's left of the ceiling, not what's left of the currently-committed heap: the JVM
    // grows total up to max on demand, so runtime.freeMemory() alone reads far lower than it is
    val used = runtime.totalMemory() - runtime.freeMemory()
    return MemoryInfo(freeBytes = total - used, totalBytes = total)
}

/** two hex digits of the current thread, or `UI` when [uiThreadName] matches. */
fun jvmThreadId(uiThreadName: String): String {
    val thread = Thread.currentThread()
    if (thread.name.equals(uiThreadName, ignoreCase = true)) return "UI"
    return "%02X".format(thread.name.hashCode() and 0xFF)
}
