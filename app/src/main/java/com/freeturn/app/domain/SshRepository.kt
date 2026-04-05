package com.freeturn.app.domain

import com.freeturn.app.SSHManager
import com.freeturn.app.data.SshConfig
import com.freeturn.app.viewmodel.ServerState
import com.freeturn.app.viewmodel.SshConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class SshRepository(private val sshManager: SSHManager = SSHManager()) {

    var activeSshConfig: SshConfig? = null
        private set

    private val _sshState = MutableStateFlow<SshConnectionState>(SshConnectionState.Disconnected)
    val sshState: StateFlow<SshConnectionState> = _sshState.asStateFlow()

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Unknown)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    private val _serverLogs = MutableStateFlow<String?>(null)
    val serverLogs: StateFlow<String?> = _serverLogs.asStateFlow()

    private val _sshLog = MutableStateFlow<List<String>>(emptyList())
    val sshLog: StateFlow<List<String>> = _sshLog.asStateFlow()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private fun timestamp(): String = LocalTime.now().format(timeFormatter)

    private fun appendSshLog(vararg lines: String) {
        _sshLog.update { current ->
            var next = current + lines
            if (next.size > 500) next = next.drop(next.size - 500)
            next
        }
    }

    private fun logCommand(target: String, cmd: String) {
        appendSshLog("  ssh $target")
        cmd.lines().filter { it.isNotBlank() }.forEach { appendSshLog("  $ $it") }
        appendSshLog("  ---")
    }

    private val SshConfig.fp get() = hostFingerprint.ifEmpty { null }
    private val SshConfig.key get() = if (authType == "SSH_KEY") sshKey else ""

    suspend fun runSshCommand(cfg: SshConfig, label: String, cmd: String): String {
        appendSshLog("", "=== $label [${timestamp()}] ===")
        logCommand("${cfg.username}@${cfg.ip}:${cfg.port}", cmd)
        val result = sshManager.executeSilentCommand(
            cfg.ip, cfg.port, cfg.username, cfg.password, cmd,
            knownFingerprint = cfg.fp, sshKey = cfg.key
        )
        result.lines().filter { it.isNotBlank() }.forEach { appendSshLog(it) }
        return result
    }

    suspend fun connectSsh(config: SshConfig): Pair<Boolean, String?> {
        _sshState.value = SshConnectionState.Connecting
        val result = runSshCommand(config, "Подключение", "echo OK")
        return if (result.trim() == "OK") {
            val fp = sshManager.lastSeenFingerprint ?: config.hostFingerprint
            activeSshConfig = config.copy(hostFingerprint = fp)
            _sshState.value = SshConnectionState.Connected(config.ip)
            checkServerState(config)
            Pair(true, sshManager.lastSeenFingerprint)
        } else {
            _sshState.value = SshConnectionState.Error(result.removePrefix("ERROR: "))
            Pair(false, null)
        }
    }

    fun disconnect() {
        activeSshConfig = null
        _sshState.value = SshConnectionState.Disconnected
        _serverState.value = ServerState.Unknown
        _serverVersion.value = null
    }

    suspend fun checkServerState(config: SshConfig? = null) {
        val cfg = config ?: activeSshConfig ?: return
        if (cfg.ip.isEmpty()) {
            _serverState.value = ServerState.Unknown
            return
        }
        _serverState.value = ServerState.Checking

        val result = runSshCommand(cfg, "Проверка состояния", ServerScripts.checkServerState)
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

    suspend fun fetchServerVersion(config: SshConfig? = null) {
        val cfg = config ?: activeSshConfig ?: return
        if (cfg.ip.isEmpty()) return
        val out = runSshCommand(cfg, "Запрос версии", ServerScripts.fetchServerVersion)
        _serverVersion.value = out.trim().takeIf { it.isNotEmpty() && !it.startsWith("ERROR") }
    }

    suspend fun installServer() {
        val cfg = activeSshConfig ?: return
        if (cfg.ip.isEmpty()) return
        _serverState.value = ServerState.Working("Установка vk-turn-proxy...")

        val result = runSshCommand(cfg, "Установка", ServerScripts.installServer)
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

    suspend fun startServer(listen: String, connect: String): Boolean {
        val cfg = activeSshConfig ?: return false
        if (cfg.ip.isEmpty()) return false
        
        _serverState.value = ServerState.Working("Запуск сервера...")
        runSshCommand(cfg, "Запуск", ServerScripts.startServer(listen, connect))
        delay(2000)
        checkServerState(cfg)
        return true
    }

    suspend fun stopServer() {
        val cfg = activeSshConfig ?: return
        if (cfg.ip.isEmpty()) return
        _serverState.value = ServerState.Working("Остановка сервера...")

        runSshCommand(cfg, "Остановка", ServerScripts.stopServer)
        delay(2000)
        checkServerState(cfg)
    }

    suspend fun fetchServerLogs() {
        val cfg = activeSshConfig ?: return
        if (cfg.ip.isEmpty()) return
        _serverLogs.value = "…"
        val out = runSshCommand(cfg, "server.log", ServerScripts.fetchServerLogs)
        _serverLogs.value = out.trim().ifEmpty { "(лог пуст)" }
    }

    fun updateServerState(state: ServerState) {
        _serverState.value = state
    }

    fun clearSshLog() {
        _sshLog.value = emptyList()
    }

    fun resetAll() {
        disconnect()
        _serverLogs.value = null
        _sshLog.value = emptyList()
    }
}
