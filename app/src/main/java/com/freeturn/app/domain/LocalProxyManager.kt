package com.freeturn.app.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.freeturn.app.ProxyService
import com.freeturn.app.StartupResult
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.viewmodel.ProxyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class LocalProxyManager(private val context: Context) {

    private val _proxyState = MutableStateFlow<ProxyState>(ProxyState.Idle)
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    private val _customKernelExists = MutableStateFlow(false)
    val customKernelExists: StateFlow<Boolean> = _customKernelExists.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var resetJob: kotlinx.coroutines.Job? = null

    init {
        _customKernelExists.value = File(context.filesDir, "custom_vkturn").exists()
    }

    suspend fun observeProxyLifecycle() {
        ProxyService.proxyFailed.collect {
            setErrorWithAutoReset("Прокси упал ${ProxyService.MAX_RESTARTS} раз — проверьте настройки")
        }
    }

    suspend fun observeProxyServiceStatus() {
        ProxyService.isRunning.collect { running ->
            if (running && _proxyState.value !is ProxyState.Running && _proxyState.value !is ProxyState.Starting) {
                // Сервис запущен внешним источником (например, ProxyReceiver)
                _proxyState.value = ProxyState.Running
            } else if (!running && _proxyState.value is ProxyState.Running) {
                _proxyState.value = ProxyState.Idle
            }
        }
    }

    fun syncInitialState() {
        if (ProxyService.isRunning.value) _proxyState.value = ProxyState.Running
    }

    suspend fun startProxy(cfg: ClientConfig) {
        if (ProxyService.isRunning.value) return
        if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle

        if (!cfg.isRawMode && (cfg.serverAddress.isBlank() || cfg.vkLink.isBlank())) {
            setErrorWithAutoReset("Не заполнены настройки клиента")
            return
        }
        if (cfg.isRawMode && cfg.rawCommand.isBlank()) {
            setErrorWithAutoReset("Не задана raw-команда")
            return
        }

        _proxyState.value = ProxyState.Starting

        ProxyService.clearLogs()
        ProxyService.startupResult.value = null
        context.startForegroundService(Intent(context, ProxyService::class.java))

        val result = withTimeoutOrNull(5_000L) {
            ProxyService.startupResult.filterNotNull().first()
        }

        if (_proxyState.value is ProxyState.Error) return

        when (result) {
            null -> setErrorWithAutoReset("Прокси не запустился")
            is StartupResult.Failed -> {
                stopProxy()
                setErrorWithAutoReset(result.message)
            }
            is StartupResult.Success -> _proxyState.value = ProxyState.Running
        }
    }

    fun stopProxy() {
        context.stopService(Intent(context, ProxyService::class.java))
        _proxyState.value = ProxyState.Idle
    }

    fun setErrorWithAutoReset(message: String) {
        resetJob?.cancel()
        _proxyState.value = ProxyState.Error(message)
        resetJob = scope.launch {
            delay(4_000)
            if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle
        }
    }

    suspend fun setCustomKernel(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val dest = File(context.filesDir, "custom_vkturn")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setExecutable(true)
            withContext(Dispatchers.Main) { _customKernelExists.value = true }
            ProxyService.addLog("Кастомное ядро установлено: ${dest.length() / 1024} KB")
        } catch (e: Exception) {
            ProxyService.addLog("Ошибка установки ядра: ${e.message}")
        }
    }

    fun clearCustomKernel() {
        File(context.filesDir, "custom_vkturn").delete()
        _customKernelExists.value = false
        ProxyService.addLog("Кастомное ядро удалено, используется встроенное")
    }

    fun clearState() {
        _proxyState.value = ProxyState.Idle
        _customKernelExists.value = false
    }
}
