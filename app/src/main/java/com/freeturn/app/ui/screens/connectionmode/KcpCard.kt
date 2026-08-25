@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.screens.connectionmode

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.freeturn.app.R
import com.freeturn.app.data.config.KcpPreset
import com.freeturn.app.data.config.KcpProfile
import com.freeturn.app.ui.components.LabeledTextField
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsRowDivider
import com.freeturn.app.ui.components.SettingsSwitchRow

/**
 * ARQ tcp-режима: пресет и, для "Свои", восемь параметров ядра.
 * Значения уходят и клиенту, и серверу - настраиваются один раз на пару.
 */
@Composable
internal fun KcpCard(profile: KcpProfile, onProfile: (KcpProfile) -> Unit) {
    // Флаг, а не производный пресет: ручные значения, совпавшие с дефолтом, иначе
    // схлопывали бы поля обратно прямо во время ввода.
    var pinnedCustom by remember { mutableStateOf(KcpPreset.of(profile) == KcpPreset.CUSTOM) }
    val preset = if (pinnedCustom) KcpPreset.CUSTOM else KcpPreset.of(profile)

    SectionLabel(stringResource(R.string.kcp_section))
    SettingsCard {
        SettingsFieldSlot {
            PresetDropdown(preset) { picked ->
                pinnedCustom = picked == KcpPreset.CUSTOM
                when (picked) {
                    KcpPreset.STANDARD -> onProfile(KcpProfile.DEFAULT)
                    KcpPreset.MOBILE -> onProfile(KcpProfile.MOBILE)
                }
            }
            Text(
                stringResource(presetDescRes(preset)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (preset == KcpPreset.CUSTOM) {
            SettingsRowDivider()
            SettingsFieldSlot {
                NumberField(profile.interval, R.string.kcp_interval_label) { onProfile(profile.copy(interval = it)) }
                NumberField(
                    profile.resend,
                    R.string.kcp_resend_label,
                    errorRes = R.string.kcp_value_nonneg,
                    valid = profile.resend >= 0
                ) {
                    onProfile(profile.copy(resend = it))
                }
                NumberField(profile.sndWnd, R.string.kcp_sndwnd_label) { onProfile(profile.copy(sndWnd = it)) }
                NumberField(profile.rcvWnd, R.string.kcp_rcvwnd_label) { onProfile(profile.copy(rcvWnd = it)) }
                NumberField(
                    profile.mtu,
                    R.string.kcp_mtu_label,
                    supportingRes = R.string.kcp_mtu_support,
                    errorRes = R.string.kcp_mtu_invalid,
                    valid = profile.mtu in KcpProfile.MTU_MIN..KcpProfile.MTU_MAX
                ) { onProfile(profile.copy(mtu = it)) }
            }
            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.kcp_nodelay_title),
                subtitle = stringResource(R.string.kcp_nodelay_desc),
                checked = profile.noDelay == 1,
                onCheckedChange = { onProfile(profile.copy(noDelay = if (it) 1 else 0)) }
            )
            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.kcp_nc_title),
                subtitle = stringResource(R.string.kcp_nc_desc),
                checked = profile.nc == 1,
                onCheckedChange = { onProfile(profile.copy(nc = if (it) 1 else 0)) }
            )
            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.kcp_acknodelay_title),
                subtitle = stringResource(R.string.kcp_acknodelay_desc),
                checked = profile.ackNoDelay,
                onCheckedChange = { onProfile(profile.copy(ackNoDelay = it)) }
            )
        }
    }
}

@Composable
private fun PresetDropdown(preset: String, onPreset: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = stringResource(presetLabelRes(preset)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.kcp_preset_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            KcpPreset.VALUES.forEach { value ->
                DropdownMenuItem(
                    text = { Text(stringResource(presetLabelRes(value))) },
                    onClick = {
                        expanded = false
                        onPreset(value)
                    }
                )
            }
        }
    }
}

@StringRes
private fun presetLabelRes(preset: String): Int = when (preset) {
    KcpPreset.STANDARD -> R.string.kcp_preset_standard
    KcpPreset.MOBILE -> R.string.kcp_preset_mobile
    else -> R.string.kcp_preset_custom
}

@StringRes
private fun presetDescRes(preset: String): Int = when (preset) {
    KcpPreset.STANDARD -> R.string.kcp_preset_standard_desc
    KcpPreset.MOBILE -> R.string.kcp_preset_mobile_desc
    else -> R.string.kcp_preset_custom_desc
}

/** Пустое поле не пишется в профиль: значение остаётся прежним до валидного ввода. */
@Composable
private fun NumberField(
    value: Int,
    @StringRes labelRes: Int,
    @StringRes supportingRes: Int? = null,
    @StringRes errorRes: Int = R.string.kcp_value_positive,
    valid: Boolean = value > 0,
    onValue: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    LabeledTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let(onValue)
        },
        labelRes = labelRes,
        supportingRes = supportingRes,
        errorRes = errorRes,
        isError = !valid || text.isEmpty(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
