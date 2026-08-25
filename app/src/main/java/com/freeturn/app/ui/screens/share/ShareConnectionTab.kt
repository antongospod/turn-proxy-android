@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.freeturn.app.ui.screens.share

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.data.config.ClientId
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.theme.LocalReducedMotion
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsRowDivider
import com.freeturn.app.ui.components.SettingsRowIcon
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.viewmodel.share.ShareUiState
import com.freeturn.app.viewmodel.share.ShareViewModel
import com.freeturn.app.ui.theme.Spacing

/**
 * Суб-вкладка "Соединение": имя нового пользователя + сервер. Сам запуск выдачи -
 * на FAB экрана. Протокол (WireGuard/прокси) определяет сервер (share-info),
 * не локальный режим владельца.
 */
@Composable
fun ShareConnectionTab(
    state: ShareUiState,
    onSelectServer: (String) -> Unit,
    onUserNameChange: (String) -> Unit,
    onClientIdChange: (String) -> Unit,
    onSetMode: (Boolean) -> Unit,
    onSetShareVkLink: (Boolean) -> Unit,
    onVkLinkChange: (String) -> Unit,
    onRetryInfo: () -> Unit
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Text(
            stringResource(R.string.share_connection_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = state.userName,
            onValueChange = onUserNameChange,
            label = { Text(stringResource(R.string.share_user_name_label)) },
            singleLine = true,
            enabled = !state.creating,
            supportingText = {
                Text(
                    "${state.userName.length}/${ShareViewModel.MAX_USER_NAME_LEN}",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        ServerSelector(
            servers = state.servers,
            selected = state.selectedServer,
            onSelect = onSelectServer
        )

        // Ручной сервер без SSH: cid не завести автоматически - владелец вводит существующий.
        if (state.localOnly) {
            val cidInvalid = state.manualClientId.isNotBlank() && !ClientId.isValid(state.manualClientId)
            OutlinedTextField(
                value = state.manualClientId,
                onValueChange = onClientIdChange,
                label = { Text(stringResource(R.string.share_client_id_label)) },
                singleLine = true,
                isError = cidInvalid,
                enabled = !state.creating,
                supportingText = {
                    Text(
                        stringResource(
                            if (cidInvalid) R.string.share_client_id_invalid
                            else R.string.share_client_id_hint
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // WG-сервер умеет оба типа доступа -> выбор. Прокси-only сервер - просто статус.
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SectionLabel(stringResource(R.string.share_access_type))
            if (state.canChooseMode && state.shareInfo != null &&
                !state.infoLoading && state.infoError == null
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.useWg,
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                            onSetMode(false)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text(stringResource(R.string.share_protocol_wg)) }
                    SegmentedButton(
                        selected = !state.useWg,
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                            onSetMode(true)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text(stringResource(R.string.share_protocol_proxy)) }
                }
            }
            ShareProtocolCard(state = state, onRetryInfo = onRetryInfo)
        }

        // Ссылка на звонок уходит вместе с доступом - только по явному согласию владельца.
        if (state.ownerVkLink.isNotBlank()) {
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.share_include_vk_link),
                    subtitle = stringResource(R.string.share_include_vk_link_desc),
                    iconRes = R.drawable.link_24px,
                    checked = state.shareVkLink,
                    onCheckedChange = onSetShareVkLink,
                    enabled = !state.creating
                )
                if (state.shareVkLink) {
                    SettingsRowDivider()
                    SettingsFieldSlot {
                        OutlinedTextField(
                            value = state.vkLinkToShare,
                            onValueChange = onVkLinkChange,
                            label = { Text(stringResource(R.string.call_link_label)) },
                            placeholder = { Text(stringResource(R.string.call_link_placeholder)) },
                            singleLine = true,
                            enabled = !state.creating,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (state.missingAddress) {
            Text(
                stringResource(R.string.share_no_address),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        state.createError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Статус выбранного сервера: протокол шаринга либо загрузка/ошибка share-info. */
@Composable
private fun ShareProtocolCard(state: ShareUiState, onRetryInfo: () -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    SettingsCard {
        when {
            state.infoLoading -> Row(
                modifier = Modifier.padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                // Загрузка стартует уже после enter-перехода (см. ShareScreen),
                // поэтому индикатор не дёргает slide-анимацию.
                LoadingIndicator(modifier = Modifier.size(28.dp))
                Text(
                    stringResource(R.string.share_info_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.infoError != null -> SettingsFieldSlot {
                Text(
                    stringResource(R.string.share_info_error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    state.infoError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onRetryInfo) {
                    Text(stringResource(R.string.share_info_retry))
                }
            }

            else -> state.shareInfo?.let {
                Crossfade(
                    targetState = state.useWg,
                    animationSpec = tween(if (reducedMotion) 0 else 250),
                    label = "protocol_mode"
                ) { wg ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
                        ) {
                            SettingsRowIcon(
                                iconRes = if (wg) R.drawable.vpn_key_24px else R.drawable.public_24px,
                                container = if (wg) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.tertiaryContainer,
                                tint = if (wg) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                stringResource(
                                    if (wg) R.string.share_protocol_wg
                                    else R.string.share_protocol_proxy
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            stringResource(
                                if (wg) R.string.share_protocol_wg_desc
                                else R.string.share_protocol_proxy_desc
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

