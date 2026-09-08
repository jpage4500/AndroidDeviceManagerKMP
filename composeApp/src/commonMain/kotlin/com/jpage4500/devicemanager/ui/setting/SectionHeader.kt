package com.jpage4500.devicemanager.ui.setting

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.ui.theme.*

/**
 * a settings section header: grey text over a divider.
 *
 * deliberately small and tight — a settings screen carries several of these and none of them can
 * spend a full-size heading and its whitespace. ported from DashboardWeatherKMP.
 */
@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Colors.secondaryText,
        modifier = Modifier.padding(vertical = 2.dp),
    )
    HorizontalDivider(color = Colors.divider)
}
