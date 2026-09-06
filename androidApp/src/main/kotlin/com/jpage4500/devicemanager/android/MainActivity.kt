package com.jpage4500.devicemanager.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jpage4500.devicemanager.initApp
import com.jpage4500.devicemanager.ui.App

/**
 * the phone app.
 *
 * Always the compact layout, and always reading a transport over the network: an Android phone can
 * reach `adbd` on another phone directly, but not the USB fleet on a desk — see
 * docs/ADB-TRANSPORTS.md for which route reaches what.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        initApp(this)

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App(compact = true) }
    }
}
