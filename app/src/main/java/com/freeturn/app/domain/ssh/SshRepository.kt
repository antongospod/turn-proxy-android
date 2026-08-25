package com.freeturn.app.domain.ssh

import android.content.Context
import com.freeturn.app.data.config.KcpProfile
import com.freeturn.app.data.config.ProxyMode
import com.freeturn.app.data.config.SshConfig
import com.freeturn.app.data.control.ControlResponse
import com.freeturn.app.data.control.InstallData
import com.freeturn.app.data.control.ProbeData
import com.freeturn.app.domain.ServerState
import com.freeturn.app.domain.SshConnectionState
import com.freeturn.app.domain.server.ServerCommand
import com.freeturn.app.domain.server.ServerControl
import com.freeturn.app.domain.server.ServerStartOptions
import com.freeturn.app.domain.server.errorText
import com.freeturn.app.domain.server.requireData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class SshRepository(
    context: Context,
    private val sshManager: SSHManager = SSHManager(),
    private val serverControl: ServerControl = ServerControl(context, sshManager)
) {

    // Параллельные команды на одном SSHManager затирали бы fingerprint/_serverState.
    private val mutex = Mutex()

    var activeSshConfig: SshConfig? = null
        private set

    private val _sshState = MutableStateFlow<SshConnectionState>(SshConnectionState.Disconnected)
    val sshState: StateFlow<SshConnectionState> = _sshState.asStateFlow()

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Unknown)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _sshLog = MutableStateFlow<List<String>>(emptyList())
    val sshLog: StateFlow<List<String>> = _sshLog.asStateFlow()

    private val _logsLoading = MutableStateFlow(false)
    val logsLoading: StateFlow<Boolean> = _logsLoading.asStateFlow()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private fun timestamp(): String = LocalTime.now().format(timeFormatter)

    private fun appendSshLog(lines: List<String>) {
        if (lines.isEmpty()) return
        _sshLog.update { current ->
            var next = current + lines
            if (next.size > 500) next = next.drop(next.size - 500)
            next
        }
    }

    private fun appendSshLog(vararg lines: String) = appendSshLog(lines.toList())

    private fun logHeader(label: String, target: String) {
        appendSshLog("", "=== $label [${timestamp()}] ===", "  ssh $target")
    }

    private fun logCmdResult(result: ControlResponse) {
        // Одним обновлением состояния: построчный append на длинном выводе (журнал
        // на 200 строк) дёргал подписчиков UI (бейдж и автоскролл) на каждую строку.
        val batch = buildList {
            addAll(result.logs)
            if (result.isOk) {
                // Примитивы - без кавычек JsonElement.toString (version=1.6.0, а не "1.6.0");
                // объекты/массивы (wg, conflicts) остаются компактным JSON.
                result.data.forEach { (k, v) ->
                    add("  $k=" + ((v as? JsonPrimitive)?.contentOrNull ?: v.toString()))
                }
            } else {
                add("ERROR: ${result.errorText()}")
            }
        }
        appendSshLog(batch)
    }

    private suspend fun runCmd(cfg: SshConfig, label: String, cmd: ServerCommand): ControlResponse {
        logHeader(label, "${cfg.username}@${cfg.ip}:${cfg.port}")
        val result = serverControl.run(cfg, cmd)
        logCmdResult(result)
        return result
    }

    private suspend fun runEcho(cfg: SshConfig): String {
        logHeader("Подключение", "${cfg.username}@${cfg.ip}:${cfg.port}")
        val result = sshManager.executeSilentCommand(
            cfg.ip, cfg.port, cfg.username, cfg.password, "echo OK",
            knownFingerprint = cfg.hostFingerprint.ifEmpty { null },
            sshKey = if (cfg.authType == SshConfig.AUTH_SSH_KEY) cfg.sshKey else ""
        )
        appendSshLog(result.lines().filter { it.isNotBlank() })
        return result
    }

    suspend fun connectSsh(config: SshConfig): Pair<Boolean, String?> = mutex.withLock {
        _sshState.value = SshConnectionState.Connecting
        val result = runEcho(config)
        // Сравниваем построчно, а не весь вывод: серверный MOTD/banner или строки
        // из .bashrc могут попасть в stdout перед "OK" и сломать строгое равенство.
        if (result.lines().any { it.trim() == "OK" }) {
            val fp = sshManager.lastSeenFingerprint ?: config.hostFingerprint
            // Сохранённый rootMode мог устареть между SSH-сессиями.
            val mode = serverControl.detectRootMode(config) ?: config.rootMode
            activeSshConfig = config.copy(hostFingerprint = fp, rootMode = mode)
            _sshState.value = SshConnectionState.Connected(config.ip)
            checkServerStateLocked(activeSshConfig, silent = false)
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
    }

    /**
     * @param silent пропустить промежуточный [ServerState.Checking]. Нужно при перепроверке
     * после действия (стоп/старт/установка): иначе хаб моргает Working -> "Подключение" -> Online.
     * При silent текущий Working держится до прихода [ServerState.Known].
     */
    suspend fun checkServerState(config: SshConfig? = null, silent: Boolean = false) =
        mutex.withLock { checkServerStateLocked(config, silent) }

    private suspend fun checkServerStateLocked(config: SshConfig?, silent: Boolean) {
        val cfg = config ?: activeSshConfig ?: return
        if (cfg.ip.isEmpty()) {
            _serverState.value = ServerState.Unknown
            return
        }
        if (!silent) _serverState.value = ServerState.Checking

        val r = runCmd(cfg, "Проверка состояния", ServerCommand.Probe)
        r.requireData<ProbeData>()
            .onSuccess { d ->
                _serverState.value = ServerState.Known(
                    installed = d.installed,
                    running = d.running,
                    mode = if (d.running) d.mode ?: ProxyMode.UDP else null,
                    obfProfile = if (d.running) d.obf else null,
                    version = d.version
                )
            }
            .onFailure { e -> _serverState.value = ServerState.Error(e.message ?: r.errorText()) }
    }

    suspend fun installServer(): InstallResult = mutex.withLock {
        val cfg = activeSshConfig ?: return@withLock InstallResult.Failed("not connected")
        if (cfg.ip.isEmpty()) return@withLock InstallResult.Failed("no SSH config")
        _serverState.value = ServerState.Working("Установка free-turn-proxy...")

        val result = runCmd(cfg, "Установка", ServerCommand.Install)
        result.requireData<InstallData>().fold(
            onSuccess = { d ->
                // Работающий процесс остаётся на старом бинарнике до перезапуска.
                if (d.needsRestart) {
                    appendSshLog("  needs_restart -> авто-рестарт сервера")
                    runCmd(cfg, "Авто-остановка перед рестартом", ServerCommand.Stop)
                }
                delay(300)
                checkServerStateLocked(cfg, silent = true)
                InstallResult.Success(stage = d.stage, version = d.version, needsRestart = d.needsRestart)
            },
            onFailure = { e ->
                val msg = e.message ?: result.errorText()
                _serverState.value = ServerState.Error(msg)
                InstallResult.Failed(msg)
            }
        )
    }

    suspend fun startServer(
        listen: String,
        connect: String,
        proxyMode: String = ProxyMode.UDP,
        kcp: KcpProfile = KcpProfile.DEFAULT,
        obfProfile: String = "none",
        obfKey: String = "",
        obfTimingMs: Int = 0,
        clientId: String = ""
    ): Boolean = mutex.withLock {
        val cfg = activeSshConfig ?: return@withLock false
        if (cfg.ip.isEmpty()) return@withLock false

        _serverState.value = ServerState.Working("Запуск сервера...")
        val result = runCmd(
            cfg, "Запуск",
            ServerCommand.Start(
                ServerStartOptions(
                    listen = listen,
                    connect = connect,
                    proxyMode = proxyMode,
                    kcp = kcp,
                    obfProfile = obfProfile,
                    obfKey = obfKey,
                    obfTimingMs = obfTimingMs,
                    clientId = clientId
                )
            )
        )
        if (!result.isOk) {
            _serverState.value = ServerState.Error(result.errorText())
            return@withLock false
        }
        delay(1500)
        checkServerStateLocked(cfg, silent = true)
        true
    }

    suspend fun stopServer() = mutex.withLock {
        val cfg = activeSshConfig ?: return@withLock
        if (cfg.ip.isEmpty()) return@withLock
        _serverState.value = ServerState.Working("Остановка сервера...")

        val result = runCmd(cfg, "Остановка", ServerCommand.Stop)
        if (!result.isOk) {
            _serverState.value = ServerState.Error(result.errorText())
            return@withLock
        }
        delay(1000)
        checkServerStateLocked(cfg, silent = true)
    }

    suspend fun fetchServerLogs(lines: Int = 200) = mutex.withLock {
        val cfg = activeSshConfig ?: return@withLock
        if (cfg.ip.isEmpty()) return@withLock
        _logsLoading.value = true
        try {
            runCmd(cfg, "server.log", ServerCommand.FetchLogs(lines))
        } finally {
            _logsLoading.value = false
        }
        Unit
    }

    fun updateServerState(state: ServerState) {
        _serverState.value = state
    }

    fun logNote(line: String) {
        appendSshLog("", "--- $line [${timestamp()}]")
    }

    fun clearSshLog() {
        _sshLog.value = emptyList()
    }

    fun resetAll() {
        disconnect()
        _sshLog.value = emptyList()
    }

    sealed class InstallResult {
        /**
         * stage: cached | downloaded; version - только если ядро вернуло.
         * needsRestart - true, если бинарь был переустановлен поверх работающего
         * процесса; перед использованием новой версии нужен start.
         */
        data class Success(
            val stage: String,
            val version: String?,
            val needsRestart: Boolean = false
        ) : InstallResult()
        data class Failed(val message: String) : InstallResult()
    }
}
