package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import com.jpage4500.devicemanager.util.*
import kotlinx.cinterop.*
import platform.AVFoundation.*
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.*

private val log = LoggerConfig.createLogger("QrScanner")

/**
 * AVFoundation, which decodes QR codes itself — no library, no model to download.
 *
 * A capture session with an `AVCaptureMetadataOutput` asked for one object type is the whole of it;
 * the camera hardware and the decoder are both the system's. What has to be written by hand is the
 * view controller around it, because Compose has no camera preview and this is presented over the
 * whole app rather than inside a composable.
 *
 * `NSCameraUsageDescription` is required in Info.plist or the app is killed on first use.
 */
@Composable
actual fun rememberQrScanner(onScanned: (String) -> Unit): (() -> Unit)? {
    val current by rememberUpdatedState(onScanned)
    return remember {
        {
            val root = UIApplication.sharedApplication.keyWindow?.rootViewController
            if (root == null) {
                log.w("rememberQrScanner: no root view controller")
            } else {
                val controller = ScannerViewController { code -> current(code) }
                controller.modalPresentationStyle = UIModalPresentationFullScreen
                root.presentViewController(controller, animated = true, completion = null)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class ScannerViewController(
    private val onScanned: (String) -> Unit,
) : UIViewController(nibName = null, bundle = null), AVCaptureMetadataOutputObjectsDelegateProtocol {

    private val session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer? = null

    // the delegate fires per frame while the code is in view; the first one wins and the rest are
    // dropped, or a single scan would present a dozen connections
    private var done = false

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor

        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
        if (input == null || !session.canAddInput(input)) {
            log.w("viewDidLoad: no camera")
            return
        }
        session.addInput(input)

        val output = AVCaptureMetadataOutput()
        if (!session.canAddOutput(output)) {
            log.w("viewDidLoad: no metadata output")
            return
        }
        session.addOutput(output)
        // the delegate has to be set before the object types, which are only valid once the output
        // is attached to a session that has an input
        output.setMetadataObjectsDelegate(this, dispatch_get_main_queue())
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)

        val layer = AVCaptureVideoPreviewLayer(session = session)
        layer.videoGravity = AVLayerVideoGravityResizeAspectFill
        layer.frame = view.layer.bounds
        view.layer.addSublayer(layer)
        preview = layer

        addCancelButton()

        // starting the session blocks for a moment, which on the main thread is a dropped frame at
        // exactly the moment the screen appears
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0uL)) {
            session.startRunning()
        }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.layer.bounds
    }

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        if (done) return
        val value = didOutputMetadataObjects
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue }
            ?: return
        done = true
        log.d("captureOutput: read ${value.length} chars")
        finish { onScanned(value) }
    }

    private fun addCancelButton() {
        val button = UIButton.buttonWithType(UIButtonTypeSystem)
        button.setTitle("Cancel", forState = UIControlStateNormal)
        button.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        button.addTarget(this, NSSelectorFromString("onCancel"), UIControlEventTouchUpInside)
        button.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(button)
        NSLayoutConstraint.activateConstraints(
            listOf(
                button.centerXAnchor.constraintEqualToAnchor(view.centerXAnchor),
                button.bottomAnchor.constraintEqualToAnchor(view.safeAreaLayoutGuide.bottomAnchor, -32.0),
            )
        )
    }

    @ObjCAction
    fun onCancel() {
        done = true
        finish {}
    }

    private fun finish(then: () -> Unit) {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0uL)) {
            session.stopRunning()
        }
        dismissViewControllerAnimated(true) { then() }
    }
}
