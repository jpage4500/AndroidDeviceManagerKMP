package com.jpage4500.devicemanager.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.jpage4500.devicemanager.util.*

private val log = LoggerConfig.createLogger("QrScanner")

/**
 * Google's code scanner, which is a whole scanning UI behind one call.
 *
 * The reason for this one rather than ML Kit's own: the scanning happens in a Play Services activity
 * outside this app's process, so there is **no camera permission to request** and no preview,
 * permission flow or lifecycle to write. It downloads its module on first use, which is why a first
 * scan can take a moment.
 */
@Composable
actual fun rememberQrScanner(onScanned: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    // so a scan started before a recomposition still calls the current lambda
    val current by rememberUpdatedState(onScanned)
    return remember(context) { { startScan(context) { code -> current(code) } } }
}

private fun startScan(context: Context, onScanned: (String) -> Unit) {
    // an Activity where there is one: the scanner starts an activity of its own, and an application
    // context can't parent it
    val host = context.findActivity() ?: context
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    GmsBarcodeScanning.getClient(host, options)
        .startScan()
        .addOnSuccessListener { barcode ->
            val value = barcode.rawValue
            log.d("startScan: read ${value?.length ?: 0} chars")
            if (value != null) onScanned(value)
        }
        .addOnCanceledListener { log.d("startScan: cancelled") }
        .addOnFailureListener { log.w("startScan: ${it.message}") }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
