package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*

/**
 * no scanner on the desktop.
 *
 * A desktop is the machine *showing* the QR, not the one reading it — and a webcam pointed at your
 * own screen is not a connection flow. The desktop types an address, or copies a connection string.
 */
@Composable
actual fun rememberQrScanner(onScanned: (String) -> Unit): (() -> Unit)? = null
