package com.freeturn.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.ProxyService
import com.freeturn.app.SSHManager
import com.freeturn.app.StartupResult
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

/** Возвращает приватный ключ если выбран key-режим аутентификации, иначе пустую строку */
private val SshConfig.key get() = if (authType == "SSH_KEY") sshKey else ""

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val sshManager = SSHManager()

    /**
     * Конфиг, с которым было выполнено последнее успешное SSH-подключение.
     * Хранит пароль в памяти, не зависит от асинхронного чтения EncryptedSharedPreferences.
     * Все операции с сервером используют его вместо sshConfig.value.
     */
    @Volatile private var activeSshConfig: SshConfig? = null

    private val _sshState = MutableStateFlow<SshConnectionState>(SshConnectionState.Disconnected)
    val sshState: StateFlow<SshConnectionState> = _sshState.asStateFlow()

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Unknown)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    private val _serverLogs = MutableStateFlow<String?>(null)
    val serverLogs: StateFlow<String?> = _serverLogs.asStateFlow()

    // Накопительный лог всех SSH-операций (команда + вывод)
    private val _sshLog = MutableStateFlow<List<String>>(emptyList())
    val sshLog: StateFlow<List<String>> = _sshLog.asStateFlow()

    private fun appendSshLog(vararg lines: String) {
        _sshLog.update { current ->
            var next = current + lines
            if (next.size > 500) next = next.drop(next.size - 500)
            next
        }
    }

    private fun logCommand(target: String, cmd: String) {
        appendSshLog("  ssh $target")
        cmd.lines().filter { it.isNotBlank() }.forEach { appendSshLog("  \$ $it") }
        appendSshLog("  ---")
    }

    // P2-2: единый форматтер времени (DateTimeFormatter — thread-safe, не требует Locale)
    private val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    private fun timestamp(): String = java.time.LocalTime.now().format(timeFormatter)

    // P2-2: обобщённая SSH-операция — логирование + выполнение + вывод в SSH-лог
    private suspend fun runSshCommand(cfg: SshConfig, label: String, cmd: String): String {
        appendSshLog("", "=== $label [${timestamp()}] ===")
        logCommand("${cfg.username}@${cfg.ip}:${cfg.port}", cmd)
        val result = sshManager.executeSilentCommand(
            cfg.ip, cfg.port, cfg.username, cfg.password, cmd,
            knownFingerprint = cfg.fp, sshKey = cfg.key
        )
        result.lines().filter { it.isNotBlank() }.forEach { appendSshLog(it) }
        return result
    }

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
        _sshState.value = SshConnectionState.Connecting()

        viewModelScope.launch {
            val result = runSshCommand(config, "Подключение", "echo OK")
            if (result.trim() == "OK") {
                val fp = sshManager.lastSeenFingerprint ?: config.hostFingerprint
                if (config.hostFingerprint.isEmpty()) {
                    sshManager.lastSeenFingerprint?.let { prefs.saveSshFingerprint(it) }
                }
                activeSshConfig = config.copy(hostFingerprint = fp)
                HapticUtil.perform(getApplication(), HapticUtil.Pattern.SUCCESS)
                _sshState.value = SshConnectionState.Connected(config.ip)
                checkServerState(config)
            } else {
                HapticUtil.perform(getApplication(), HapticUtil.Pattern.ERROR)
                _sshState.value = SshConnectionState.Error(result.removePrefix("ERROR: "))
            }
        }
    }

    fun disconnectSsh() {
        activeSshConfig = null
        _sshState.value = SshConnectionState.Disconnected
        _serverState.value = ServerState.Unknown
        _serverVersion.value = null
    }

    fun reconnectSsh() {
        viewModelScope.launch {
            val cfg = activeSshConfig
                ?: sshConfig.value.takeIf { it.ip.isNotEmpty() }
                ?: prefs.sshConfigFlow.first()
            if (cfg.ip.isNotEmpty()) connectSsh(cfg)
        }
    }

    // ── Server management ──────────────────────────────────────────────────

    fun checkServerState(config: SshConfig? = null) {
        val cfg = config ?: activeSshConfig ?: sshConfig.value
        // P2-4: сброс в Unknown вместо зависания в Checking при пустом IP
        if (cfg.ip.isEmpty()) {
            _serverState.value = ServerState.Unknown
            return
        }
        _serverState.value = ServerState.Checking

        val checkCmd = """
            if ls /opt/vk-turn/server-linux-* >/dev/null 2>&1; then echo "INSTALLED:YES"; else echo "INSTALLED:NO"; fi
            if ps aux | grep -v grep | grep -q "server-linux-"; then echo "RUNNING:YES"; else echo "RUNNING:NO"; fi
        """.trimIndent()

        viewModelScope.launch {
            val result = runSshCommand(cfg, "Проверка состояния", checkCmd)
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
        val cfg = config ?: activeSshConfig ?: sshConfig.value
        if (cfg.ip.isEmpty()) return
        val cmd = """
            cd /opt/vk-turn 2>/dev/null &&
            ARCH=${'$'}(uname -m);
            if [ "${'$'}ARCH" = "x86_64" ]; then BIN="./server-linux-amd64"; else BIN="./server-linux-arm64"; fi;
            timeout 2 ${'$'}BIN -version 2>&1 | head -1
        """.trimIndent()
        viewModelScope.launch {
            val out = sshManager.executeSilentCommand(cfg.ip, cfg.port, cfg.username, cfg.password, cmd,
                knownFingerprint = cfg.fp, sshKey = cfg.key)
            _serverVersion.value = out.trim().takeIf { it.isNotEmpty() && !it.startsWith("ERROR") }
        }
    }

    fun installServer() {
        val cfg = activeSshConfig ?: sshConfig.value
        if (cfg.ip.isEmpty()) return
        _serverState.value = ServerState.Working("Установка vk-turn-proxy...")

        val script = """
            mkdir -p /opt/vk-turn && cd /opt/vk-turn
            if [ -f /opt/vk-turn/proxy.pid ]; then kill -9 ${'$'}(cat /opt/vk-turn/proxy.pid) 2>/dev/null; rm -f /opt/vk-turn/proxy.pid; fi
            ARCH=${'$'}(uname -m)
            if [ "${'$'}ARCH" = "x86_64" ]; then BIN="server-linux-amd64"; else BIN="server-linux-arm64"; fi
            BASE_URL="https://github.com/cacggghp/vk-turn-proxy/releases/latest/download"
            echo "Arch: ${'$'}ARCH | Binary: ${'$'}BIN"
            _dl() { URL=${'$'}1; OUT=${'$'}2
                if command -v curl >/dev/null 2>&1; then
                    curl -sSL -o "${'$'}OUT" "${'$'}URL" 2>&1
                elif command -v wget >/dev/null 2>&1; then
                    wget -q -O "${'$'}OUT" "${'$'}URL" 2>&1
                else
                    echo "ERROR: curl и wget не найдены"; return 1
                fi
            }
            _dl "${'$'}BASE_URL/${'$'}BIN" "${'$'}BIN" || { echo "ERROR: скачивание бинарника не удалось"; exit 1; }
            SIZE=${'$'}(wc -c < "${'$'}BIN" 2>/dev/null || echo 0)
            echo "Size: ${'$'}SIZE bytes"
            if [ "${'$'}SIZE" -lt 100000 ]; then echo "ERROR: файл слишком мал (${'$'}SIZE байт)"; cat "${'$'}BIN" 2>/dev/null; exit 1; fi
            if _dl "${'$'}BASE_URL/checksums.txt" checksums.txt && [ -s checksums.txt ]; then
                EXPECTED=${'$'}(grep "${'$'}BIN" checksums.txt | awk '{print ${'$'}1}')
                if [ -n "${'$'}EXPECTED" ]; then
                    ACTUAL=${'$'}(sha256sum "${'$'}BIN" | awk '{print ${'$'}1}')
                    if [ "${'$'}EXPECTED" = "${'$'}ACTUAL" ]; then
                        echo "SHA256: OK"
                    else
                        echo "ERROR: SHA256 не совпадает — ожидался ${'$'}EXPECTED, получен ${'$'}ACTUAL"
                        rm -f "${'$'}BIN" checksums.txt; exit 1
                    fi
                else
                    echo "WARN: ${'$'}BIN не найден в checksums.txt, SHA256 пропущен"
                fi
                rm -f checksums.txt
            else
                echo "WARN: checksums.txt недоступен, SHA256 не проверен"
            fi
            chmod +x "${'$'}BIN" && echo "DONE"
        """.trimIndent()

        viewModelScope.launch {
            val result = runSshCommand(cfg, "Установка", script)
            if (!result.contains("DONE")) {
                val errorMsg = result.lines()
                    .filter { it.isNotBlank() }
                    .takeLast(4)
                    .joinToString("\n")
                    .ifBlank { "Нет вывода от команды — см. SSH-лог" }
                _serverState.value = ServerState.Error(errorMsg)
            } else {
                delay(500)
                checkServerState(cfg)
            }
        }
    }

    private fun isValidHostPort(s: String): Boolean =
        s.matches(Regex("""^[\w.\-]+:\d{1,5}$"""))

    fun startServer() {
        val cfg = activeSshConfig ?: sshConfig.value
        if (cfg.ip.isEmpty()) return

        val l = proxyListen.value
        val c = proxyConnect.value
        if (!isValidHostPort(l) || !isValidHostPort(c)) {
            _serverState.value = ServerState.Error("Неверный формат адреса (ожидается host:port)")
            return
        }
        _serverState.value = ServerState.Working("Запуск сервера...")

        val script = """
            cd /opt/vk-turn &&
            ARCH=${'$'}(uname -m);
            if [ "${'$'}ARCH" = "x86_64" ]; then BIN="server-linux-amd64"; else BIN="server-linux-arm64"; fi;
            nohup ./${'$'}BIN -listen $l -connect $c > server.log 2>&1 &
            echo ${'$'}! > proxy.pid && echo "STARTED"
        """.trimIndent()

        viewModelScope.launch {
            runSshCommand(cfg, "Запуск", script)
            delay(2000)
            checkServerState(cfg)
        }
    }

    fun stopServer() {
        val cfg = activeSshConfig ?: sshConfig.value
        if (cfg.ip.isEmpty()) return
        _serverState.value = ServerState.Working("Остановка сервера...")

        val script = """
            cd /opt/vk-turn &&
            if [ -f proxy.pid ]; then kill -9 ${'$'}(cat proxy.pid) 2>/dev/null; rm -f proxy.pid; fi;
            pkill -9 -f "[s]erver-linux-" 2>/dev/null;
            echo "STOPPED"
        """.trimIndent()

        viewModelScope.launch {
            runSshCommand(cfg, "Остановка", script)
            delay(2000)
            checkServerState(cfg)
        }
    }

    fun fetchServerLogs() {
        val cfg = activeSshConfig ?: sshConfig.value
        if (cfg.ip.isEmpty()) return
        _serverLogs.value = "…"
        val logCmd = "tail -n 80 /opt/vk-turn/server.log 2>/dev/null || echo '(лог пуст)'"
        viewModelScope.launch {
            val out = runSshCommand(cfg, "server.log", logCmd)
            _serverLogs.value = out.trim().ifEmpty { "(лог пуст)" }
        }
    }

    fun clearSshLog() {
        _sshLog.value = emptyList()
    }

    // ── Local proxy ────────────────────────────────────────────────────────

    fun startProxy(context: Context) {
        if (ProxyService.isRunning.value) return

        if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle

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
            prefs.saveClientConfig(cfg)

            ProxyService.clearLogs()
            ProxyService.startupResult.value = null
            context.startForegroundService(Intent(context, ProxyService::class.java))

            // P2-1: ожидаем явного сигнала от ProxyService вместо delay(2500) + эвристики
            val result = withTimeoutOrNull(5_000L) {
                ProxyService.startupResult.filterNotNull().first()
            }

            // Если proxyFailed уже обработал ошибку — не дублируем
            if (_proxyState.value is ProxyState.Error) return@launch

            when (result) {
                null -> setErrorWithAutoReset("Прокси не запустился")
                is StartupResult.Failed -> {
                    context.stopService(Intent(context, ProxyService::class.java))
                    setErrorWithAutoReset(result.message)
                }
                is StartupResult.Success -> _proxyState.value = ProxyState.Running
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
        ProxyService.clearLogs()
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

    fun resetAllSettings(context: Context) {
        viewModelScope.launch {
            if (ProxyService.isRunning.value) {
                context.stopService(Intent(context, ProxyService::class.java))
            }
            prefs.resetAll()
            activeSshConfig = null
            _sshState.value = SshConnectionState.Disconnected
            _serverState.value = ServerState.Unknown
            _serverVersion.value = null
            _serverLogs.value = null
            _sshLog.value = emptyList()
            _proxyState.value = ProxyState.Idle
            _customKernelExists.value = false
            ProxyService.clearLogs()

            val intent = (context as? android.app.Activity)?.intent
                ?: Intent(context, com.freeturn.app.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
    }
}
