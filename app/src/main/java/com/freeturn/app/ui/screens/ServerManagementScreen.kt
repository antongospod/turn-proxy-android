@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.freeturn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.freeturn.app.R
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.ui.theme.StatusBlue
import com.freeturn.app.ui.theme.StatusGreen
import com.freeturn.app.viewmodel.MainViewModel
import com.freeturn.app.viewmodel.ServerState
import com.freeturn.app.viewmodel.SshConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerManagementScreen(
    viewModel: MainViewModel,
    onContinue: () -> Unit
) {
    val sshState by viewModel.sshState.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val sshConfig by viewModel.sshConfig.collectAsStateWithLifecycle()
    val savedListen by viewModel.proxyListen.collectAsStateWithLifecycle()
    val savedConnect by viewModel.proxyConnect.collectAsStateWithLifecycle()
    val sshLog by viewModel.sshLog.collectAsStateWithLifecycle()

    var proxyListenPort by rememberSaveable(savedListen) { mutableStateOf(savedListen.substringAfterLast(":", "56000")) }
    var proxyConnect by rememberSaveable(savedConnect) { mutableStateOf(savedConnect) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val isConnected = sshState is SshConnectionState.Connected
    val isWorking = serverState is ServerState.Working || serverState is ServerState.Checking
    val serverKnown = serverState as? ServerState.Known

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server)) },
                actions = {
                    SshStatusBadge(sshState = sshState, ip = sshConfig.ip)
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Status card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.server_status), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    StatusRow(
                        label = stringResource(R.string.ssh_connection),
                        isActive = isConnected,
                        activeLabel = stringResource(R.string.paired),
                        activeColor = StatusBlue
                    )
                    Spacer(Modifier.height(10.dp))
                    StatusRow(stringResource(R.string.vk_turn_proxy), serverKnown?.running == true)

                    when (serverState) {
                        is ServerState.Checking -> {
                            Spacer(Modifier.height(12.dp))
                            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is ServerState.Working -> {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                (serverState as ServerState.Working).action,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is ServerState.Error -> {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.error_format, (serverState as ServerState.Error).message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }
                }
            }

            // Server config
            Text(stringResource(R.string.server_config), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = proxyListenPort,
                onValueChange = { proxyListenPort = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.listen_port)) },
                placeholder = { Text(stringResource(R.string.listen_port_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(stringResource(R.string.listen_port_desc)) }
            )

            OutlinedTextField(
                value = proxyConnect,
                onValueChange = { proxyConnect = it },
                label = { Text(stringResource(R.string.turn_client_address)) },
                placeholder = { Text(stringResource(R.string.turn_client_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(stringResource(R.string.turn_client_desc)) }
            )

            // Action buttons
            FilledTonalButton(
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    viewModel.saveProxyServerConfig("0.0.0.0:$proxyListenPort", proxyConnect)
                    viewModel.installServer()
                },
                enabled = isConnected && !isWorking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(painterResource(R.drawable.cloud_download_24px), null)
                Spacer(Modifier.width(8.dp))
                Text(if (serverKnown?.installed == true) stringResource(R.string.update) else stringResource(R.string.install))
            }

            Button(
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    viewModel.saveProxyServerConfig("0.0.0.0:$proxyListenPort", proxyConnect)
                    viewModel.startServer()
                },
                enabled = (isConnected && !isWorking
                        && serverKnown?.installed == true) && !serverKnown.running,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(painterResource(R.drawable.play_arrow_24px), null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start_server))
            }

            OutlinedButton(
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    viewModel.stopServer()
                },
                enabled = isConnected && !isWorking && serverKnown?.running == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(painterResource(R.drawable.stop_24px), null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.stop_server))
            }

            if (serverKnown?.running == true) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onContinue()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(stringResource(R.string.continue_client_setup))
                    Spacer(Modifier.width(8.dp))
                    Icon(painterResource(R.drawable.arrow_forward_24px), null)
                }
            }

            // ── SSH-лог (вывод всех команд) ────────────────────────────────
            if (sshLog.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.ssh_log_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        val listState = rememberLazyListState()
                        LaunchedEffect(sshLog.size) {
                            if (sshLog.isNotEmpty()) listState.animateScrollToItem(sshLog.lastIndex)
                        }
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp)
                                    .padding(10.dp)
                            ) {
                                items(sshLog) { line ->
                                    val isHeader = line.startsWith("===")
                                    val isError = line.contains("ERROR", ignoreCase = true) ||
                                                  line.contains("error", ignoreCase = true) ||
                                                  line.contains("failed", ignoreCase = true)
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = when {
                                            isHeader -> MaterialTheme.colorScheme.primary
                                            isError  -> MaterialTheme.colorScheme.error
                                            else     -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SshStatusBadge(sshState: SshConnectionState, ip: String) {
    val connected = sshState is SshConnectionState.Connected
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (connected) StatusBlue else MaterialTheme.colorScheme.error, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (connected) ip else stringResource(R.string.not_connected),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    isActive: Boolean,
    activeLabel: String = stringResource(R.string.active),
    activeColor: Color = StatusGreen
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (isActive) activeColor else MaterialTheme.colorScheme.outline,
                        CircleShape
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (isActive) activeLabel else stringResource(R.string.inactive),
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) activeColor else MaterialTheme.colorScheme.outline
            )
        }
    }
}
