package com.jpage4500.devicemanager.manager

import android.content.*
import com.jpage4500.devicemanager.util.*
import java.io.*

private val log = LoggerConfig.createLogger("AdbDaemon")

/**
 * the bundled `adb`, run as a child process.
 *
 * **Why it lives in `jniLibs` and is called `libadb.so`.** Android has forbidden executing anything
 * out of an app's writable data directory since API 29 — the whole directory is mounted no-exec —
 * and the one place left is `nativeLibraryDir`, which the installer populates from the APK with the
 * exec bit set. Only files matching `lib*.so` are packaged there, so an executable that is not a
 * library has to be named like one anyway. It is not a `.so` and is never `dlopen`ed; it is a plain
 * PIE executable that happens to be named to get itself installed somewhere runnable. This is the
 * same arrangement LADB and Shizuku use.
 *
 * It also needs `android:extractNativeLibs="true"` — set through `useLegacyPackaging` in
 * `androidApp/build.gradle.kts`. With the modern default the libraries are mapped straight out of
 * the APK and never land on disk, so `nativeLibraryDir` would hold nothing to run.
 *
 * A statically linked `adb` needs nothing else. One that isn't — Termux's build links against its
 * own libc++ and protobuf — starts only if those sit beside it, which is why `LD_LIBRARY_PATH`
 * points at the binary's own directory. See `scripts/fetch-adb-android.sh`.
 */
actual object AdbDaemon {

    actual val isSupported: Boolean get() = true

    actual val isBundled: Boolean get() = binary()?.canExecute() == true

    @Volatile
    private var process: Process? = null

    actual val isAlive: Boolean get() = process?.isAlive == true

    actual fun launch(): Boolean {
        kill()
        val executable = binary()
        if (executable == null || !executable.canExecute()) {
            log.e("launch: no adb binary for this ABI")
            return false
        }

        val context = context() ?: run {
            log.e("launch: no context yet")
            return false
        }
        // adb writes its RSA key under here; the app's own files dir is the only writable place, and
        // HOME is what older adb reads while ANDROID_USER_HOME is what current adb reads — both are
        // set rather than guessing the build.
        //
        // **`.android` is created too, and that is not tidiness.** adb does not create it: it writes
        // `adbkey` and `adb_known_hosts.pb` there through a write-then-rename, and the rename fails
        // with "No such file or directory" if the directory is missing. The key then exists only in
        // that process, so every restart of the server would generate a new one — and a device
        // paired against the old key would have to be paired again
        val home = File(context.filesDir, "adb").apply { mkdirs() }
        val userHome = File(home, ".android").apply { mkdirs() }

        return try {
            // `tcp:localhost:<port>`, and **not** `tcp:127.0.0.1:<port>`, which adb rejects with
            // "listening on specified hostname currently unsupported". Its socket_spec.cpp has
            //
            //     static bool tcp_host_is_local(std::string_view hostname) {
            //         // FIXME
            //         return hostname.empty() || hostname == "localhost";
            //     }
            //
            // so a literal loopback address is not recognised as loopback. Naming the host at all
            // is what pins this to the loopback path: an empty host there binds every interface
            // whenever adb's own `gListenAll` is set, and 5037 has no authentication whatsoever
            val builder = ProcessBuilder(
                executable.absolutePath,
                "-L", "tcp:localhost:${LocalAdbServer.server.port}",
                "server",
                "nodaemon",
            )
            builder.environment().apply {
                put("HOME", home.absolutePath)
                put("ANDROID_USER_HOME", userHome.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                // a statically linked adb ignores this; one built against its own libc++ and
                // protobuf will not start without it, and the failure is a silent instant exit.
                // nativeLibraryDir is where any companion `lib*.so` lands beside the binary
                put("LD_LIBRARY_PATH", executable.parent.orEmpty())
            }
            // one stream instead of two, because two need two drainers to avoid filling a pipe
            builder.redirectErrorStream(true)
            val started = builder.start()
            process = started
            drain(started)
            log.i("launch: started ${executable.name}")
            true
        } catch (e: IOException) {
            log.e("launch: ${e.message}")
            false
        }
    }

    actual fun kill() {
        process?.let {
            log.d("kill: stopping the adb server")
            it.destroy()
        }
        process = null
    }

    /**
     * put the server's own output in the app log.
     *
     * Not decoration — a pipe nobody reads fills up and the server blocks on its next write, so this
     * has to happen whether anyone wants the lines or not. Logged at debug: adb is chatty about USB
     * it can't reach, which on a phone is every device.
     */
    private fun drain(from: Process) {
        Thread {
            runCatching {
                from.inputStream.bufferedReader().forEachLine { line ->
                    log.d("adb: $line")
                }
            }
            // waitFor, not exitValue: the stream can end a moment before the process does, and
            // exitValue then throws and the log says "exited with null" instead of the code
            log.d("drain: adb exited with ${runCatching { from.waitFor() }.getOrNull()}")
        }.apply {
            isDaemon = true
            name = "adb-server-output"
        }.start()
    }

    /** the extracted binary, or null when this build shipped none for the running ABI. */
    private fun binary(): File? {
        val context = context() ?: return null
        val file = File(context.applicationInfo.nativeLibraryDir, BINARY)
        return file.takeIf { it.exists() }
    }

    // AndroidContextProvider.context is lateinit and set from MainActivity.onCreate; isBundled is
    // read by the Connect panel, which cannot run before that, but a `lateinit` read is worth
    // guarding rather than relying on that ordering staying true
    private fun context(): Context? =
        runCatching { AndroidContextProvider.context }.getOrNull()

    /** named as a library so the installer puts it somewhere executable — see the class comment. */
    private const val BINARY = "libadb.so"
}
