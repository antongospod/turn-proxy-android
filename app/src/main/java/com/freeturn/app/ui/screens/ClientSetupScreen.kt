@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.viewmodel.MainViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * @param showFinishButton  true — онбординг-флоу, показываем кнопку «Завершить».
 *                          false — вкладка, авто-сохранение без кнопки.
 * @param onFinish  вызывается после нажатия кнопки «Завершить» (только если showFinishButton=true).
 */
@Composable
fun ClientSetupScreen(
    viewModel: MainViewModel,
    showFinishButton: Boolean = false,
    onFinish: (() -> Unit)? = null
) {
    val saved by viewModel.clientConfig.collectAsStateWithLifecycle()
    val sshConfig by viewModel.sshConfig.collectAsStateWithLifecycle()
    val proxyListen by viewModel.proxyListen.collectAsStateWithLifecycle()
    val customKernelExists by viewModel.customKernelExists.collectAsStateWithLifecycle()
    val kernelError by viewModel.kernelError.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val kernelPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.setCustomKernel(it) } }

    var serverAddress by rememberSaveable(saved.serverAddress) { mutableStateOf(saved.serverAddress) }
    var vkLink       by rememberSaveable(saved.vkLink)         { mutableStateOf(saved.vkLink) }
    var threads      by rememberSaveable(saved.threads)        { mutableFloatStateOf(saved.threads.toFloat()) }
    var useUdp       by rememberSaveable(saved.useUdp)         { mutableStateOf(saved.useUdp) }
    var noDtls       by rememberSaveable(saved.noDtls)         { mutableStateOf(saved.noDtls) }
    var manualCaptcha by rememberSaveable(saved.manualCaptcha) { mutableStateOf(saved.manualCaptcha) }
    var localPort    by rememberSaveable(saved.localPort)      { mutableStateOf(saved.localPort) }
    var lastSliderInt by rememberSaveable { mutableIntStateOf(saved.threads) }

    // Автозаполнение адреса сервера из SSH-конфига если поле пустое
    LaunchedEffect(sshConfig.ip, proxyListen) {
        if (serverAddress.isBlank() && sshConfig.ip.isNotBlank()) {
            val port = proxyListen.substringAfterLast(":", "56000")
            serverAddress = "${sshConfig.ip}:$port"
        }
    }

    // Авто-сохранение с дебаунсом 600 мс на каждое изменение поля.
    // vlessMode исключён — сохраняется через setVlessMode с автоперезапуском сервера.
    LaunchedEffect(serverAddress, vkLink, threads, useUdp, noDtls, manualCaptcha, localPort) {
        delay(600)
        viewModel.saveClientConfig(
            ClientConfig(
                serverAddress = serverAddress.trim(),
                vkLink        = vkLink.trim(),
                threads       = threads.roundToInt(),
                useUdp        = useUdp,
                noDtls        = noDtls,
                manualCaptcha = manualCaptcha,
                localPort     = localPort.trim(),
                vlessMode     = saved.vlessMode
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.client_title)) })
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

            // Подключение
            Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = serverAddress.redact(privacyMode),
                onValueChange = { if (!privacyMode) serverAddress = it },
                label = { Text(stringResource(R.string.server_address_label)) },
                placeholder = { Text(stringResource(R.string.server_address_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = privacyMode,
                supportingText = { Text(stringResource(R.string.server_address_support)) }
            )

            OutlinedTextField(
                value = vkLink.redact(privacyMode),
                onValueChange = { if (!privacyMode) vkLink = it },
                label = { Text(stringResource(R.string.vk_link_label)) },
                placeholder = { Text(stringResource(R.string.vk_link_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = privacyMode,
                supportingText = { Text(stringResource(R.string.vk_link_support)) }
            )

            OutlinedTextField(
                value = localPort.redact(privacyMode),
                onValueChange = { if (!privacyMode) localPort = it },
                label = { Text(stringResource(R.string.local_listen_address)) },
                placeholder = { Text(stringResource(R.string.local_listen_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = privacyMode,
                supportingText = { Text(stringResource(R.string.local_listen_support)) }
            )

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            // Параметры
            Text(stringResource(R.string.parameters_title), style = MaterialTheme.typography.titleMedium)

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.threads_format, threads.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.threads_recommendation),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                Slider(
                    value = threads,
                    onValueChange = {
                        val newInt = it.roundToInt()
                        if (newInt != lastSliderInt) {
                            HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                            lastSliderInt = newInt
                        }
                        threads = it
                    },
                    valueRange = 1f..8f,
                    steps = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SwitchRow(
                label = stringResource(R.string.vless_mode),
                description = stringResource(R.string.vless_mode_desc),
                checked = saved.vlessMode,
                onCheckedChange = {
                    HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                    viewModel.setVlessMode(it)
                }
            )

            if (!saved.vlessMode) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(stringResource(R.string.transport_protocol), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.transport_protocol_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !useUdp,
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                useUdp = false
                                noDtls = false
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text(stringResource(R.string.tcp)) }
                        SegmentedButton(
                            selected = useUdp,
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                useUdp = true
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text(stringResource(R.string.udp)) }
                    }
                }

                if (useUdp) {
                    SwitchRow(
                        label = stringResource(R.string.no_dtls),
                        description = stringResource(R.string.no_dtls_desc),
                        checked = noDtls,
                        onCheckedChange = {
                            HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                            noDtls = it
                        }
                    )
                }
            }

            SwitchRow(
                label = stringResource(R.string.manual_captcha),
                description = stringResource(R.string.manual_captcha_desc),
                checked = manualCaptcha,
                onCheckedChange = {
                    HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                    manualCaptcha = it
                }
            )

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            // Ядро
            Text(stringResource(R.string.core_title), style = MaterialTheme.typography.titleMedium)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (customKernelExists)
                        MaterialTheme.colorScheme.secondaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            painter = painterResource(
                                if (customKernelExists) R.drawable.check_circle_24px
                                else R.drawable.memory_24px
                            ),
                            contentDescription = null,
                            tint = if (customKernelExists)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (customKernelExists) stringResource(R.string.custom_core)
                                else stringResource(R.string.builtin_core),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                if (customKernelExists) stringResource(R.string.loaded_from_memory)
                                else stringResource(R.string.from_apk),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                    if (customKernelExists) {
                        IconButton(onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.clearCustomKernel()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.delete_24px),
                                contentDescription = stringResource(R.string.reset),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        FilledTonalButton(onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.clearKernelError()
                            kernelPickerLauncher.launch(arrayOf("*/*"))
                        }) {
                            Text(stringResource(R.string.btn_load))
                        }
                    }
                }
            }

            if (kernelError != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.error_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        kernelError!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Кнопка «Завершить» — только в онбординг-флоу
            if (showFinishButton && onFinish != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onFinish,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = serverAddress.isNotBlank() && vkLink.isNotBlank(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(stringResource(R.string.finish_setup), style = MaterialTheme.typography.labelLarge)
                }
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
