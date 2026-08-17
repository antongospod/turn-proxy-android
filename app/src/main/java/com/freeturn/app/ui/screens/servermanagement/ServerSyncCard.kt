@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.screens.servermanagement

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.freeturn.app.R
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsControlLabel
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsRowDivider
import com.freeturn.app.ui.util.redact

/**
 * Синхронные серверные настройки (apply-модель): профиль обфускации и obf-ключ
 * (черновик). Регенерация/копирование ключа - через колбэки; рестарт случается
 * по общей кнопке "Применить" на экране.
 */
@Composable
internal fun ServerSyncCard(
    obfProfile: String,
    onObfProfile: (String) -> Unit,
    keyDraft: String,
    onKeyDraft: (String) -> Unit,
    savedObfKey: String,
    privacyMode: Boolean,
    onCopyKey: () -> Unit,
    onRegenKey: () -> Unit
) {
    SectionLabel(stringResource(R.string.server_sync_section))
    SettingsCard {
        // Профиль обфускации.
        SettingsFieldSlot {
            SettingsControlLabel(stringResource(R.string.obf_profile_title))
            ObfProfileDropdown(obfProfile = obfProfile, onObfProfile = onObfProfile)
        }
        SettingsRowDivider()
        if (obfProfile != ObfProfile.NONE) {
            SettingsFieldSlot {
                OutlinedTextField(
                    value = if (privacyMode) savedObfKey.redact(true) else keyDraft,
                    onValueChange = { if (!privacyMode) onKeyDraft(it) },
                    label = { Text(stringResource(R.string.server_obf_key_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = privacyMode,
                    singleLine = true,
                    isError = keyDraft.isNotBlank() && !ObfProfile.isValidKey(keyDraft),
                    trailingIcon = {
                        if (savedObfKey.isNotBlank() && !privacyMode) {
                            IconButton(onClick = onCopyKey) {
                                Icon(
                                    painterResource(R.drawable.content_copy_24px),
                                    contentDescription = stringResource(R.string.copy)
                                )
                            }
                        }
                    },
                    supportingText = {
                        when {
                            keyDraft.isBlank() -> Text(stringResource(R.string.obf_key_empty_hint))
                            !ObfProfile.isValidKey(keyDraft) -> Text(
                                stringResource(R.string.obf_key_invalid_hint),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
                if (!privacyMode) {
                    TextButton(
                        onClick = onRegenKey,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.obf_key_regen))
                    }
                }
            }
        } else {
            // obfProfile == NONE - подсказка выбрать профиль.
            SettingsFieldSlot {
                Text(
                    stringResource(R.string.obf_select_profile_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun obfProfileLabel(value: String): String = when (value) {
    ObfProfile.NONE -> stringResource(R.string.obf_none)
    ObfProfile.RTPOPUS -> stringResource(R.string.obf_rtpopus)
    ObfProfile.RTPOPUS2 -> stringResource(R.string.obf_rtpopus2)
    ObfProfile.RTPOPUS3 -> stringResource(R.string.obf_rtpopus3)
    else -> value
}

@Composable
private fun ObfProfileDropdown(
    obfProfile: String,
    onObfProfile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val current = obfProfileLabel(obfProfile)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.obf_profile_title)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ObfProfile.VALUES.forEach { value ->
                DropdownMenuItem(
                    text = { Text(obfProfileLabel(value)) },
                    onClick = {
                        expanded = false
                        onObfProfile(value)
                    }
                )
            }
        }
    }
}
