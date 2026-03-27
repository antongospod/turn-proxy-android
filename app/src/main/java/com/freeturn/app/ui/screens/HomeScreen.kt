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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.ui.theme.StatusGreen
import com.freeturn.app.ui.theme.StatusGreenDark
import com.freeturn.app.ui.theme.StatusRed
import com.freeturn.app.viewmodel.MainViewModel
import com.freeturn.app.viewmodel.ProxyState
import com.freeturn.app.viewmodel.ServerState
import com.freeturn.app.viewmodel.SshConnectionState

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToSshSetup: () -> Unit
) {
    val context = LocalContext.current
    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val sshState by viewModel.sshState.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    var showBottomSheet by rememberSaveable { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VK TURN Proxy") },
                actions = {
                    // Кнопка «Детали и логи» через иконку — без ••• меню
                    IconButton(onClick = { showBottomSheet = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "Детали")
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

            // Big power button
            ProxyToggleButton(
                state = proxyState,
                onClick = {
                    when (proxyState) {
                        is ProxyState.Idle, is ProxyState.Error -> viewModel.startProxy(context)
                        is ProxyState.Running -> viewModel.stopProxy(context)
                        else -> {}
                    }
                }
            )

            Spacer(Modifier.height(24.dp))

            // State label
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

            // Config summary card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                onClick = { showBottomSheet = true },
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Текущие настройки", style = MaterialTheme.typography.titleSmall)
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (clientConfig.serverAddress.isNotBlank()) {
                        ConfigRow("Сервер", clientConfig.serverAddress)
                        ConfigRow("Потоки", "${clientConfig.threads}")
                        ConfigRow("UDP", if (clientConfig.useUdp) "Вкл" else "Выкл")
                        ConfigRow("Локальный порт", clientConfig.localPort)
                    } else {
                        Text(
                            "Не настроено. Нажмите для настройки.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // SSH status row
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
                    TextButton(onClick = { viewModel.reconnectSsh() }, modifier = Modifier.height(28.dp)) {
                        Text("Переподключить", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Bottom sheet with all settings
    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = bottomSheetState
        ) {
            SettingsBottomSheet(
                viewModel = viewModel,
                onNavigateToSshSetup = { showBottomSheet = false; onNavigateToSshSetup() },
                onDismiss = { showBottomSheet = false }
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
                is ProxyState.Starting -> CircularWavyProgressIndicator(color = contentColor)
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
private fun SettingsBottomSheet(
    viewModel: MainViewModel,
    onNavigateToSshSetup: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sshState by viewModel.sshState.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    val isConnected = sshState is SshConnectionState.Connected
    val isWorking = serverState is ServerState.Working || serverState is ServerState.Checking
    val serverKnown = serverState as? ServerState.Known
    var showLogs by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ── Сервер ────────────────────────────────────────────────────────
        ListItem(
            headlineContent = { Text("Сервер", style = MaterialTheme.typography.titleSmall) },
            trailingContent = {
                TextButton(onClick = onNavigateToSshSetup) { Text("Изменить") }
            }
        )
        Divider()

        ListItem(
            headlineContent = { Text("SSH-соединение") },
            supportingContent = {
                Text(
                    when (sshState) {
                        is SshConnectionState.Connected -> "Подключено: ${(sshState as SshConnectionState.Connected).ip}"
                        is SshConnectionState.Connecting -> "Подключение..."
                        is SshConnectionState.Error -> "Ошибка: ${(sshState as SshConnectionState.Error).message}"
                        else -> "Не подключено"
                    }
                )
            },
            trailingContent = {
                if (sshState !is SshConnectionState.Connected) {
                    IconButton(onClick = { viewModel.reconnectSsh() }) {
                        Icon(Icons.Filled.Refresh, null)
                    }
                } else {
                    Box(Modifier.size(10.dp).background(StatusGreen, CircleShape))
                }
            }
        )

        ListItem(
            headlineContent = { Text("vk-turn-proxy") },
            supportingContent = {
                Text(
                    when {
                        serverKnown?.running == true -> "Запущен"
                        serverKnown?.installed == true -> "Установлен, не запущен"
                        serverKnown?.installed == false -> "Не установлен"
                        isWorking -> (serverState as? ServerState.Working)?.action ?: "Проверка..."
                        else -> "Статус неизвестен"
                    }
                )
            }
        )

        if (isWorking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { viewModel.installServer() },
                enabled = isConnected && !isWorking,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.CloudDownload, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Обновить", style = MaterialTheme.typography.labelSmall)
            }
            Button(
                onClick = { viewModel.startServer() },
                enabled = isConnected && !isWorking && serverKnown?.installed == true && serverKnown?.running == false,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Запустить", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = { viewModel.stopServer() },
                enabled = isConnected && !isWorking && serverKnown?.running == true,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Stop, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Стоп", style = MaterialTheme.typography.labelSmall)
            }
        }

        Divider()

        // ── Клиент ────────────────────────────────────────────────────────
        ListItem(
            headlineContent = { Text("Клиент", style = MaterialTheme.typography.titleSmall) }
        )
        Divider()

        if (clientConfig.serverAddress.isNotBlank()) {
            ListItem(headlineContent = { Text("Сервер") },
                supportingContent = { Text(clientConfig.serverAddress) })
            ListItem(headlineContent = { Text("Ссылка VK/Yandex") },
                supportingContent = {
                    Text(
                        clientConfig.vkLink.take(60) + if (clientConfig.vkLink.length > 60) "..." else "",
                        maxLines = 2
                    )
                })
            ListItem(headlineContent = { Text("Потоки / UDP / DTLS") },
                supportingContent = {
                    Text("${clientConfig.threads} потоков · UDP: ${if (clientConfig.useUdp) "вкл" else "выкл"} · DTLS: ${if (clientConfig.noDtls) "выкл" else "вкл"}")
                })
        } else {
            ListItem(
                headlineContent = { Text("Не настроено") },
                supportingContent = { Text("Нажмите «Изменить» для настройки клиента") }
            )
        }

        Divider()

        // ── Логи ─────────────────────────────────────────────────────────
        ListItem(
            headlineContent = { Text("Логи", style = MaterialTheme.typography.titleSmall) },
            trailingContent = {
                Row {
                    TextButton(onClick = { viewModel.clearLogs() }) { Text("Очистить") }
                    TextButton(onClick = { showLogs = !showLogs }) {
                        Text(if (showLogs) "Скрыть" else "Показать")
                    }
                }
            }
        )

        if (showLogs) {
            val recentLogs = logs.takeLast(30)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (recentLogs.isEmpty()) {
                        Text(
                            "Нет логов",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    } else {
                        recentLogs.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(4.dp))
}
