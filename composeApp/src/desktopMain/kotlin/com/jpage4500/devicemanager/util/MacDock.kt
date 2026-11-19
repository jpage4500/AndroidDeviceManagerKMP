package com.jpage4500.devicemanager.util

import com.sun.jna.*
import com.sun.jna.Function as NativeFunction
import java.awt.Taskbar
import javax.imageio.ImageIO

private val log = LoggerConfig.createLogger("MacDock")

// NSApplicationActivationPolicy: in the Dock and ⌘-Tab, or neither
private const val POLICY_REGULAR = 0L
private const val POLICY_ACCESSORY = 1L

/**
 * the app's Dock icon on macOS: whether there is one, and what it shows.
 *
 * **Hiding it** is `NSApplication.setActivationPolicy:`, which AWT doesn't expose — its only switch
 * is `apple.awt.UIElement`, read once at launch and never again. So this is the Swing app's
 * `Utils.setDockIconVisible`, ported: two Objective-C calls through JNA, queued onto AppKit's main
 * thread, since AppKit called from anywhere else is undefined.
 *
 * **Its image** is set at runtime from `icon.png` rather than left to the bundle's `icon.icns`,
 * because macOS 26 draws a non-conforming icns shrunk onto a grey plate in the Dock and ⌘-Tab, and
 * caches that composite. jDeploy writes its own icns from the repo-root icon.png, and `gradlew run`
 * passes ours as `-Xdock:icon`; an image handed to the Dock while running is drawn as given, which
 * is what fixed the same symptom in the Swing app. `icon.png` is already on Apple's grid — a
 * 412px body centred in 512, the 824-in-1024 inset — so it lines up with the icons beside it.
 */
object MacDock {
    // the main queue holds a raw pointer to this, so it lives as long as the app does
    private var callback: DispatchCallback? = null

    @Volatile
    private var visible = true

    // JNA finds the method to call by reflection, so a plain public interface with exactly one —
    // not a fun-interface lambda, whose generated class it may not be able to reach
    interface DispatchCallback : Callback {
        fun callback(context: Pointer?)
    }

    /** show or hide the Dock icon; a no-op off macOS, or when it's already that way */
    @Synchronized
    fun setVisible(isVisible: Boolean) {
        if (!isMac() || visible == isVisible) return
        visible = isVisible
        log.d("setVisible: $isVisible")
        try {
            val dispatch = callback ?: createCallback().also { callback = it }
            val system = NativeLibrary.getInstance("System")
            val mainQueue = system.getGlobalVariableAddress("_dispatch_main_q")
            system.getFunction("dispatch_async_f").invoke(Void::class.java, arrayOf(mainQueue, null, dispatch))
        } catch (e: Throwable) {
            log.e("setVisible: ${e.message}")
            return
        }
        // a regular app gets its bundle's icon back, plate and all
        if (isVisible) applyIcon()
    }

    /** hand the Dock our own image in place of the bundle's icns — see the class comment */
    fun applyIcon() {
        if (!isMac() || !Taskbar.isTaskbarSupported()) return
        try {
            val taskbar = Taskbar.getTaskbar()
            if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return
            val image = MacDock::class.java.getResourceAsStream("/icon.png")?.use { ImageIO.read(it) }
            if (image == null) {
                log.w("applyIcon: icon.png not on the classpath")
                return
            }
            taskbar.iconImage = image
        } catch (e: Exception) {
            log.e("applyIcon: ${e.message}")
        }
    }

    private fun createCallback(): DispatchCallback {
        val msgSend = NativeFunction.getFunction("objc", "objc_msgSend")
        val selector = NativeFunction.getFunction("objc", "sel_registerName")
        fun sel(name: String) = selector.invoke(Pointer::class.java, arrayOf(name)) as Pointer
        val appClass = NativeFunction.getFunction("objc", "objc_getClass")
            .invoke(Pointer::class.java, arrayOf("NSApplication")) as Pointer
        val app = msgSend.invoke(Pointer::class.java, arrayOf(appClass, sel("sharedApplication"))) as Pointer
        val setPolicy = sel("setActivationPolicy:")
        val activate = sel("activateIgnoringOtherApps:")
        return object : DispatchCallback {
            override fun callback(context: Pointer?) {
                // read when it runs rather than when queued, so two quick toggles land on the last one
                val show = visible
                msgSend.invoke(Void::class.java, arrayOf(app, setPolicy, if (show) POLICY_REGULAR else POLICY_ACCESSORY))
                // a regular app that isn't active leaves its window behind whatever was in front
                if (show) msgSend.invoke(Void::class.java, arrayOf(app, activate, true))
            }
        }
    }
}
