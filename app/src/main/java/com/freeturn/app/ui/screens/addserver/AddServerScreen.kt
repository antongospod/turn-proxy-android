@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.addserver

import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.data.share.FreeturnLink
import com.freeturn.app.domain.share.LinkImportBus
import com.freeturn.app.ui.components.BackupPasswordDialog
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.screens.settings.backupEventMessage
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Экран "Добавить сервер" (вкладка "+"). Self-hosted уводит в мастер установки
 * (SSH -> опросник -> установка) - сервер создаётся только после его успешного
 * завершения. Ручная настройка создаёт пустой сервер по имени - дальше пользователь
 * настраивает его сам в хабе. Импорт - по freeturn://-ссылке (вставка из буфера,
 * QR-сканер); из файла ещё не реализован.
 */
@Composable
fun AddServerScreen(
    settingsViewModel: SettingsViewModel,
    onSelfHosted: () -> Unit,
    onManualCreate: (String) -> Unit,
    onScanQr: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showManualDialog by rememberSaveable { mutableStateOf(false) }
    var showRestoreDialog by rememberSaveable { mutableStateOf(false) }
    var restoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val linkBus = koinInject<LinkImportBus>()
    val snackbarHostState = remember { SnackbarHostState() }
    val notLinkMessage = stringResource(R.string.add_paste_not_link)
    val scope = rememberCoroutineScope()

    // Восстановление: выбор файла -> диалог пароля -> расшифровка и замена профиля в VM.
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            restoreUri = uri
            showRestoreDialog = true
        }
    }

    LaunchedEffect(Unit) {
        settingsViewModel.backupEvents.collect { event ->
            snackbarHostState.showSnackbar(backupEventMessage(context, event))
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.add_server_title)) },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Экран всегда внутри NavigationSuite - нижний бар сам держит навбар-инсет.
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
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            ) {
                PasteLinkField(
                    onPaste = {
                        val clip = context.getSystemService(ClipboardManager::class.java)
                            ?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (FreeturnLink.looksLikeLink(clip)) {
                            linkBus.offer(clip)
                        } else {
                            scope.launch { snackbarHostState.showSnackbar(notLinkMessage) }
                        }
                    }
                )

                Spacer(Modifier.height(24.dp))

                SectionLabel(stringResource(R.string.add_methods_section))
                Spacer(Modifier.height(8.dp))
                SettingsGroup {
                    SettingsGroupItem(0, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.host_24px,
                            title = stringResource(R.string.add_self_hosted_title),
                            subtitle = stringResource(R.string.add_self_hosted_desc),
                            onClick = onSelfHosted
                        )
                    }
                    SettingsGroupItem(1, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.tune_24px,
                            title = stringResource(R.string.add_manual_title),
                            subtitle = stringResource(R.string.add_manual_desc),
                            onClick = { showManualDialog = true }
                        )
                    }
                    SettingsGroupItem(2, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.qr_code_scanner_24px,
                            title = stringResource(R.string.add_from_qr_title),
                            subtitle = stringResource(R.string.add_from_qr_desc),
                            onClick = onScanQr
                        )
                    }
                    SettingsGroupItem(3, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.description_24px,
                            title = stringResource(R.string.add_restore_title),
                            subtitle = stringResource(R.string.add_restore_desc),
                            onClick = { restoreLauncher.launch(arrayOf("*/*")) }
                        )
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualNameDialog(
            onCreate = { name ->
                showManualDialog = false
                onManualCreate(name)
            },
            onDismiss = { showManualDialog = false }
        )
    }

    if (showRestoreDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_restore_title),
            confirmLabel = stringResource(R.string.backup_restore_action),
            requireConfirmation = false,
            onConfirm = { password ->
                showRestoreDialog = false
                restoreUri?.let { settingsViewModel.restoreBackup(it, password) }
            },
            onDismiss = { showRestoreDialog = false },
            warning = stringResource(R.string.backup_restore_warning)
        )
    }
}
