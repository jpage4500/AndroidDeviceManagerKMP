package com.jpage4500.devicemanager.ui.dialog.common

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.text.input.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class InputType { TEXT, NUMBER, DECIMAL }

/**
 * one line of text, asked for in a dialog.
 *
 * [inputType] does nothing on a desktop and everything on a phone, where the soft keyboard that
 * comes up decides whether a port number is three taps or fifteen. ported from DashboardWeatherKMP,
 * over this app's own [AppTextField] rather than Material's outlined one.
 */
@Composable
fun TextInputDialog(
    title: String? = null,
    titleRes: StringResource? = null,
    desc: String? = null,
    descRes: StringResource? = null,
    initialValue: String? = null,
    placeholder: String = "",
    inputType: InputType = InputType.TEXT,
    validator: ((String) -> Boolean)? = null,
    okText: String = stringResource(Res.string.ok),
    cancelText: String? = stringResource(Res.string.cancel),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialValue.orEmpty()) }
    val focus = remember { FocusRequester() }

    // with no validator of its own, a numeric field is valid when it parses and a text one when
    // it isn't empty
    val isValid = validator?.invoke(text) ?: when (inputType) {
        InputType.NUMBER -> text.toIntOrNull() != null
        InputType.DECIMAL -> text.toDoubleOrNull() != null
        InputType.TEXT -> text.isNotEmpty()
    }

    BaseDialog(
        title = title,
        titleRes = titleRes,
        icon = Res.drawable.edit,
        okText = okText,
        cancelText = cancelText,
        okEnabled = isValid,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(text) },
    ) {
        val descText = desc ?: descRes?.let { stringResource(it) }
        if (descText != null) {
            DialogMessage(descText)
            Spacer(modifier = Modifier.height(Dimens.gap))
        }

        AppTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.settingPadding),
            hint = placeholder,
            focusRequester = focus,
            keyboardType = when (inputType) {
                InputType.TEXT -> KeyboardType.Text
                InputType.NUMBER -> KeyboardType.Number
                InputType.DECIMAL -> KeyboardType.Decimal
            },
            onSubmit = { if (isValid) { onConfirm(text); onDismiss() } },
        )
        FocusOnShow(focus)
    }
}
