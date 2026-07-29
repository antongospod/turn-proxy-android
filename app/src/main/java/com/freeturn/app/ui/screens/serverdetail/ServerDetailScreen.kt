@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.serverdetail

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.domain.SshConnectionState
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsBackButton
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.ui.navigation.NAV_SLIDE_MS
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.ui.util.redact
import com.freeturn.app.viewmodel.server.ServerHubState
import com.freeturn.app.viewmodel.server.ServerViewModel
import com.freeturn.app.viewmodel.server.serverSettingsAvailable
import com.freeturn.app.viewmodel.settings.SettingsViewModel

@Composable
fun ServerDetailScreen(
    serverId: String,
    settingsViewModel: SettingsViewModel,
    serverViewModel: ServerViewModel,
    onBack: () -> Unit,
    onOpenConnection: (String) -> Unit,
    onOpenConnectionMode: (String) -> Unit,
    onOpenServerSettings: (String) -> Unit,
    onOpenNerdInfo: (String) -> Unit,
    onCloned: (String) -> Unit
) {
    val context = LocalContext.current
    val snapshot by settingsViewModel.serversSnapshot.collectAsStateWithLifecycle()
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()
    val sshState by serverViewModel.sshState.collectAsStateWithLifecycle()
    val sshConfig by serverViewModel.sshConfig.collectAsStateWithLifecycle()
    val coreStatus by serverViewModel.hubState.collectAsStateWithLifecycle()
    val nerdMode by settingsViewModel.nerdMode.collectAsStateWithLifecycle()
    val server = snapshot.list.firstOrNull { it.id == serverId }
    val isActive = snapshot.activeId == serverId

    // Сервер удалён (например, из этого же экрана) - выходим назад.
    if (snapshot.loaded && server == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // До загрузки снимка показывается skeleton, а не ложный Offline.
    val status: ServerHubState = when {
        !snapshot.loaded -> ServerHubState.Connecting
        !isActive -> ServerHubState.Offline
        server?.ssh?.ip.isNullOrBlank() -> ServerHubState.NotPaired
        else -> coreStatus
    }
    val online = status as? ServerHubState.Online
    val connected = online != null

    val disconnected by rememberUpdatedState(sshState is SshConnectionState.Disconnected)
    LaunchedEffect(isActive, sshConfig.ip) {
        if (isActive && sshConfig.ip.isNotBlank()) {
            kotlinx.coroutines.delay(NAV_SLIDE_MS + 50L)
            if (disconnected) serverViewModel.reconnectSsh()
        }
    }

    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showCleanup by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val headerSubtitle = server?.let { p ->
        p.client.serverAddress.takeIf { it.isNotBlank() }?.redact(privacyMode)
            ?: p.ssh.ip.takeIf { it.isNotBlank() }?.let { "SSH ${it.redact(privacyMode)}" }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(server?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                subtitle = headerSubtitle?.let { sub ->
                    { Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                navigationIcon = { SettingsBackButton(onBack) },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            if (server != null) {
                ServerHubActionsFab(
                    online = online,
                    onInstall = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        serverViewModel.installServer()
                    },
                    onStart = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        serverViewModel.startServer()
                    },
                    onStop = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        serverViewModel.stopServer()
                    },
                    onRename = { showRename = true },
                    onClone = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        settingsViewModel.cloneServer(serverId, onCloned)
                    }
                )
            }
        },
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
                if (server != null) {
                    val sshConfigured = server.ssh.ip.isNotBlank()
                    ServerStatusCard(
                        status = status,
                        syncOn = sshConfigured && server.client.syncServerSwitches,
                        onActivate = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            settingsViewModel.applyServer(serverId)
                        },
                        onRetry = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            serverViewModel.reconnectSsh()
                        }
                    )

                    SettingsCard {
                        SettingsSwitchRow(
                            title = stringResource(R.string.sync_server_switches),
                            iconRes = R.drawable.cached_24px,
                            checked = sshConfigured && server.client.syncServerSwitches,
                            enabled = sshConfigured,
                            onCheckedChange = { v ->
                                if (isActive) settingsViewModel.setSyncServerSwitches(v)
                                else settingsViewModel.updateServerClient(serverId) { it.copy(syncServerSwitches = v) }
                            }
                        )
                    }
                }

                SectionLabel(stringResource(R.string.provider_vk_calls))
                // "Настройки сервера": при sync ON правки пушатся на сервер -> нужен живой
                // SSH; при sync OFF клиент-локальны -> вход доступен и оффлайн. Правило
                // общее с ServerManagementScreen - serverSettingsAvailable.
                val syncOn = server?.client?.syncServerSwitches == true
                // Connecting/Working - transient: SSH ещё поднимается. Пункт не должен мигать,
                // держим его видимым на время подключения (сам экран при входе покажет
                // дебаунс-карту потери связи, а не пустоту). Прячем только в терминальных
                // disconnected-состояниях (Failed/NotPaired) при sync ON.
                val connecting = status is ServerHubState.Connecting || status is ServerHubState.Working
                // Без isActive-гейта: неактивный сервер форсит status=Offline -> connected/connecting=false,
                // поэтому serverSettingsAvailable даёт true только при sync OFF (клиент-локальные настройки),
                // а при sync ON остаётся скрытым (пушить на сервер нечем без живого SSH активного сервера).
                val showServerSettings = serverSettingsAvailable(connected || connecting, syncOn)
                val entryCount = if (showServerSettings) 3 else 2
                SettingsGroup {
                    SettingsGroupItem(0, entryCount) {
                        SettingsEntryRow(
                            iconRes = R.drawable.mobile_24px,
                            title = stringResource(R.string.provider_connection_settings),
                            subtitle = stringResource(R.string.provider_connection_settings_desc),
                            onClick = { onOpenConnection(serverId) }
                        )
                    }
                    SettingsGroupItem(1, entryCount) {
                        SettingsEntryRow(
                            iconRes = R.drawable.wifi_24px,
                            title = stringResource(R.string.connection_mode_title),
                            subtitle = stringResource(R.string.provider_connection_mode_desc),
                            onClick = { onOpenConnectionMode(serverId) }
                        )
                    }
                    if (showServerSettings) {
                        SettingsGroupItem(2, entryCount) {
                            SettingsEntryRow(
                                iconRes = R.drawable.database_24px,
                                title = stringResource(R.string.provider_server_settings),
                                subtitle = stringResource(R.string.provider_server_settings_desc),
                                onClick = { onOpenServerSettings(serverId) }
                            )
                        }
                    }
                }

                if (nerdMode && server != null) {
                    SettingsCard {
                        SettingsEntryRow(
                            iconRes = R.drawable.terminal_24px,
                            title = stringResource(R.string.nerd_section_title),
                            subtitle = stringResource(R.string.nerd_section_desc),
                            onClick = { onOpenNerdInfo(serverId) }
                        )
                    }
                }

                if (server != null) {
                    SectionLabel(stringResource(R.string.server_management))
                    val canCleanup = server.ssh.ip.isNotBlank()
                    val mgmtCount = if (canCleanup) 2 else 1
                    SettingsGroup {
                        if (canCleanup) {
                            SettingsGroupItem(0, mgmtCount) {
                                SettingsEntryRow(
                                    iconRes = R.drawable.mop_24px,
                                    title = stringResource(R.string.server_clean_title),
                                    subtitle = stringResource(R.string.server_clean_subtitle),
                                    trailingRes = null,
                                    iconContainer = MaterialTheme.colorScheme.errorContainer,
                                    iconTint = MaterialTheme.colorScheme.onErrorContainer,
                                    titleColorOverride = MaterialTheme.colorScheme.error,
                                    onClick = { showCleanup = true }
                                )
                            }
                        }
                        SettingsGroupItem(mgmtCount - 1, mgmtCount) {
                            SettingsEntryRow(
                                iconRes = R.drawable.delete_24px,
                                title = stringResource(R.string.server_delete_app),
                                subtitle = stringResource(R.string.server_delete_app_subtitle),
                                trailingRes = null,
                                iconContainer = MaterialTheme.colorScheme.errorContainer,
                                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                                titleColorOverride = MaterialTheme.colorScheme.error,
                                onClick = { showDelete = true }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(88.dp))
            }
        }
    }

    if (showDelete && server != null) {
        DeleteServerDialog(
            serverName = server.name,
            onConfirm = {
                settingsViewModel.deleteServer(serverId)
                showDelete = false
            },
            onDismiss = { showDelete = false }
        )
    }

    if (showCleanup && server != null) {
        val cleanupState by settingsViewModel.cleanupState.collectAsStateWithLifecycle()
        ServerCleanupDialog(
            state = cleanupState,
            onConfirm = { settingsViewModel.cleanupServer(serverId) },
            onClose = {
                showCleanup = false
                settingsViewModel.resetCleanupState()
            }
        )
    }

    if (showRename && server != null) {
        RenameServerDialog(
            currentName = server.name,
            onSave = { name ->
                settingsViewModel.renameServer(serverId, name)
                showRename = false
            },
            onDismiss = { showRename = false }
        )
    }
}

@Composable
private fun ServerHubActionsFab(
    online: ServerHubState.Online?,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRename: () -> Unit,
    onClone: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it }
            ) {
                // ToggleFloatingActionButton не задаёт контентный цвет - тинтуем сами
                // под контейнер (primaryContainer в покое -> primary при раскрытии).
                Icon(
                    painterResource(R.drawable.more_vert_24px),
                    contentDescription = stringResource(R.string.server_actions),
                    tint = if (expanded) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) {
        if (online != null) {
            FloatingActionButtonMenuItem(
                onClick = { expanded = false; onInstall() },
                icon = { Icon(painterResource(R.drawable.cloud_download_24px), contentDescription = null) },
                text = {
                    Text(stringResource(
                        if (online.installed) R.string.server_update else R.string.server_install
                    ))
                }
            )
            if (online.installed && !online.running) {
                FloatingActionButtonMenuItem(
                    onClick = { expanded = false; onStart() },
                    icon = { Icon(painterResource(R.drawable.play_arrow_24px), contentDescription = null) },
                    text = { Text(stringResource(R.string.start_server)) }
                )
            }
            if (online.running) {
                FloatingActionButtonMenuItem(
                    onClick = { expanded = false; onStop() },
                    icon = { Icon(painterResource(R.drawable.stop_24px), contentDescription = null) },
                    text = { Text(stringResource(R.string.stop_server)) }
                )
            }
        }
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onRename() },
            icon = { Icon(painterResource(R.drawable.edit_24px), contentDescription = null) },
            text = { Text(stringResource(R.string.menu_rename_server)) }
        )
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onClone() },
            icon = { Icon(painterResource(R.drawable.content_copy_24px), contentDescription = null) },
            text = { Text(stringResource(R.string.menu_clone_server)) }
        )
    }
}
