@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.freeturn.app.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.ui.theme.StatusBlue
import com.freeturn.app.ui.theme.StatusGreen
import com.freeturn.app.ui.theme.StatusGreenDark
import com.freeturn.app.viewmodel.MainViewModel
import com.freeturn.app.viewmodel.ProxyState
import com.freeturn.app.viewmodel.SshConnectionState
import com.freeturn.app.viewmodel.UpdateState
import androidx.core.net.toUri

@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToSshSetup: () -> Unit,
    onNavigateToClientSetup: () -> Unit = {}
) {
    val context = LocalContext.current
    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val sshState by viewModel.sshState.collectAsStateWithLifecycle()
    val sshConfig by viewModel.sshConfig.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val isConfigured = sshConfig.ip.isNotBlank()

    // ── Запрос разрешений при первом открытии главного экрана ─────────────
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* пользователь закрыл диалог батареи — результат нас не интересует */ }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // После диалога уведомлений — запрашиваем исключение из оптимизации батареи
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            batteryOptLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${context.packageName}".toUri()
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        delay(400) // даём экрану отрисоваться
        val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED

        if (needsNotification) {
            // Запрашиваем нотификации; батарею запросим в callback выше
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Нотификации уже есть — сразу проверяем батарею
            val pm = context.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                batteryOptLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                )
            }
        }
    }

    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val showBottomSheet = rememberSaveable { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.turn_proxy_title)) },
                actions = {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showBottomSheet.value = true
                    }) {
                        Icon(painterResource(R.drawable.info_24px), contentDescription = stringResource(R.string.info_desc))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            ProxyToggleButton(
                state = proxyState,
                onClick = {
                    when (proxyState) {
                        is ProxyState.Idle, is ProxyState.Error -> {
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                            viewModel.startProxy()
                        }
                        is ProxyState.Running -> {
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_OFF)
                            viewModel.stopProxy()
                        }
                        else -> {}
                    }
                }
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = when (proxyState) {
                    is ProxyState.Running -> stringResource(R.string.proxy_active)
                    is ProxyState.Starting -> stringResource(R.string.proxy_connecting)
                    is ProxyState.Error -> (proxyState as ProxyState.Error).message
                    is ProxyState.CaptchaRequired -> "Требуется прохождение капчи"
                    else -> stringResource(R.string.proxy_press_to_start)
                },
                style = MaterialTheme.typography.titleMedium,
                color = when (proxyState) {
                    is ProxyState.Running -> StatusGreen
                    is ProxyState.Error -> MaterialTheme.colorScheme.error
                    is ProxyState.CaptchaRequired -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                },
                textAlign = TextAlign.Center
            )

            if (isConfigured) {
                Spacer(Modifier.height(40.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.current_settings), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(12.dp))
                        ConfigRow(stringResource(R.string.server), clientConfig.serverAddress.redact(privacyMode))
                        ConfigRow(stringResource(R.string.threads), "${clientConfig.threads}")
                        ConfigRow(stringResource(R.string.transport_protocol), if (clientConfig.useUdp) stringResource(R.string.udp) else stringResource(R.string.tcp))
                        ConfigRow(stringResource(R.string.local_port), clientConfig.localPort.redact(privacyMode))
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (sshState is SshConnectionState.Connected) StatusBlue
                                else MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (sshState) {
                            is SshConnectionState.Connected -> "SSH: ${(sshState as SshConnectionState.Connected).ip.redact(privacyMode)}"
                            is SshConnectionState.Connecting -> stringResource(R.string.ssh_connecting)
                            is SshConnectionState.Error -> stringResource(R.string.ssh_error)
                            else -> stringResource(R.string.ssh_disconnected)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    if (sshState !is SshConnectionState.Connected) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                viewModel.reconnectSsh()
                            },
                            enabled = sshState !is SshConnectionState.Connecting,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(stringResource(R.string.reconnect), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showBottomSheet.value) {
        val sheetColor = MaterialTheme.colorScheme.surfaceContainerLow
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet.value = false },
            sheetState = bottomSheetState,
            containerColor = sheetColor
        ) {
            InfoBottomSheet(
                viewModel = viewModel,
                containerColor = sheetColor,
                privacyMode = privacyMode,
                onPrivacyModeChange = { viewModel.setPrivacyMode(it) },
                onNavigateToSshSetup = {
                    showBottomSheet.value = false
                    onNavigateToSshSetup()
                }
            )
        }
    }

    UpdateDialogs(viewModel)

    if (proxyState is ProxyState.CaptchaRequired) {
        CaptchaWebViewDialog(
            captchaUrl = (proxyState as ProxyState.CaptchaRequired).url,
            onDismiss = { viewModel.dismissCaptcha() }
        )
    }
}

// ── Диалоги обновления ────────────────────────────────────────────────────

@Composable
private fun UpdateDialogs(viewModel: MainViewModel) {
    val context = LocalContext.current
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf(false) }

    when (val state = updateState) {
        is UpdateState.Available -> if (!dismissed) {
            AlertDialog(
                onDismissRequest = { dismissed = true },
                title = { Text(stringResource(R.string.update_available_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.update_available, state.version))
                        if (state.changelog.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    state.changelog,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        dismissed = true
                        viewModel.downloadUpdate()
                    }) { Text(stringResource(R.string.update_download)) }
                },
                dismissButton = {
                    TextButton(onClick = { dismissed = true }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.update_downloading_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.update_downloading, state.progress))
                        Spacer(Modifier.height(12.dp))
                        LinearWavyProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetUpdateState() },
                title = { Text(stringResource(R.string.update_ready_title)) },
                text = { Text(stringResource(R.string.update_ready_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        viewModel.installUpdate()
                    }) { Text(stringResource(R.string.update_install)) }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resetUpdateState() }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        else -> {}
    }
}

// ── Кнопка прокси ─────────────────────────────────────────────────────────

@Composable
private fun ProxyToggleButton(state: ProxyState, onClick: () -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = when (state) {
            is ProxyState.Running -> StatusGreenDark
            is ProxyState.Error -> MaterialTheme.colorScheme.errorContainer
            is ProxyState.Starting -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = tween(600),
        label = "btn_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = when (state) {
            is ProxyState.Running -> StatusGreen
            is ProxyState.Error -> MaterialTheme.colorScheme.onErrorContainer
            is ProxyState.Starting -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = tween(600),
        label = "btn_fg"
    )
    val scale by animateFloatAsState(
        targetValue = if (state is ProxyState.Starting) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btn_scale"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(148.dp)
            .scale(scale)
            .clip(CircleShape),
        shape = CircleShape,
        color = containerColor,
        shadowElevation = if (state is ProxyState.Running) 12.dp else 4.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (state) {
                is ProxyState.Starting -> CircularWavyProgressIndicator(color = contentColor)
                is ProxyState.Running -> Icon(
                    painterResource(R.drawable.check_circle_24px), stringResource(R.string.proxy_active_stop),
                    Modifier.size(52.dp), tint = contentColor
                )
                is ProxyState.Error -> Icon(
                    painterResource(R.drawable.error_24px), stringResource(R.string.proxy_error_restart),
                    Modifier.size(52.dp), tint = contentColor
                )
                else -> Icon(
                    painterResource(R.drawable.play_arrow_24px), stringResource(R.string.start_proxy),
                    Modifier.size(52.dp), tint = contentColor
                )
            }
        }
    }
}

// ── Bottom sheet ──────────────────────────────────────────────────────────

@Composable
private fun InfoBottomSheet(
    viewModel: MainViewModel,
    containerColor: Color,
    privacyMode: Boolean,
    onPrivacyModeChange: (Boolean) -> Unit,
    onNavigateToSshSetup: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val sshState by viewModel.sshState.collectAsStateWithLifecycle()
    val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    val appVersion = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—" }
        catch (_: Exception) { "—" }
    }

    val isConnected = sshState is SshConnectionState.Connected
    val listColors = ListItemDefaults.colors(containerColor = containerColor)

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // ── Соединение ────────────────────────────────────────────────────
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.connection), style = MaterialTheme.typography.titleSmall) },
                colors = listColors,
                supportingContent = {
                    Text(
                        when (sshState) {
                            is SshConnectionState.Connected ->
                                stringResource(R.string.connected_format, (sshState as SshConnectionState.Connected).ip.redact(privacyMode))
                            is SshConnectionState.Connecting ->
                                stringResource(R.string.ssh_connecting)
                            is SshConnectionState.Error ->
                                stringResource(R.string.error_format_short, (sshState as SshConnectionState.Error).message)
                            else -> stringResource(R.string.not_connected)
                        }
                    )
                },
                trailingContent = {
                    if (isConnected) {
                        TextButton(onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onNavigateToSshSetup()
                        }) { Text(stringResource(R.string.change)) }
                    } else {
                        TextButton(onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onNavigateToSshSetup()
                        }) { Text(stringResource(R.string.configure)) }
                    }
                }
            )
        }

        item { HorizontalDivider() }

        // ── Ссылки ────────────────────────────────────────────────────────
        item {
            RepoLinkItem(
                title = stringResource(R.string.android_client),
                subtitle = "antongospod/turn-proxy-android",
                url = "https://github.com/antongospod/turn-proxy-android",
                containerColor = containerColor,
                onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                onOpen = { uriHandler.openUri(it) }
            )
        }

        item {
            RepoLinkItem(
                title = stringResource(R.string.proxy_core),
                subtitle = "cacggghp/vk-turn-proxy",
                url = "https://github.com/cacggghp/vk-turn-proxy",
                containerColor = containerColor,
                onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                onOpen = { uriHandler.openUri(it) }
            )
        }

        item { HorizontalDivider() }

        // ── Настройки интерфейса ──────────────────────────────────────────
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.privacy_mode_title)) },
                supportingContent = { Text(stringResource(R.string.privacy_mode_desc)) },
                colors = listColors,
                trailingContent = {
                    androidx.compose.material3.Switch(
                        checked = privacyMode,
                        onCheckedChange = onPrivacyModeChange
                    )
                }
            )
        }

        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.dynamic_theme_title)) },
                supportingContent = { Text(stringResource(R.string.dynamic_theme_desc)) },
                colors = listColors,
                trailingContent = {
                    androidx.compose.material3.Switch(
                        checked = dynamicTheme,
                        onCheckedChange = { viewModel.setDynamicTheme(it) }
                    )
                }
            )
        }

        item { HorizontalDivider() }

        // ── Обновление ────────────────────────────────────────────────────
        item {
            UpdateListItem(
                state = updateState,
                colors = listColors,
                onCheck = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    viewModel.checkForUpdate()
                },
                onDownload = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    viewModel.downloadUpdate()
                },
                onInstall = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    viewModel.installUpdate()
                }
            )
        }

        item { HorizontalDivider() }

        // ── Сброс ─────────────────────────────────────────────────────────
        item {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.reset_settings),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                colors = listColors,
                trailingContent = {
                    Icon(
                        painterResource(R.drawable.delete_24px),
                        contentDescription = stringResource(R.string.reset),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                modifier = Modifier.clickable {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showResetDialog = true
                }
            )
        }

        // ── Версия ────────────────────────────────────────────────────────
        item {
            Text(
                text = "v$appVersion",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                        viewModel.resetAllSettings(context)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

// ── Пункт обновления в bottom sheet ───────────────────────────────────────

@Composable
private fun UpdateListItem(
    state: UpdateState,
    colors: ListItemColors,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val supportingText = when (state) {
        is UpdateState.Idle -> stringResource(R.string.update_tap_to_check)
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.Available -> stringResource(R.string.update_available, state.version)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.progress)
        is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_desc_short)
        is UpdateState.NoUpdate -> stringResource(R.string.update_no_update)
        is UpdateState.Error -> stringResource(R.string.update_error, state.message)
    }

    ListItem(
        headlineContent = {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = {
            Column {
                Text(
                    supportingText,
                    color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state is UpdateState.Downloading) {
                    LinearWavyProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }
        },
        trailingContent = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.update_download))
                }
                is UpdateState.ReadyToInstall -> TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.update_install))
                }
                is UpdateState.Idle, is UpdateState.NoUpdate, is UpdateState.Error ->
                    TextButton(onClick = onCheck) {
                        Text(stringResource(R.string.update_check))
                    }
                else -> {}
            }
        },
        colors = colors
    )
}

// ── Общие компоненты ──────────────────────────────────────────────────────

@Composable
private fun RepoLinkItem(
    title: String,
    subtitle: String,
    url: String,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onHaptic: () -> Unit,
    onOpen: (String) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        colors = ListItemDefaults.colors(containerColor = containerColor),
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Icon(
                painterResource(R.drawable.open_in_new_24px),
                contentDescription = stringResource(R.string.btn_open),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier.clickable {
            onHaptic()
            onOpen(url)
        }
    )
}


internal fun String.redact(enabled: Boolean) = if (enabled) "••••••" else this

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(4.dp))
}
