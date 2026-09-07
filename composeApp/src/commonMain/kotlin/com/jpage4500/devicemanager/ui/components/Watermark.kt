package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.imageResource

/**
 * the app's background pattern, tiled and faded behind whatever this is applied to.
 *
 * **Tiled at its own size rather than stretched.** The image is a repeating pattern, and scaling it
 * to the window would make it a different size in every window — the Swing app's note, and the
 * reason it uses a `TexturePaint` there and an [ImageShader] here.
 *
 * Drawn behind the content, so a row's own background covers it wherever there is one; what shows
 * through is the empty space below the last row, and the white rows at 10%.
 */
@Composable
fun Modifier.watermark(alpha: Float = Dimens.backgroundAlpha): Modifier {
    val image = imageResource(Res.drawable.background)
    // the shader holds the decoded bitmap, so it is built once per image rather than per frame
    val brush = remember(image) {
        ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))
    }
    return drawBehind { drawRect(brush = brush, alpha = alpha) }
}
