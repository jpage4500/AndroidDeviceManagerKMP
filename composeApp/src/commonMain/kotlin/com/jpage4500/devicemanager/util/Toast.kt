package com.jpage4500.devicemanager.util

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val log = LoggerConfig.createLogger("ToastManager")

enum class ToastType { INFO, SUCCESS, ERROR }

data class ToastData(
    val message: String,
    val type: ToastType,
    val actionLabel: String? = null,
    val actionCallback: (() -> Unit)? = null,
)

/**
 * a short message to the user, from anywhere.
 *
 * callable from non-UI code — a failed `adb` command, a crash report going up — which is the point:
 * a manager has no composable to show a snackbar from. one at a time, newest wins. ported from
 * DashboardWeatherKMP.
 *
 * **drawn in the status bar** where a page has one — [StatusToastSlot], the way FileExplorerKMP does
 * it — so it covers no row of the table somebody is about to click. [ToastHost] floats it over the
 * bottom of the window only while no status bar is on screen: the phone, and the desktop pages
 * without one.
 */
object ToastManager {
    private const val DURATION_MS = 3000L

    private val _current = MutableStateFlow<ToastData?>(null)
    val current: StateFlow<ToastData?> = _current.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var dismissJob: Job? = null

    // how many status bars are composed and drawing [current]; while any is, [ToastHost] stays quiet
    internal var statusBars by mutableIntStateOf(0)

    fun showToast(
        message: String,
        success: Boolean? = null,
        actionLabel: String? = null,
        actionCallback: (() -> Unit)? = null,
    ) {
        val type = when (success) {
            true -> ToastType.SUCCESS
            false -> ToastType.ERROR
            null -> ToastType.INFO
        }
        log.d("showToast: $type - $message")
        dismissJob?.cancel()
        _current.value = ToastData(message, type, actionLabel, actionCallback)
        dismissJob = scope.launch {
            delay(DURATION_MS)
            _current.value = null
        }
    }

    fun dismiss() {
        dismissJob?.cancel()
        _current.value = null
    }
}

/**
 * where a toast is drawn when no status bar is — place once, at the app root.
 *
 * a Popup rather than a Dialog: a Popup costs no window on any of the three platforms.
 */
@Composable
fun ToastHost() {
    val toast by ToastManager.current.collectAsState()
    val data = toast ?: return
    if (ToastManager.statusBars > 0) return
    Popup(alignment = Alignment.BottomCenter, onDismissRequest = { ToastManager.dismiss() }) {
        Box(Modifier.padding(bottom = Dimens.gapLarge * 2)) { ToastCard(data) }
    }
}

/**
 * the part of a status bar a toast takes over: [content] normally, the toast for as long as it's up.
 *
 * one line of the bar says one thing, and what it said is back the moment the toast goes. Composing
 * this is what tells [ToastHost] there's a bar to draw in, so every page's status bar has exactly one.
 */
@Composable
fun StatusToastSlot(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    DisposableEffect(Unit) {
        ToastManager.statusBars++
        onDispose { ToastManager.statusBars-- }
    }
    val toast by ToastManager.current.collectAsState()
    Box(modifier, contentAlignment = contentAlignment) {
        val data = toast
        if (data == null) {
            content()
        } else {
            // keyed so one toast's hover and overflow don't carry over to the one replacing it
            key(data) { StatusToast(data) }
        }
    }
}

/**
 * the toast as a pill in the bar. the tint is what makes up for being small — most of these are
 * failures, and a red one has to be seen without a shadow to help.
 *
 * clicking anywhere but the button dismisses it. the tooltip is the message again and appears only
 * when it didn't fit, since one line is all a status bar has.
 */
@Composable
private fun StatusToast(data: ToastData) {
    val fill = when (data.type) {
        ToastType.INFO -> Colors.info
        ToastType.SUCCESS -> Colors.online
        ToastType.ERROR -> Colors.error
    }
    val icon = when (data.type) {
        ToastType.INFO -> Res.drawable.about
        ToastType.SUCCESS -> Res.drawable.success
        ToastType.ERROR -> Res.drawable.error
    }
    CellTooltip(text = { clipped -> data.message.takeIf { clipped } }) {
        val clipped = LocalCellClipped.current
        Row(
            modifier = Modifier
                .widthIn(max = Dimens.statusToastMaxWidth)
                .height(Dimens.statusToastHeight)
                .clip(RoundedCornerShape(Dimens.statusToastCorner))
                .background(fill)
                .plainClickable { ToastManager.dismiss() }
                .padding(start = Dimens.statusToastPaddingH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.statusToastSpacing),
        ) {
            AppIcon(icon, size = Dimens.statusToastIcon, tint = Color.White)
            Text(
                text = data.message,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { clipped?.value = it.hasVisualOverflow },
                // gives way before the button does: a clipped message still has its tooltip, a
                // clipped Undo has nothing
                modifier = Modifier.weight(1f, fill = false),
            )
            val action = data.actionCallback
            if (data.actionLabel != null && action != null) {
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Text(
                    text = data.actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(vertical = Dimens.statusToastActionInset)
                        .padding(end = Dimens.statusToastActionInset)
                        .clip(RoundedCornerShape(Dimens.statusToastCorner))
                        // white over the tint in both states, so it reads as a button on any of the
                        // three fills without a colour of its own per fill
                        .background(Color.White.copy(alpha = if (hovered) 0.35f else 0.2f))
                        .hoverable(interaction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .plainClickable {
                            action()
                            ToastManager.dismiss()
                        }
                        .fillMaxHeight()
                        .wrapContentHeight()
                        .padding(horizontal = Dimens.statusToastActionPaddingH),
                )
            } else {
                // the trailing padding the button would otherwise have supplied
                Spacer(Modifier.width(Dimens.statusToastPaddingH - Dimens.statusToastSpacing))
            }
        }
    }
}

@Composable
private fun ToastCard(data: ToastData) {
    val background = when (data.type) {
        ToastType.INFO -> Colors.info
        ToastType.SUCCESS -> Colors.online
        ToastType.ERROR -> Colors.error
    }
    Card(
        modifier = Modifier.widthIn(max = 400.dp).padding(horizontal = Dimens.gapLarge),
        shape = RoundedCornerShape(Dimens.corner),
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(defaultElevation = Dimens.gap),
    ) {
        Row(
            Modifier.padding(horizontal = Dimens.gapLarge, vertical = Dimens.gap + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapLarge),
        ) {
            Text(
                text = data.message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (data.actionLabel != null && data.actionCallback != null) {
                TextButton(onClick = {
                    data.actionCallback.invoke()
                    ToastManager.dismiss()
                }) {
                    Text(text = data.actionLabel, color = Color.White)
                }
            }
        }
    }
}
