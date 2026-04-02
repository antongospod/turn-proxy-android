@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.freeturn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.res.painterResource
import com.freeturn.app.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.data.SshConfig
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.viewmodel.MainViewModel
import com.freeturn.app.viewmodel.SshConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshSetupScreen(
    viewModel: MainViewModel,
    onConnected: () -> Unit,
    onBack: () -> Unit
) {
    val sshState by viewModel.sshState.collectAsStateWithLifecycle()
    val savedConfig by viewModel.sshConfig.collectAsStateWithLifecycle()

    var ip by rememberSaveable(savedConfig.ip) { mutableStateOf(savedConfig.ip) }
    var port by rememberSaveable(savedConfig.port) { mutableStateOf(savedConfig.port.toString()) }
    var username by rememberSaveable(savedConfig.username) { mutableStateOf(savedConfig.username) }
    var password by rememberSaveable { mutableStateOf(savedConfig.password) }
    var authType by rememberSaveable(savedConfig.authType) { mutableStateOf(savedConfig.authType) }
    var sshKey by rememberSaveable(savedConfig.sshKey) { mutableStateOf(savedConfig.sshKey) }
    var showPassword by remember { mutableStateOf(false) }
    var authDropdownExpanded by remember { mutableStateOf(false) }

    // Переходим только если подключение было установлено ПОСЛЕ открытия экрана.
    // Если sshState уже Connected при входе (пользователь хочет изменить настройки) —
    // не перенаправляем автоматически, ждём явного нажатия «Подключиться».
    var sawNonConnected by remember { mutableStateOf(sshState !is SshConnectionState.Connected) }
    LaunchedEffect(sshState) {
        if (sshState !is SshConnectionState.Connected) sawNonConnected = true
        if (sawNonConnected && sshState is SshConnectionState.Connected) onConnected()
    }

    val isConnecting = sshState is SshConnectionState.Connecting
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подключение к серверу") },
                navigationIcon = {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                        onBack()
                    }) {
                        Icon(painterResource(R.drawable.arrow_back_24px), contentDescription = "Назад")
                    }
                }
            )
        },
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

            if (!isConnecting) {
                FormSection(
                    ip = ip, onIpChange = { ip = it },
                    port = port, onPortChange = { port = it },
                    username = username, onUsernameChange = { username = it },
                    password = password, onPasswordChange = { password = it },
                    showPassword = showPassword, onTogglePassword = { showPassword = !showPassword },
                    authType = authType, onAuthTypeChange = { authType = it },
                    sshKey = sshKey, onSshKeyChange = { sshKey = it },
                    authDropdownExpanded = authDropdownExpanded,
                    onAuthDropdownChange = { authDropdownExpanded = it },
                    sshState = sshState,
                    onConnect = {
                        viewModel.connectSsh(
                            SshConfig(
                                ip = ip.trim(),
                                port = port.toIntOrNull() ?: 22,
                                username = username.trim(),
                                password = password,
                                authType = authType,
                                sshKey = sshKey
                            )
                        )
                    }
                )
            } else {
                Spacer(Modifier.height(32.dp))
                ConnectionProgressCard(step = (sshState as SshConnectionState.Connecting).step)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormSection(
    ip: String, onIpChange: (String) -> Unit,
    port: String, onPortChange: (String) -> Unit,
    username: String, onUsernameChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    showPassword: Boolean, onTogglePassword: () -> Unit,
    authType: String, onAuthTypeChange: (String) -> Unit,
    sshKey: String, onSshKeyChange: (String) -> Unit,
    authDropdownExpanded: Boolean, onAuthDropdownChange: (Boolean) -> Unit,
    sshState: SshConnectionState,
    onConnect: () -> Unit
) {
    val context = LocalContext.current
    Text("Данные сервера", style = MaterialTheme.typography.titleMedium)

    OutlinedTextField(
        value = ip,
        onValueChange = onIpChange,
        label = { Text("IP-адрес сервера") },
        placeholder = { Text("1.2.3.4") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
    )

    OutlinedTextField(
        value = port,
        onValueChange = onPortChange,
        label = { Text("SSH порт") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )

    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text("Имя пользователя") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    ExposedDropdownMenuBox(
        expanded = authDropdownExpanded,
        onExpandedChange = onAuthDropdownChange,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = if (authType == "PASSWORD") "Пароль" else "Приватный ключ",
            onValueChange = {},
            readOnly = true,
            label = { Text("Аутентификация") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = authDropdownExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = authDropdownExpanded,
            onDismissRequest = { onAuthDropdownChange(false) }
        ) {
            DropdownMenuItem(text = { Text("Пароль") },
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                    onAuthTypeChange("PASSWORD")
                    onAuthDropdownChange(false)
                })
            DropdownMenuItem(text = { Text("Приватный ключ") },
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                    onAuthTypeChange("SSH_KEY")
                    onAuthDropdownChange(false)
                })
        }
    }

    if (authType == "PASSWORD") {
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Пароль") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                    onTogglePassword()
                }) {
                    Icon(
                        painterResource(if (showPassword) R.drawable.visibility_off_24px else R.drawable.visibility_24px),
                        contentDescription = null
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
    } else {
        OutlinedTextField(
            value = sshKey,
            onValueChange = onSshKeyChange,
            label = { Text("Приватный ключ (PEM)") },
            placeholder = { Text("-----BEGIN RSA PRIVATE KEY-----\n...") },
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            maxLines = 10
        )
    }

    if (sshState is SshConnectionState.Error) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.error_24px), null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text(
                    sshState.message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    Button(
        onClick = {
            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
            onConnect()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = ip.isNotBlank() && when (authType) {
            "SSH_KEY" -> sshKey.isNotBlank()
            else -> password.isNotBlank()
        },
        shape = MaterialTheme.shapes.large
    ) {
        Text("Подключиться", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ConnectionProgressCard(step: String) {
    val steps = listOf(
        "Подключение к серверу...",
        "Аутентификация...",
        "Проверка SSH..."
    )
    val currentIndex = steps.indexOf(step).coerceAtLeast(0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())

            Text(
                text = step,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                steps.forEachIndexed { index, label ->
                    val isDone = index < currentIndex
                    val isActive = index == currentIndex
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(when {
                                isDone -> R.drawable.check_circle_24px
                                isActive -> R.drawable.radio_button_checked_24px
                                else -> R.drawable.radio_button_unchecked_24px
                            }),
                            contentDescription = null,
                            tint = when {
                                isDone -> MaterialTheme.colorScheme.primary
                                isActive -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isActive || isDone) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }
    }
}
