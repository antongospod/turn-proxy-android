package com.vkturn.proxy.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vkturn.proxy.ProxyService
import com.vkturn.proxy.SSHManager
import com.vkturn.proxy.data.AppPreferences
import com.vkturn.proxy.data.ClientConfig
import com.vkturn.proxy.data.SshConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val sshManager = SSHManager()

    private val _sshState = MutableStateFlow<SshConnectionState>(SshConnectionState.Disconnected)
    val sshState: StateFlow<SshConnectionState> = _sshState.asStateFlow()

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Unknown)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _proxyState = MutableStateFlow<ProxyState>(ProxyState.Idle)
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

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
            // Wait for first real emit from DataStore before showing UI
            onboardingDone.collect { _isInitialized.value = true }
        }

        ProxyService.onLogReceived = { msg ->
            _logs.value = (_logs.value + msg).takeLast(200)
        }
        _logs.value = ProxyService.logBuffer.toList()

        if (ProxyService.isRunning) _proxyState.value = ProxyState.Running
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
                config.ip, config.port, config.username, config.password, "echo OK"
            )
            if (result.trim() == "OK") {
                _sshState.value = SshConnectionState.Connected(config.ip)
                checkServerState(config)
            } else {
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
            val result = sshManager.executeSilentCommand(cfg.ip, cfg.port, cfg.username, cfg.password, checkCmd)
            _serverState.value = if (result.startsWith("ERROR")) {
                ServerState.Error(result.removePrefix("ERROR: "))
            } else {
                ServerState.Known(
                    installed = result.contains("INSTALLED:YES"),
                    running = result.contains("RUNNING:YES")
                )
            }
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
            sshManager.executeSilentCommand(cfg.ip, cfg.port, cfg.username, cfg.password, script)
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
            sshManager.executeSilentCommand(cfg.ip, cfg.port, cfg.username, cfg.password, script)
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
            sshManager.executeSilentCommand(cfg.ip, cfg.port, cfg.username, cfg.password, script)
            delay(2000)
            checkServerState(cfg)
        }
    }

    // ── Local proxy ────────────────────────────────────────────────────────

    fun startProxy(context: Context) {
        if (ProxyService.isRunning) return
        _proxyState.value = ProxyState.Starting

        viewModelScope.launch {
            // Write to legacy SharedPreferences so ProxyService can read them
            val cfg = clientConfig.value
            context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE).edit().apply {
                putString("peer", cfg.serverAddress)
                putString("link", cfg.vkLink)
                putString("n", cfg.threads.toString())
                putBoolean("udp", cfg.useUdp)
                putBoolean("noDtls", cfg.noDtls)
                putString("listen", cfg.localPort)
                putBoolean("isRaw", cfg.isRawMode)
                putString("rawCmd", cfg.rawCommand)
            }.apply()

            ProxyService.logBuffer.clear()
            context.startForegroundService(Intent(context, ProxyService::class.java))

            delay(1500)
            _proxyState.value = if (ProxyService.isRunning) ProxyState.Running
                                 else ProxyState.Error("Не удалось запустить прокси")
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
        ProxyService.logBuffer.clear()
        _logs.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        ProxyService.onLogReceived = null
        sshManager.disconnect()
    }
}
