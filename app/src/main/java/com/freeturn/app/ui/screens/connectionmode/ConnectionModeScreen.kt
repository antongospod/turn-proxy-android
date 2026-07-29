@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.connectionmode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.domain.ProxyState
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.screens.splittunnel.SplitTunnelModal
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.viewmodel.proxy.ProxyViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConnectionModeScreen(
    settingsViewModel: SettingsViewModel,
    proxyViewModel: ProxyViewModel,
    serverId: String? = null,
    onBack: (() -> Unit)? = null
) {
    val snapshot by settingsViewModel.serversSnapshot.collectAsStateWithLifecycle()
    val activeClient by settingsViewModel.clientConfig.collectAsStateWithLifecycle()
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()
    val proxyState by proxyViewModel.proxyState.collectAsStateWithLifecycle()

    val server = serverId?.let { id -> snapshot.list.firstOrNull { it.id == id } }
    val saved = server?.client ?: activeClient
    val isActive = serverId == null || serverId == snapshot.activeId

    fun clientEdit(transform: (ClientConfig) -> ClientConfig) {
        if (serverId != null) {
            settingsViewModel.updateServerClient(serverId, transform)
        } else {
            settingsViewModel.saveClientConfig(transform(settingsViewModel.clientConfig.value), snapshot.activeId)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // userPickedVpn сохраняет выбор на время сессии (чтобы сегмент не мигал).
    var userPickedVpn by remember(serverId, saved.tunnelTransport) { mutableStateOf<Boolean?>(null) }
    val isVpn = userPickedVpn ?: (saved.tunnelTransport == TunnelTransport.WIREGUARD)

    val fieldsKey = serverId ?: snapshot.activeId
    var wgConfig by remember(fieldsKey) { mutableStateOf(saved.wireGuardConfig) }
    var wgName by remember(fieldsKey) { mutableStateOf(saved.wireGuardTunnelName) }

    fun persistWg(vpn: Boolean = isVpn) {
        clientEdit {
            it.copy(
                tunnelTransport = if (vpn) TunnelTransport.WIREGUARD else TunnelTransport.NONE,
                wireGuardConfig = wgConfig.trim(),
                wireGuardTunnelName = wgName.trim().ifBlank { TunnelTransport.DEFAULT_TUNNEL_NAME }
            )
        }
    }

    var showSplitSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isActive) { if (!isActive) showSplitSheet = false }

    var wgDirty by remember(fieldsKey) { mutableStateOf(false) }
    var pendingSave by remember(fieldsKey) { mutableStateOf(false) }

    LaunchedEffect(fieldsKey, saved) {
        if (wgDirty) return@LaunchedEffect
        wgConfig = saved.wireGuardConfig
        wgName = saved.wireGuardTunnelName
    }

    LaunchedEffect(fieldsKey, wgConfig, wgName) {
        if (!wgDirty) return@LaunchedEffect
        pendingSave = true
        delay(600)
        persistWg()
        pendingSave = false
    }
    val flush by rememberUpdatedState { persistWg() }
    DisposableEffect(Unit) {
        onDispose { if (pendingSave) flush() }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (!text.isNullOrBlank()) {
                    wgConfig = text
                    wgDirty = true
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.connection_mode_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                painterResource(R.drawable.arrow_back_24px),
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
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
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isVpn,
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                            userPickedVpn = false
                            persistWg(vpn = false)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text(stringResource(R.string.mode_proxy)) }
                    SegmentedButton(
                        selected = isVpn,
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                            userPickedVpn = true
                            persistWg(vpn = true)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text(stringResource(R.string.mode_vpn)) }
                }

                Text(
                    stringResource(if (isVpn) R.string.mode_vpn_desc else R.string.mode_proxy_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isVpn) {
                    WireGuardConfigCard(
                        wgConfig = wgConfig,
                        onWgConfig = { wgConfig = it; wgDirty = true },
                        wgName = wgName,
                        onWgName = { wgName = it; wgDirty = true },
                        privacyMode = privacyMode,
                        onLoadFile = { filePicker.launch("*/*") }
                    )

                    SectionLabel(stringResource(R.string.split_tunnel_title))
                    SettingsCard {
                        SettingsEntryRow(
                            iconRes = R.drawable.mobile_24px,
                            title = stringResource(R.string.split_tunnel_title),
                            trailingRes = R.drawable.unfold_more_24px,
                            enabled = isActive,
                            onClick = { showSplitSheet = true }
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showSplitSheet && isActive) {
        SplitTunnelModal(
            mode = saved.splitTunnelMode,
            apps = saved.splitTunnelApps,
            locked = proxyState !is ProxyState.Idle && proxyState !is ProxyState.Error,
            onModeChange = settingsViewModel::setSplitTunnelMode,
            onAppsChange = settingsViewModel::setSplitTunnelApps,
            onDismiss = { showSplitSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    }
}
