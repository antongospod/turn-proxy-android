package com.freeturn.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.ProxyService
import com.freeturn.app.SSHManager
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.SshConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import java.io.File

// ── SSH connection states ──────────────────────────────────────────────────
sealed class SshConnectionState {
    object Disconnected : SshConnectionState()
    data class Connecting(val step: String = "Подключение к серверу...") : SshConnectionState()
    data class Connected(val ip: String) : SshConnectionState()
    data class Error(val message: String) : SshConnectionState()
}

// ── Remote server states ───────────────────────────────────────────────────
sealed class ServerState {
    object Unknown : ServerState()
    object Checking : ServerState()
    data class Known(val installed: Boolean, val running: Boolean) : ServerState()
    data class Working(val action: String) : ServerState()
    data class Error(val message: String) : ServerState()
}

// ── Local proxy client states ──────────────────────────────────────────────
sealed class ProxyState {
    object Idle : ProxyState()
    object Starting : ProxyState()
    object Running : ProxyState()
    data class Error(val message: String) : ProxyState()
}

/** Возвращает сохранённый отпечаток хоста или null при первом подключении */
private val SshConfig.fp get() = hostFingerprint.ifEmpty { null }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val sshManager = SSHManager()

    private val _sshState = MutableStateFlow<SshConnectionState>(SshConnectionState.Disconnected)
    val sshState: StateFlow<SshConnectionState> = _sshState.asStateFlow()

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Unknown)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    private val _proxyState = MutableStateFlow<ProxyState>(ProxyState.Idle)
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    val logs: StateFlow<List<String>> = ProxyService.logs

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _customKernelExists = MutableStateFlow(false)
    val customKernelExists: StateFlow<Boolean> = _customKernelExists.asStateFlow()

    val sshConfig: StateFlow<SshConfig> = prefs.sshConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SshConfig())

    val clientConfig: StateFlow<ClientConfig> = prefs.clientConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClientConfig())

    val onboardingDone: StateFlow<Boolean> = prefs.onboardingDoneFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val proxyListen: StateFlow<String> = prefs.proxyListenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "0.0.0.0:56000")

    val proxyConnect: StateFlow<String> = prefs.proxyConnectFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "127.0.0.1:40537")

    init {
        viewModelScope.launch {
            // Wait for first real DataStore read before showing UI
            prefs.onboardingDoneFlow.first()
            _isInitialized.value = true
        }

        viewModelScope.launch {
            ProxyService.proxyFailed.collect {
                setErrorWithAutoReset("Прокси упал ${ProxyService.MAX_RESTARTS} раз — проверьте настройки")
            }
        }

        // Сбрасываем состояние когда сервис остановился (нормальный выход, не краш)
        viewModelScope.launch {
            ProxyService.isRunning.collect { running ->
                if (!running && _proxyState.value == ProxyState.Running) {
                    _proxyState.value = ProxyState.Idle
                }
            }
        }

        if (ProxyService.isRunning.value) _proxyState.value = ProxyState.Running

        _customKernelExists.value =
            File(getApplication<Application>().filesDir, "custom_vkturn").exists()
    }

    // ── SSH ────────────────────────────────────────────────────────────────

    fun connectSsh(config: SshConfig) {
        viewModelScope.launch {
            prefs.saveSshConfig(config)
        }
        sshManager.disconnect()
        _sshState.value = SshConnectionState.Connecting("Подключение к серверу...")

        viewModelScope.launch {
            delay(400)
            _sshState.value = SshConnectionState.Connecting("Аутентификация...")
            delay(300)
            _sshState.value = SshConnectionState.Connecting("Проверка SSH...")

            val result = sshManager.executeSilentCommand(
                config.ip, config.port, config.username, config.password, "echo OK",
                knownFingerprint = config.fp
            )
            if (result.trim() == "OK") {
                // При первом подключении сохраняем отпечаток хоста
                if (config.hostFingerprint.isEmpty()) {
                    sshManager.lastSeenFingerprint?.let { prefs.saveSshFingerprint(it) }
                }
                HapticUtil.perform(getApplication(), HapticUtil.Pattern.SUCCESS)
                _sshState.value = SshConnectionState.Connected(config.ip)
                checkServerState(config)
            } else {
                HapticUtil.perform(getApplication(), HapticUtil.Pattern.ERROR)
                _sshState.value = SshConnectionState.Error(result.removePrefix("ERROR: "))
            }
        }
    }

    fun reconnectSsh() {
        val cfg = sshConfig.value
        if (cfg.ip.isNotEmpty()) connectSsh(cfg)
    }

    // ── Server management ──────────────────────────────────────────────────

    fun checkServerState(config: SshConfig? = null) {
        val cfg = config ?: sshConfig.value
        if (cfg.ip.isEmpty()) return
        _serverState.value = ServerState.Checking

        val checkCmd = """
            if ls /opt/vk-turn/server-linux-* >/dev/null 2>&1; then echo "INSTALLED:YES"; else echo "INSTALLED:NO"; fi
            if ps aux | grep -v grep | grep -q "server-linux-"; then echo "RUNNING:YES"; else echo "RUNNING:NO"; fi
        """.trimIndent()

        viewModelScope.launch {
            val result = sshManager.executeSilentCommand(cfg.ip, cfg.port, cfg.username, cfg.password, checkCmd,
                knownFingerprint = cfg.fp)
            _serverState.value = if (result.startsWith("ERROR")) {
                ServerState.Error(result.removePrefix("ERROR: "))
            } else {
                val known = ServerState.Known(
                    installed = result.contains("INSTALLED:YES"),
                    running = result.contains("RUNNING:YES")
                )
                if (known.installed) fetchServerVersion(cfg)
                known
            }
        }
    }

    fun fetchServerVersion(config: SshConfig? = null) {
        val cfg = config ?: sshConfig.value
        if (cfg.ip.isEmpty()) return
        val cmd = """
            cd /opt/vk-turn 2>/dev/null &&
            ARCH=${'$'}(uname -m);
            if [ "${'$'}ARCH" = "x86_64" ]; then BIN="./server-linux-amd64"; else BIN="./server-linux-arm64"; fi;
            timeout 2 ${'$'}BIN -version 2>&1 | head -1
        """.trimIndent()
        viewModelScope.launch {
            val out = sshManager.executeSilentCommand(cfg.ip, cfg.port, cfg.username, cfg.password, cmd,
                knownFingerprint = cfg.fp)
            _serverVersion.value = out.trim().takeIf { it.isNotEmpty() && !it.startsWith("ERROR") }
        }
    }

    fun installServer() {
        val cfg = sshConfig.value
        if (cfg.ip.isEmpty()) return
        _serverState.value = ServerState.Working("Установка vk-turn-proxy...")

        val script = """
            mkdir -p /opt/vk-turn && cd /opt/vk-turn &&
            pkill -9 -f "server-linux-" 2>/dev/null;
            ARCH=${'$'}(uname -m);
            if [ "${'$'}ARCH" = "x86_64" ]; then BIN="server-linux-amd64"; else BIN="server-linux-arm64"; fi;
            wget -qO ${'$'}BIN https://github.com/cacggghp/vk-turn-proxy/releases/latest/download/${'$'}BIN &&
            chmod +x ${'$'}BIN && echo "DONE"
        """.trimIndent()

        viewModelScope.launch {
            sshManager.executeSilentCommand(cfg.ip, cfg.port, cfg.username, cfg.password, script,
                knownFingerprint = cfg.fp)
            delay(1000)
            checkServerState(cfg)
        }
    }

    fun startServer() {
        val cfg = sshConfig.value
        if (cfg.ip.isEmpty()) return
        _serverState.value = ServerState.Working("Запуск сервера...")

        val l = proxyListen.value
        val c = proxyConnect.value
        val script = """
            cd /opt/vk-turn &&
            ARCH=${'$'}(uname -m);
            if [ "${'$'}ARCH" = "x86_64" ]; then BIN="server-linux-amd64"; else BIN="server-linux-arm64"; fi;
            nohup ./${'$'}BIN -listen $l -connect $c > server.log 2>&1 &
            echo ${'$'}! > proxy.pid && echo "STARTED"
        """.trimIndent()

        viewModelScope.launch {
            sshManager.executeSilentCommand(cfg.ip, cfg.port, cfg.username, cfg.password, script,
                knownFingerprint = cfg.fp)
            delay(2000)
            checkServerState(cfg)
        }
    }

    fun stopServer() {
        val cfg = sshConfig.value
        if (cfg.ip.isEmpty()) return
        _serverState.value = ServerState.Working("Остановка сервера...")

        val script = """
            cd /opt/vk-turn &&
            if [ -f proxy.pid ]; then kill -9 ${'$'}(cat proxy.pid) 2>/dev/null; rm -f proxy.pid; fi;
            pkill -9 -f "server-linux-" 2>/dev/null;
            echo "STOPPED"
        """.trimIndent()

        viewModelScope.launch {
            sshManager.executeSilentCommand(cfg.ip, cfg.port, cfg.username, cfg.password, script,
                knownFingerprint = cfg.fp)
            delay(2000)
            checkServerState(cfg)
        }
    }

    // ── Local proxy ────────────────────────────────────────────────────────

    fun startProxy(context: Context) {
        if (ProxyService.isRunning.value) return

        // Сброс предыдущей ошибки перед повторной попыткой
        if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle

        // Валидация конфига до запуска
        val cfg = clientConfig.value
        if (!cfg.isRawMode && (cfg.serverAddress.isBlank() || cfg.vkLink.isBlank())) {
            setErrorWithAutoReset("Не заполнены настройки клиента")
            return
        }
        if (cfg.isRawMode && cfg.rawCommand.isBlank()) {
            setErrorWithAutoReset("Не задана raw-команда")
            return
        }

        _proxyState.value = ProxyState.Starting

        viewModelScope.launch {
            // Write to legacy SharedPreferences so ProxyService can read them
            context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE).edit {
                putString("peer", cfg.serverAddress)
                putString("link", cfg.vkLink)
                putString("n", cfg.threads.toString())
                putBoolean("udp", cfg.useUdp)
                putBoolean("noDtls", cfg.noDtls)
                putString("listen", cfg.localPort)
                putBoolean("isRaw", cfg.isRawMode)
                putString("rawCmd", cfg.rawCommand)
            }

            ProxyService.logs.value = emptyList()
            context.startForegroundService(Intent(context, ProxyService::class.java))

            // Ждём запуска: проверяем логи на ошибку или успех вместо простого isRunning
            delay(2500)
            // Если proxyFailed уже обработал ошибку — не дублируем
            if (_proxyState.value is ProxyState.Error) return@launch
            val logText = ProxyService.logs.value.joinToString("\n").lowercase()
            when {
                !ProxyService.isRunning.value ->
                    setErrorWithAutoReset("Прокси не запустился")
                logText.contains("panic") || logText.contains("fatal") ||
                logText.contains("rate limit") || logText.contains("критическая") -> {
                    context.stopService(Intent(context, ProxyService::class.java))
                    val errorLine = ProxyService.logs.value
                        .lastOrNull { line ->
                            val l = line.lowercase()
                            l.contains("error") || l.contains("ошибка") ||
                            l.contains("panic") || l.contains("fatal") ||
                            l.contains("критическая")
                        }
                    setErrorWithAutoReset(errorLine ?: "Ошибка запуска — проверьте настройки")
                }
                else ->
                    _proxyState.value = ProxyState.Running
            }
        }
    }

    private fun setErrorWithAutoReset(message: String) {
        HapticUtil.perform(getApplication(), HapticUtil.Pattern.ERROR)
        _proxyState.value = ProxyState.Error(message)
        viewModelScope.launch {
            delay(3500)
            if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle
        }
    }

    fun stopProxy(context: Context) {
        context.stopService(Intent(context, ProxyService::class.java))
        _proxyState.value = ProxyState.Idle
    }

    // ── Preferences ────────────────────────────────────────────────────────

    fun saveClientConfig(config: ClientConfig) {
        viewModelScope.launch { prefs.saveClientConfig(config) }
    }

    fun saveProxyServerConfig(listen: String, connect: String) {
        viewModelScope.launch { prefs.saveProxyConfig(listen, connect) }
    }

    fun setOnboardingDone() {
        viewModelScope.launch { prefs.setOnboardingDone(true) }
    }

    fun clearLogs() {
        ProxyService.logs.value = emptyList()
    }

    // ── Custom kernel ──────────────────────────────────────────────────────

    fun setCustomKernel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val dest = File(app.filesDir, "custom_vkturn")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.setExecutable(true)
                withContext(Dispatchers.Main) { _customKernelExists.value = true }
                ProxyService.addLog("Кастомное ядро установлено: ${dest.length() / 1024} KB")
            } catch (e: Exception) {
                ProxyService.addLog("Ошибка установки ядра: ${e.message}")
            }
        }
    }

    fun clearCustomKernel() {
        File(getApplication<Application>().filesDir, "custom_vkturn").delete()
        _customKernelExists.value = false
        ProxyService.addLog("Кастомное ядро удалено, используется встроенное")
    }

    override fun onCleared() {
        super.onCleared()
        sshManager.disconnect()
    }
}
