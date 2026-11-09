package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

// how many devices the preview reads, and how many suggestions it lists — both fill the panel
private const val PREVIEW_DEVICES = 5
private const val MAX_SUGGESTIONS = 5

// a suggestion list starts at this many characters; one letter matches half of `getprop`
private const val MIN_QUERY = 2

// the preview waits for typing to pause, so a package name typed out doesn't run a dumpsys per letter
private const val PREVIEW_DELAY_MS = 400L

/**
 * add or edit a [CustomColumn]: an app's version or a system property, as a column of the device
 * table.
 *
 * the Swing app's dialog was a text box and a page explaining its `LABEL:TYPE:VALUE` format. This is
 * a form instead, and the explanation is the **preview**: what the column would say on a few of the
 * devices actually connected, read as it's typed. The panel under the field lists what the first of
 * them has installed — or every property it knows — while there's something to match, and the
 * preview otherwise.
 *
 * the header fills itself in from what's being read until someone types their own.
 */
@Composable
fun CustomColumnDialog(
    original: CustomColumn?,
    onDismiss: () -> Unit,
    onSave: (CustomColumn) -> Unit,
) {
    var type by remember { mutableStateOf(original?.type ?: CustomColumnType.VERSION) }
    val initialKey = original?.key.orEmpty()
    var key by remember { mutableStateOf(TextFieldValue(initialKey, TextRange(initialKey.length))) }
    var label by remember { mutableStateOf(original?.label.orEmpty()) }
    var labelTyped by remember { mutableStateOf(original != null) }
    // the suggestions show from the first keystroke until one is picked — not on opening a column to
    // edit it, when the field already says what it should
    var suggesting by remember { mutableStateOf(false) }
    val keyFocus = remember { FocusRequester() }

    val keyText = key.text.trim()
    val validKey = CustomColumn.isValidKey(type, keyText)
    val shownLabel = if (labelTyped) label else CustomColumn.suggestLabel(type, keyText)
    val canSave = validKey && shownLabel.isNotBlank()

    // the selection's ready devices, or the first few ready ones — read once, so the preview doesn't
    // reshuffle under the person reading it
    val samples = remember {
        val selected = DeviceManager.selectedDevices().filter { it.isReady }
        selected.ifEmpty { DeviceManager.devices.value.filter { it.isReady } }.take(PREVIEW_DEVICES)
    }
    val first = samples.firstOrNull()

    val suggestions = remember { mutableStateMapOf<CustomColumnType, List<String>>() }
    LaunchedEffect(type) {
        if (first == null || type in suggestions) return@LaunchedEffect
        suggestions[type] = when (type) {
            CustomColumnType.VERSION -> DeviceManager.packages(first.id)
            CustomColumnType.PROPERTY -> DeviceManager.propertyKeys(first.id)
        }
    }
    val known = suggestions[type]
    val matches = remember(known, keyText) {
        if (keyText.length < MIN_QUERY) emptyList()
        else known.orEmpty()
            .filter { it.contains(keyText, ignoreCase = true) && it != keyText }
            // what starts with the text first, then the shortest — the app rather than its plugins
            .sortedWith(compareBy<String>({ !it.startsWith(keyText, ignoreCase = true) }, { it.length }))
            .take(MAX_SUGGESTIONS)
    }

    // device id → its answer; a device not in here yet is still being read
    var preview by remember { mutableStateOf<Map<String, Result<String>>>(emptyMap()) }
    LaunchedEffect(type, keyText) {
        preview = emptyMap()
        if (!validKey) return@LaunchedEffect
        delay(PREVIEW_DELAY_MS)
        samples.map { device ->
            async { device.id to DeviceManager.readColumn(device.id, type, keyText) }
        }.forEach { pending ->
            val (id, result) = pending.await()
            preview = preview + (id to result)
        }
    }

    fun save() {
        if (!canSave) return
        onSave(
            CustomColumn(
                id = original?.id ?: CustomColumn.newId(),
                label = shownLabel.trim(),
                type = type,
                key = keyText,
            )
        )
        onDismiss()
    }

    AppDialog(
        title = stringResource(if (original == null) Res.string.custom_column_add_title else Res.string.custom_column_edit_title),
        onDismiss = onDismiss,
        modifier = Modifier.width(Dimens.customColumnDialogWidth),
        confirmLabel = stringResource(if (original == null) Res.string.custom_column_add else Res.string.save),
        confirmEnabled = canSave,
        onConfirm = ::save,
    ) {
        FocusOnShow(keyFocus)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.custom_column_show),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
                modifier = Modifier.padding(end = Dimens.gap),
            )
            SegmentedToggle(
                options = CustomColumnType.entries,
                selected = type,
                label = {
                    stringResource(
                        when (it) {
                            CustomColumnType.VERSION -> Res.string.custom_type_version
                            CustomColumnType.PROPERTY -> Res.string.custom_type_property
                        }
                    )
                },
                onSelect = {
                    if (it != type) {
                        type = it
                        // a package name means nothing as a property, and the other way round
                        key = TextFieldValue()
                        suggesting = false
                    }
                },
            )
        }
        Spacer(Modifier.height(Dimens.gapLarge))

        val isVersion = type == CustomColumnType.VERSION
        FieldLabel(stringResource(if (isVersion) Res.string.custom_column_package else Res.string.custom_column_property))
        AppTextField(
            value = key,
            onValueChange = {
                if (it.text != key.text) suggesting = true
                key = it
            },
            modifier = Modifier.fillMaxWidth(),
            hint = stringResource(if (isVersion) Res.string.custom_column_package_hint else Res.string.custom_column_property_hint),
            focusRequester = keyFocus,
            isError = keyText.isNotEmpty() && !validKey,
            onSubmit = ::save,
        )
        Spacer(Modifier.height(Dimens.gap))

        Column(Modifier.fillMaxWidth().height(Dimens.customColumnPanelHeight)) {
            when {
                suggesting && matches.isNotEmpty() && first != null -> {
                    FieldLabel(stringResource(Res.string.custom_column_suggestions, first.displayName))
                    matches.forEach { match ->
                        SuggestionRow(match, keyText) {
                            key = TextFieldValue(match, TextRange(match.length))
                            suggesting = false
                            runCatching { keyFocus.requestFocus() }
                        }
                    }
                }

                keyText.isNotEmpty() && !validKey -> PanelMessage(
                    stringResource(if (isVersion) Res.string.custom_column_invalid_package else Res.string.custom_column_invalid_property),
                    Colors.error,
                )

                samples.isEmpty() -> PanelMessage(stringResource(Res.string.custom_column_preview_none), Colors.secondaryText)

                else -> {
                    FieldLabel(stringResource(Res.string.custom_column_preview))
                    samples.forEach { device ->
                        PreviewRow(device, if (validKey) preview[device.id] else null, reading = validKey, isVersion = isVersion)
                    }
                }
            }
        }
        Spacer(Modifier.height(Dimens.gap))

        FieldLabel(stringResource(Res.string.custom_column_header))
        AppTextField(
            value = shownLabel,
            onValueChange = {
                label = it
                // emptied, it goes back to following the field above rather than staying blank
                labelTyped = it.isNotEmpty()
            },
            modifier = Modifier.fillMaxWidth(),
            onSubmit = ::save,
        )
    }
}

/** one package or property the device has, with the typed part in bold. */
@Composable
private fun SuggestionRow(text: String, query: String, onPick: () -> Unit) {
    val start = text.indexOf(query, ignoreCase = true)
    val styled = buildAnnotatedString {
        append(text)
        if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + query.length)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(Dimens.customColumnRowHeight)
            .clip(RoundedCornerShape(Dimens.corner))
            .plainClickable(onClick = onPick)
            .padding(horizontal = Dimens.gap),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            styled,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * a device and what the column would say on it. [result] is null while it's being read — or, with
 * [reading] off, when there's nothing to read yet.
 */
@Composable
private fun PreviewRow(device: Device, result: Result<String>?, reading: Boolean, isVersion: Boolean) {
    val value = result?.getOrNull()
    val (text, color) = when {
        result == null -> (if (reading) stringResource(Res.string.custom_value_reading) else "") to Colors.secondaryText
        value == null -> stringResource(Res.string.custom_value_no_answer) to Colors.error
        value.isEmpty() -> stringResource(if (isVersion) Res.string.custom_value_not_installed else Res.string.custom_value_not_set) to Colors.secondaryText
        else -> value to Colors.primaryText
    }
    Row(
        Modifier.fillMaxWidth().height(Dimens.customColumnRowHeight).padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            device.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = Dimens.gap),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** a line where the preview would be — why there isn't one. */
@Composable
private fun PanelMessage(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(top = Dimens.gap),
    )
}
