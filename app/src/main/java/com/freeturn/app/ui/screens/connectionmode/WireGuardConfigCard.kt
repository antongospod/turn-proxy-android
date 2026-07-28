package com.freeturn.app.ui.screens.connectionmode

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsRowDivider
import com.freeturn.app.ui.theme.extendedColorScheme
import com.freeturn.app.ui.util.redact

/** Конфигурация WG-туннеля: загрузка .conf из файла / ручная вставка + имя туннеля. */
@Composable
internal fun WireGuardConfigCard(
    wgConfig: String,
    onWgConfig: (String) -> Unit,
    wgName: String,
    onWgName: (String) -> Unit,
    privacyMode: Boolean,
    onLoadFile: () -> Unit
) {
    val configLoaded = wgConfig.isNotBlank()
    SectionLabel(stringResource(R.string.connection_config_section))
    SettingsCard {
        SettingsEntryRow(
            iconRes = R.drawable.cloud_download_24px,
            title = stringResource(R.string.load_wg_conf),
            trailingRes = if (configLoaded) R.drawable.check_circle_24px else null,
            trailingTint = MaterialTheme.extendedColorScheme.success,
            enabled = !privacyMode,
            onClick = onLoadFile
        )
        SettingsRowDivider()
        SettingsFieldSlot {
            // Та же ручная вставка .conf, что на шаге конфига мастера.
            // Конфиг содержит приватный ключ - под privacyMode маскируем.
            OutlinedTextField(
                value = wgConfig.redact(privacyMode),
                onValueChange = { if (!privacyMode) onWgConfig(it) },
                label = { Text(stringResource(R.string.setup_wg_conf_label)) },
                placeholder = { Text(stringResource(R.string.setup_wg_conf_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                maxLines = 10,
                readOnly = privacyMode
            )
            OutlinedTextField(
                value = wgName.redact(privacyMode),
                onValueChange = { if (!privacyMode) onWgName(it) },
                label = { Text(stringResource(R.string.wireguard_tunnel_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = privacyMode
            )
        }
    }
}
