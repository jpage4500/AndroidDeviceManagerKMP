package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*

/**
 * read a QR code with the camera.
 *
 * The way into this app that involves no typing: Device Manager's Share Server dialog shows a QR of
 * its own `adm://` connection string, and scanning it carries the host, the port and a sixteen
 * character auth token across in one go. See docs/MOBILE-UI.md.
 *
 * @return a function that starts a scan, or **null** where the platform has no scanner — the caller
 *   disables the button rather than offering something that does nothing
 */
@Composable
expect fun rememberQrScanner(onScanned: (String) -> Unit): (() -> Unit)?
