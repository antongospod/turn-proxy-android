package com.freeturn.app.ui.screens.clientsetup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.freeturn.app.R
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsControlLabel
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.components.SettingsRowDivider
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.ui.util.redact

/**
 * "Дополнительно": транспорт TURN (tcp/udp, ортогонален режиму туннеля), сегментированная
 * группа свитчей (капча + bond - bond только в TCP-режиме), альтернативный TURN-узел.
 */
@Composable
internal fun AdvancedSection(
    useUdp: Boolean,
    onUseUdp: (Boolean) -> Unit,
    manualCaptcha: Boolean,
    onManualCaptcha: (Boolean) -> Unit,
    showBond: Boolean,
    bond: Boolean,
    onBond: (Boolean) -> Unit,
    magicSwitch: Boolean,
    onMagicSwitch: (Boolean) -> Unit,
    magicTurn: String,
    onMagicTurn: (String) -> Unit,
    privacyMode: Boolean
) {
    SectionLabel(stringResource(R.string.client_section_advanced))
    // TURN-транспорт (-transport tcp|udp) ортогонален режиму туннеля.
    SettingsCard {
        SettingsFieldSlot {
            SettingsControlLabel(
                title = stringResource(R.string.transport_protocol),
                desc = stringResource(R.string.transport_protocol_desc)
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !useUdp,
                    onClick = { onUseUdp(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text(stringResource(R.string.tcp)) }
                SegmentedButton(
                    selected = useUdp,
                    onClick = { onUseUdp(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text(stringResource(R.string.udp)) }
            }
        }
    }

    // Капча + Bond - сегментированная группа свитчей.
    // Bond - client-only флаг (сервер детектит сам), только в TCP-режиме.
    val toggleCount = if (showBond) 2 else 1
    SettingsGroup {
        SettingsGroupItem(0, toggleCount) {
            SettingsSwitchRow(
                title = stringResource(R.string.manual_captcha),
                subtitle = stringResource(R.string.manual_captcha_desc),
                checked = manualCaptcha,
                onCheckedChange = onManualCaptcha
            )
        }
        if (showBond) {
            SettingsGroupItem(1, toggleCount) {
                SettingsSwitchRow(
                    title = stringResource(R.string.client_bond),
                    subtitle = stringResource(R.string.client_bond_desc),
                    checked = bond,
                    onCheckedChange = onBond
                )
            }
        }
    }

    // Альтернативный TURN-узел - свитч + адрес (раскрывается при включении).
    SettingsCard {
        SettingsSwitchRow(
            title = stringResource(R.string.magic_switch),
            subtitle = stringResource(R.string.magic_switch_desc),
            checked = magicSwitch,
            onCheckedChange = onMagicSwitch
        )
        if (magicSwitch) {
            SettingsRowDivider()
            SettingsFieldSlot {
                OutlinedTextField(
                    value = magicTurn.redact(privacyMode),
                    onValueChange = { if (!privacyMode) onMagicTurn(it) },
                    label = { Text(stringResource(R.string.magic_switch_address_label)) },
                    placeholder = { Text(stringResource(R.string.magic_switch_address_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = privacyMode,
                    supportingText = { Text(stringResource(R.string.magic_switch_address_support)) }
                )
            }
        }
    }
}
