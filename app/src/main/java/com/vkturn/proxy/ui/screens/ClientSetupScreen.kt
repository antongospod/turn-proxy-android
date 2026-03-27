@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vkturn.proxy.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkturn.proxy.data.ClientConfig
import com.vkturn.proxy.viewmodel.MainViewModel

@Composable
fun ClientSetupScreen(
    viewModel: MainViewModel,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    val saved by viewModel.clientConfig.collectAsStateWithLifecycle()

    var serverAddress by rememberSaveable(saved.serverAddress) { mutableStateOf(saved.serverAddress) }
    var vkLink by rememberSaveable(saved.vkLink) { mutableStateOf(saved.vkLink) }
    var threads by rememberSaveable(saved.threads) { mutableFloatStateOf(saved.threads.toFloat()) }
    var useUdp by rememberSaveable(saved.useUdp) { mutableStateOf(saved.useUdp) }
    var noDtls by rememberSaveable(saved.noDtls) { mutableStateOf(saved.noDtls) }
    var localPort by rememberSaveable(saved.localPort) { mutableStateOf(saved.localPort) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройка клиента") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Сервер ───────────────────────────────────────────────────
            Text("Подключение", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = serverAddress,
                onValueChange = { serverAddress = it },
                label = { Text("Адрес vk-turn-proxy сервера") },
                placeholder = { Text("1.2.3.4:56000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text("IP и порт, на котором запущен vk-turn-proxy на вашем VPS")
                }
            )

            OutlinedTextField(
                value = vkLink,
                onValueChange = { vkLink = it },
                label = { Text("Ссылка VK-звонок / Yandex Telemost") },
                placeholder = { Text("https://vk.com/call/...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                supportingText = {
                    Text("VK: Звонки → Создать → Скопировать ссылку (не нажимайте «Завершить»). Тип определяется автоматически.")
                }
            )

            Divider()

            // ── Параметры ─────────────────────────────────────────────────
            Text("Параметры", style = MaterialTheme.typography.titleMedium)

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text("Потоки: ${threads.toInt()}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Рекомендуется 4–8",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                Slider(
                    value = threads,
                    onValueChange = { threads = it },
                    valueRange = 1f..8f,
                    steps = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SwitchRow(
                label = "UDP-режим",
                description = "Снижает задержки и повышает стабильность",
                checked = useUdp,
                onCheckedChange = { useUdp = it }
            )

            SwitchRow(
                label = "Без DTLS-шифрования",
                description = "Может ускорить соединение, повышает риск блокировки",
                checked = noDtls,
                onCheckedChange = { noDtls = it }
            )

            Divider()

            // ── Дополнительно (сворачиваемый блок) ───────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = { showAdvanced = !showAdvanced }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Дополнительно", style = MaterialTheme.typography.labelLarge)
                    Icon(
                        if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = localPort,
                        onValueChange = { localPort = it },
                        label = { Text("Локальный адрес прослушивания") },
                        placeholder = { Text("127.0.0.1:9000") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = {
                            Text("Адрес, на котором клиент принимает трафик от WireGuard/Hysteria")
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.saveClientConfig(
                        ClientConfig(
                            serverAddress = serverAddress.trim(),
                            vkLink = vkLink.trim(),
                            threads = threads.toInt(),
                            useUdp = useUdp,
                            noDtls = noDtls,
                            localPort = localPort.trim()
                        )
                    )
                    onFinish()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = serverAddress.isNotBlank() && vkLink.isNotBlank(),
                shape = MaterialTheme.shapes.large
            ) {
                Text("Завершить настройку", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
