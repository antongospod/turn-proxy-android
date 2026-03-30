@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import com.freeturn.app.ui.theme.StatusGreen
import com.freeturn.app.ui.theme.StatusGreenDark
import com.freeturn.app.viewmodel.MainViewModel
import com.freeturn.app.viewmodel.ProxyState
import com.freeturn.app.viewmodel.SshConnectionState
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
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()

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

    val showBottomSheet = rememberSaveable { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Turn Proxy") },
                actions = {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showBottomSheet.value = true
                    }) {
                        Icon(Icons.Filled.Info, contentDescription = "Информация")
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
                            viewModel.startProxy(context)
                        }
                        is ProxyState.Running -> {
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_OFF)
                            viewModel.stopProxy(context)
                        }
                        else -> {}
                    }
                }
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = when (proxyState) {
                    is ProxyState.Running -> "Прокси активен"
                    is ProxyState.Starting -> "Подключение..."
                    is ProxyState.Error -> (proxyState as ProxyState.Error).message
                    else -> "Нажмите для запуска"
                },
                style = MaterialTheme.typography.titleMedium,
                color = when (proxyState) {
                    is ProxyState.Running -> StatusGreen
                    is ProxyState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                },
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(40.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Текущие настройки", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    if (clientConfig.serverAddress.isNotBlank()) {
                        ConfigRow("Сервер", clientConfig.serverAddress)
                        ConfigRow("Потоки", "${clientConfig.threads}")
                        ConfigRow("UDP", if (clientConfig.useUdp) "Вкл" else "Выкл")
                        ConfigRow("Локальный порт", clientConfig.localPort)
                    } else {
                        Text(
                            "Не настроено. Перейдите в раздел «Клиент».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
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
                            if (sshState is SshConnectionState.Connected) StatusGreen
                            else MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (sshState) {
                        is SshConnectionState.Connected -> "SSH: ${(sshState as SshConnectionState.Connected).ip}"
                        is SshConnectionState.Connecting -> "SSH: подключение..."
                        is SshConnectionState.Error -> "SSH: ошибка"
                        else -> "SSH: не подключено"
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
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Переподключить", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showBottomSheet.value) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet.value = false },
            sheetState = bottomSheetState
        ) {
            InfoBottomSheet(
                viewModel = viewModel,
                onNavigateToSshSetup = {
                    showBottomSheet.value = false
                    onNavigateToSshSetup()
                }
            )
        }
    }
}

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
            is ProxyState.Error -> MaterialTheme.colorScheme.error
            is ProxyState.Starting -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.primary
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
                is ProxyState.Starting -> CircularProgressIndicator(color = contentColor)
                is ProxyState.Running -> Icon(
                    Icons.Filled.CheckCircle, null,
                    Modifier.size(52.dp), tint = contentColor
                )
                is ProxyState.Error -> Icon(
                    Icons.Filled.Error, null,
                    Modifier.size(52.dp), tint = contentColor
                )
                else -> Icon(
                    Icons.Filled.PlayArrow, null,
                    Modifier.size(52.dp), tint = contentColor
                )
            }
        }
    }
}

@Composable
private fun InfoBottomSheet(
    viewModel: MainViewModel,
    onNavigateToSshSetup: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val sshState by viewModel.sshState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var showLogs by rememberSaveable { mutableStateOf(false) }

    val appVersion = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—" }
        catch (_: Exception) { "—" }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ── SSH ────────────────────────────────────────────────────────────
        val isConnected = sshState is SshConnectionState.Connected
        ListItem(
            headlineContent = { Text("Соединение", style = MaterialTheme.typography.titleSmall) },
            trailingContent = {
                TextButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    onNavigateToSshSetup()
                }) { Text(if (isConnected) "Изменить" else "Настроить") }
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("SSH-статус") },
            supportingContent = {
                Text(
                    when (sshState) {
                        is SshConnectionState.Connected ->
                            "Подключено: ${(sshState as SshConnectionState.Connected).ip}"
                        is SshConnectionState.Connecting ->
                            (sshState as SshConnectionState.Connecting).step
                        is SshConnectionState.Error ->
                            "Ошибка: ${(sshState as SshConnectionState.Error).message}"
                        else -> "Не подключено"
                    }
                )
            },
            trailingContent = {
                if (isConnected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(StatusGreen, CircleShape))
                        TextButton(onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.disconnectSsh()
                        }) { Text("Отключить") }
                    }
                } else {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        viewModel.reconnectSsh()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Переподключить")
                    }
                }
            }
        )

        HorizontalDivider()

        // ── Логи ──────────────────────────────────────────────────────────
        ListItem(
            headlineContent = { Text("Логи", style = MaterialTheme.typography.titleSmall) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("proxy_logs", logs.joinToString("\n")))
                            HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                        },
                        enabled = logs.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать логи", modifier = Modifier.size(18.dp))
                    }
                    TextButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        viewModel.clearLogs()
                    }) { Text("Очистить") }
                    TextButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                        showLogs = !showLogs
                    }) {
                        Text(if (showLogs) "Скрыть" else "Показать")
                    }
                }
            }
        )

        if (showLogs) {
            LogsPanel(
                logs = logs,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        HorizontalDivider()

        // ── О приложении ───────────────────────────────────────────────────
        ListItem(
            headlineContent = { Text("О приложении", style = MaterialTheme.typography.titleSmall) }
        )

        ListItem(
            headlineContent = { Text("FreeTurn") },
            supportingContent = { Text("Версия $appVersion") }
        )

        RepoLinkItem(
            title = "Android-клиент",
            subtitle = "Fork MYSOREZ/vk-turn-proxy-android",
            url = "https://github.com/MYSOREZ/vk-turn-proxy-android",
            onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
            onOpen = { uriHandler.openUri(it) }
        )

        RepoLinkItem(
            title = "Прокси-ядро",
            subtitle = "cacggghp/vk-turn-proxy",
            url = "https://github.com/cacggghp/vk-turn-proxy",
            onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
            onOpen = { uriHandler.openUri(it) }
        )
    }
}

@Composable
private fun RepoLinkItem(
    title: String,
    subtitle: String,
    url: String,
    onHaptic: () -> Unit,
    onOpen: (String) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.Launch,
                contentDescription = "Открыть",
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

@Composable
private fun LogsPanel(logs: List<String>, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(logs.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(scrollState)
                .padding(vertical = 8.dp)
        ) {
            if (logs.isEmpty()) {
                Text(
                    "Нет логов",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            } else {
                logs.forEachIndexed { index, line ->
                    LogLine(line = line, isEven = index % 2 == 0)
                }
            }
        }
    }
}

@Composable
private fun LogLine(line: String, isEven: Boolean) {
    val lower = line.lowercase()
    val isHeader = line.startsWith("===")
    val isError = lower.contains("ошибка") || lower.contains("error") ||
                  lower.contains("критическая") || lower.contains("failed") ||
                  lower.contains("fatal") || lower.contains("panic")
    val isWarning = lower.contains("watchdog") || lower.contains("перезапуск") ||
                    lower.contains("quota") || lower.contains("warn") ||
                    lower.contains(">>>")
    val isSuccess = lower.contains("запущен") || lower.contains("подключен") ||
                    lower.contains("success") || lower.contains("started") ||
                    lower.contains("ok")

    val textColor = when {
        isError   -> MaterialTheme.colorScheme.error
        isWarning -> androidx.compose.ui.graphics.Color(0xFFE67E22)
        isSuccess -> StatusGreen
        isHeader  -> MaterialTheme.colorScheme.primary
        else      -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bgColor = if (isEven)
        MaterialTheme.colorScheme.surfaceVariant
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (isHeader || isError || isWarning || isSuccess) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp, end = 6.dp)
                    .size(5.dp)
                    .background(textColor, CircleShape)
            )
        } else {
            Spacer(Modifier.width(11.dp))
        }
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = if (isHeader) androidx.compose.ui.text.font.FontWeight.SemiBold
                             else androidx.compose.ui.text.font.FontWeight.Normal
            ),
            color = textColor
        )
    }
}

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
