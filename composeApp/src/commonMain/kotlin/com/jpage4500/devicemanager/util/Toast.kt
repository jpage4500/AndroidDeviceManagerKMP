package com.jpage4500.devicemanager.util

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
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
 */
object ToastManager {
    private const val DURATION_MS = 3000L

    private val _current = MutableStateFlow<ToastData?>(null)
    val current: StateFlow<ToastData?> = _current.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var dismissJob: Job? = null

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
 * where a toast is drawn — place once, at the app root.
 *
 * a Popup rather than a Dialog: this app has no dialogs yet, and a Popup costs no window on any of
 * the three platforms.
 */
@Composable
fun ToastHost() {
    val toast by ToastManager.current.collectAsState()
    val data = toast ?: return
    Popup(alignment = Alignment.BottomCenter, onDismissRequest = { ToastManager.dismiss() }) {
        Box(Modifier.padding(bottom = Dimens.gapLarge * 2)) { ToastCard(data) }
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
