@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.freeturn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.util.hapticClickable
import kotlin.math.roundToInt

/**
 * Ползунок с подписью-значением и пояснением. onTick щёлкает на каждое целочисленное
 * деление (а не на каждый float-кадр) - состояние держится локально внутри строки.
 */
@Composable
fun SettingsSliderRow(
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onTick: () -> Unit,
    hint: String? = null
) {
    var lastInt by remember { mutableIntStateOf(value.roundToInt()) }
    SettingsControlLabel(title = valueLabel, desc = hint)
    Slider(
        value = value,
        onValueChange = {
            val newInt = it.roundToInt()
            if (newInt != lastInt) {
                onTick()
                lastInt = newInt
            }
            onValueChange(it)
        },
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconRes: Int? = null,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val subtitleColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { v ->
                    HapticUtil.perform(
                        context,
                        if (v) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                    )
                    onCheckedChange(v)
                }
            )
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        if (iconRes != null) SettingsRowIcon(iconRes, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subtitleColor)
            }
        }
        // null = display-only: клики и семантику несёт строка (один haptic, один фокус).
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun SettingsRowIcon(
    iconRes: Int,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    tint: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    val alpha = if (enabled) 1f else 0.38f
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(container.copy(alpha = alpha), MaterialShapes.Sunny.toShape()),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = tint.copy(alpha = alpha),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun SettingsEntryRow(
    iconRes: Int,
    title: String,
    subtitle: String? = null,
    trailingRes: Int? = R.drawable.chevron_right_24px,
    trailingTint: Color = Color.Unspecified,
    enabled: Boolean = true,
    iconContainer: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    titleColorOverride: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    val titleColor = if (titleColorOverride != Color.Unspecified) titleColorOverride
    else if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val subtitleColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(HapticUtil.Pattern.CLICK, enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        SettingsRowIcon(iconRes, enabled = enabled, container = iconContainer, tint = iconTint)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor
                )
            }
        }
        if (trailingRes != null) {
            val baseTrailing = if (trailingTint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant
            else trailingTint
            Icon(
                painterResource(trailingRes),
                contentDescription = null,
                tint = if (enabled) baseTrailing else baseTrailing.copy(alpha = 0.38f)
            )
        }
    }
}
