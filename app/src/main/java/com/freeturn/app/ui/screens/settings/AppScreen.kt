@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.domain.UpdateState
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.components.BackupPasswordDialog
import com.freeturn.app.ui.components.BusyProgressIndicator
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsBackButton
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.components.SettingsRowIcon
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.ui.util.hapticClickable
import com.freeturn.app.viewmodel.settings.BackupEvent
import com.freeturn.app.viewmodel.settings.RestoreFailReason
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Приложение": интерфейсные тоггл-настройки, обновление и сброс. */
@Suppress("AssignedValueIsNeverRead") // showResetDialog пишется в лямбдах диалога
@Composable
fun AppScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()
    val dynamicTheme by settingsViewModel.dynamicTheme.collectAsStateWithLifecycle()
    val suppressUpdatePrompt by settingsViewModel.suppressUpdatePrompt.collectAsStateWithLifecycle()
    val suppressTgPrompt by settingsViewModel.suppressTgPrompt.collectAsStateWithLifecycle()
    val updateState by settingsViewModel.updateState.collectAsStateWithLifecycle()
    val appVersion = rememberAppVersion()
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var exportPassword by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // SAF: имя выбирает пользователь, пароль уже введён в диалоге выше.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) settingsViewModel.exportBackup(uri, exportPassword)
    }

    // Снекбар по результату экспорта/импорта.
    LaunchedEffect(Unit) {
        settingsViewModel.backupEvents.collect { event ->
            snackbarHostState.showSnackbar(backupEventMessage(context, event))
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_app)) },
                navigationIcon = { SettingsBackButton(onBack) },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                SectionLabel(stringResource(R.string.app_section_interface))
                SettingsGroup {
                    SettingsGroupItem(0, 2) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.privacy_mode_title),
                            subtitle = stringResource(R.string.privacy_mode_desc),
                            iconRes = R.drawable.visibility_off_24px,
                            checked = privacyMode,
                            onCheckedChange = { settingsViewModel.setPrivacyMode(it) }
                        )
                    }
                    SettingsGroupItem(1, 2) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.dynamic_theme_title),
                            subtitle = stringResource(R.string.dynamic_theme_desc),
                            iconRes = R.drawable.palette_24px,
                            checked = dynamicTheme,
                            onCheckedChange = { settingsViewModel.setDynamicTheme(it) }
                        )
                    }
                }

                SectionLabel(stringResource(R.string.app_section_prompts))
                SettingsGroup {
                    SettingsGroupItem(0, 2) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.suppress_update_prompt_title),
                            subtitle = stringResource(R.string.suppress_update_prompt_desc),
                            iconRes = R.drawable.cloud_download_24px,
                            checked = suppressUpdatePrompt,
                            onCheckedChange = { settingsViewModel.setSuppressUpdatePrompt(it) }
                        )
                    }
                    SettingsGroupItem(1, 2) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.suppress_tg_prompt_title),
                            subtitle = stringResource(R.string.suppress_tg_prompt_desc),
                            iconRes = R.drawable.group_off_24px,
                            checked = suppressTgPrompt,
                            onCheckedChange = { settingsViewModel.setSuppressTgPrompt(it) }
                        )
                    }
                }

                SectionLabel(stringResource(R.string.app_section_updates))
                UpdateCard(
                    state = updateState,
                    appVersion = appVersion,
                    onCheck = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        settingsViewModel.checkForUpdate()
                    },
                    onDownload = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        settingsViewModel.downloadUpdate()
                    },
                    onInstall = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        settingsViewModel.installUpdate()
                    }
                )

                SectionLabel(stringResource(R.string.app_section_backup))
                SettingsGroup {
                    SettingsGroupItem(0, 1) {
                        SettingsEntryRow(
                            iconRes = R.drawable.cloud_download_24px,
                            title = stringResource(R.string.backup_export_title),
                            subtitle = stringResource(R.string.backup_export_desc),
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                showExportDialog = true
                            }
                        )
                    }
                }

                SectionLabel(stringResource(R.string.app_section_reset))
                SettingsCard {
                    ResetRow(onClick = { showResetDialog = true })
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_all_settings_title)) },
            text = { Text(stringResource(R.string.reset_all_settings_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        settingsViewModel.resetAllSettings()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showExportDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_export_title),
            confirmLabel = stringResource(R.string.backup_export_action),
            requireConfirmation = true,
            onConfirm = { password ->
                exportPassword = password
                showExportDialog = false
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                exportLauncher.launch("freeturn-backup-$stamp.ftbackup")
            },
            onDismiss = { showExportDialog = false }
        )
    }
}

/** Текст снекбара по результату экспорта/восстановления (строки выбирает UI, не ViewModel). */
internal fun backupEventMessage(context: android.content.Context, event: BackupEvent): String =
    when (event) {
        BackupEvent.ExportSuccess -> context.getString(R.string.backup_export_ok)
        BackupEvent.ExportFailed -> context.getString(R.string.backup_export_fail)
        is BackupEvent.RestoreSuccess -> context.getString(R.string.backup_restore_ok, event.count)
        is BackupEvent.RestoreFailed -> when (event.reason) {
            RestoreFailReason.BAD_PASSWORD -> context.getString(R.string.backup_restore_bad_password)
            RestoreFailReason.BAD_FILE -> context.getString(R.string.backup_restore_bad_file)
            RestoreFailReason.IO -> context.getString(R.string.backup_restore_fail)
        }
    }

/**
 * Карточка обновления. Двухэтажная: шапка (иконка, заголовок, статус), под ней действие
 * во всю ширину - кнопка по состоянию либо прогресс загрузки. В одну строку не влезает на узких экранах.
 */
@Composable
private fun UpdateCard(
    state: UpdateState,
    appVersion: String,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val statusText = when (state) {
        is UpdateState.Idle -> stringResource(R.string.update_current_version, "v$appVersion")
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.Available -> stringResource(R.string.update_available, state.version)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.progress)
        is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_desc_short)
        is UpdateState.NoUpdate -> stringResource(R.string.update_no_update)
        is UpdateState.Error -> stringResource(R.string.update_error, state.message)
    }

    SettingsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsRowIcon(R.drawable.cloud_download_24px)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_title), style = MaterialTheme.typography.bodyLarge)
                    AnimatedContent(
                        targetState = statusText,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "update_status"
                    ) { text ->
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (state is UpdateState.Downloading) {
                BusyProgressIndicator(progress = { state.progress / 100f })
            } else {
                val action = when (state) {
                    is UpdateState.Available -> onDownload
                    is UpdateState.ReadyToInstall -> onInstall
                    else -> onCheck
                }
                FilledTonalButton(
                    onClick = action,
                    enabled = state !is UpdateState.Checking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state is UpdateState.Checking) {
                        LoadingIndicator(modifier = Modifier.size(22.dp))
                    } else {
                        Text(
                            when (state) {
                                is UpdateState.Available -> stringResource(R.string.update_download)
                                is UpdateState.ReadyToInstall -> stringResource(R.string.update_install)
                                else -> stringResource(R.string.update_check)
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** Строка сброса: error-тинт иконки и заголовка, без trailing-шеврона. */
@Composable
private fun ResetRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(HapticUtil.Pattern.CLICK, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        SettingsRowIcon(
            R.drawable.delete_24px,
            container = MaterialTheme.colorScheme.errorContainer,
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.reset_settings),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                stringResource(R.string.app_reset_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
