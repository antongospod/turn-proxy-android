package com.freeturn.app.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.freeturn.app.ProxyService
import com.freeturn.app.ProxyServiceState
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
import kotlinx.coroutines.cancel
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
        ProxyServiceState.proxyFailed.collect {
            setErrorWithAutoReset("Прокси упал ${ProxyService.MAX_RESTARTS} раз — проверьте настройки")
        }
    }

    suspend fun observeCaptchaEvents() {
        ProxyServiceState.captchaSession.collect { session ->
            if (session != null) {
                _proxyState.value = ProxyState.CaptchaRequired(session.url, session.sessionId)
            } else if (_proxyState.value is ProxyState.CaptchaRequired) {
                // Капча-сессия закрыта — возвращаемся в Running (ядро продолжает работу)
                _proxyState.value = ProxyState.Running
            }
        }
    }

    suspend fun observeProxyServiceStatus() {
        ProxyServiceState.isRunning.collect { running ->
            if (running && _proxyState.value !is ProxyState.Running && _proxyState.value !is ProxyState.Starting) {
                // Сервис запущен внешним источником (например, ProxyReceiver)
                _proxyState.value = ProxyState.Running
            } else if (!running && _proxyState.value is ProxyState.Running) {
                _proxyState.value = ProxyState.Idle
            }
        }
    }

    fun syncInitialState() {
        if (ProxyServiceState.isRunning.value) _proxyState.value = ProxyState.Running
    }

    suspend fun startProxy(cfg: ClientConfig) {
        if (ProxyServiceState.isRunning.value) return
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

        ProxyServiceState.clearLogs()
        ProxyServiceState.setStartupResult(null)
        context.startForegroundService(Intent(context, ProxyService::class.java))

        val result = withTimeoutOrNull(5_000L) {
            ProxyServiceState.startupResult.filterNotNull().first()
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

    fun dismissCaptcha() {
        ProxyServiceState.setCaptchaSession(null)
        if (_proxyState.value is ProxyState.CaptchaRequired) {
            _proxyState.value = ProxyState.Running
        }
    }

    fun setErrorWithAutoReset(message: String) {
        resetJob?.cancel()
        _proxyState.value = ProxyState.Error(message)
        resetJob = scope.launch {
            delay(4_000)
            if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle
        }
    }

    suspend fun setCustomKernel(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val MAX_SIZE = 100L * 1024 * 1024 // 100 MB
            val ELF_MAGIC = byteArrayOf(0x7F, 0x45, 0x4C, 0x46) // \x7FELF

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext "Не удалось открыть файл"

            // Читаем первые 4 байта для проверки ELF-магии
            val header = ByteArray(4)
            val headerRead = inputStream.read(header)
            inputStream.close()

            if (headerRead < 4 || !header.contentEquals(ELF_MAGIC)) {
                return@withContext "Файл не является ELF-бинарником. Убедитесь, что загружаете правильное ядро"
            }

            // Копируем файл
            val dest = File(context.filesDir, "custom_vkturn")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }

            if (dest.length() == 0L) {
                dest.delete()
                return@withContext "Файл пустой"
            }
            if (dest.length() > MAX_SIZE) {
                dest.delete()
                return@withContext "Файл слишком большой (максимум 100 МБ)"
            }

            dest.setExecutable(true, false)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath)).waitFor()
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) { _customKernelExists.value = true }
            ProxyServiceState.addLog("Кастомное ядро установлено: ${dest.length() / 1024} КБ")
            null
        } catch (e: Exception) {
            ProxyServiceState.addLog("Ошибка установки ядра: ${e.message}")
            "Ошибка: ${e.message}"
        }
    }

    fun clearCustomKernel() {
        File(context.filesDir, "custom_vkturn").delete()
        _customKernelExists.value = false
        ProxyServiceState.addLog("Кастомное ядро удалено, используется встроенное")
    }

    fun clearState() {
        _proxyState.value = ProxyState.Idle
        File(context.filesDir, "custom_vkturn").delete()
        _customKernelExists.value = false
    }

    fun destroy() {
        scope.cancel()
    }
}
