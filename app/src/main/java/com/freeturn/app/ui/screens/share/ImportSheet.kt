@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.ui.components.EmptyState
import com.freeturn.app.ui.components.InlineErrorCard
import com.freeturn.app.ui.components.ProtocolPills
import com.freeturn.app.ui.util.redact
import com.freeturn.app.viewmodel.share.ImportViewModel
import org.koin.androidx.compose.koinViewModel
import com.freeturn.app.ui.theme.Spacing

/**
 * Sheet импорта по freeturn://-ссылке. Живёт на уровне AppNavigation поверх
 * NavHost (как CaptchaWebViewDialog) - всплывает из любого места приложения.
 */
@Composable
fun ImportSheet(
    onImported: () -> Unit,
    viewModel: ImportViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()

    if (state.parseError) {
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.import_error_title)) },
            text = { Text(stringResource(R.string.import_error_desc)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismiss) {
                    Text(stringResource(R.string.import_error_ok))
                }
            }
        )
        return
    }

    val link = state.link ?: return
    val fallbackName = stringResource(R.string.import_default_name)

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    ModalBottomSheet(
        onDismissRequest = viewModel::dismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        if (state.saved) {
            EmptyState(
                iconRes = R.drawable.check_circle_24px,
                title = stringResource(R.string.import_success_title),
                desc = stringResource(R.string.import_success_desc),
                actionLabel = stringResource(R.string.import_success_go_home),
                onAction = {
                    viewModel.dismiss()
                    onImported()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(bottom = Spacing.lg)
            )
            return@ModalBottomSheet
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xxl)
                .navigationBarsPadding()
                .padding(bottom = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                stringResource(R.string.import_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )

            ProtocolPills(
                wg = link.wgConf.isNotBlank(),
                obfOn = link.obfProfile.isNotEmpty() && link.obfProfile != ObfProfile.NONE
            )

            AddressChip(link.peer.redact(privacyMode))

            OutlinedTextField(
                value = state.serverName,
                onValueChange = viewModel::setServerName,
                label = { Text(stringResource(R.string.server_name_label)) },
                singleLine = true,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.vkLink,
                onValueChange = viewModel::setVkLink,
                label = { Text(stringResource(R.string.import_vk_link_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (link.vkLink.isNotBlank()) R.string.import_vk_link_from_owner
                            else R.string.import_vk_link_helper
                        )
                    )
                },
                singleLine = true,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth()
            )

            if (state.duplicateConf) {
                ImportWarning(stringResource(R.string.import_duplicate_conf))
            } else if (state.duplicateAddress) {
                ImportWarning(stringResource(R.string.import_duplicate_address))
            }

            if (state.saveError) {
                InlineErrorCard(stringResource(R.string.import_save_error))
            }

            Button(
                onClick = { viewModel.confirm(fallbackName) },
                enabled = state.canConfirm,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.saving) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(R.string.import_confirm))
                }
            }
        }
    }
}

/** Адрес сервера из ссылки - пилюля с иконкой хоста и моно-текстом (в стиле ProtocolPills). */
@Composable
private fun AddressChip(peer: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                painterResource(R.drawable.host_24px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Text(
                peer,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Тональный баннер-предупреждение (tertiaryContainer) - дубликат конфига/адреса. */
@Composable
private fun ImportWarning(text: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.info_24px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}
