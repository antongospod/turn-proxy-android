package com.freeturn.app.viewmodel.server

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.config.HostPort
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.server.ServerOpts
import com.freeturn.app.data.config.SshConfig
import com.freeturn.app.domain.proxy.ProxyOrchestrator
import com.freeturn.app.domain.ServerState
import com.freeturn.app.domain.SshConnectionState
import com.freeturn.app.domain.ssh.SshRepository
import com.freeturn.app.data.HapticUtil
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ServerViewModel(
    private val sshRepository: SshRepository,
    private val prefs: AppPreferences,
    private val orchestrator: ProxyOrchestrator,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    val sshState: StateFlow<SshConnectionState> = sshRepository.sshState
    val serverState: StateFlow<ServerState> = sshRepository.serverState
    val sshLog: StateFlow<List<String>> = sshRepository.sshLog
    val logsLoading: StateFlow<Boolean> = sshRepository.logsLoading

    val serverOpts: StateFlow<ServerOpts> = prefs.serverOptsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerOpts())

    val sshConfig: StateFlow<SshConfig> = prefs.sshConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SshConfig())

    // Cold-start состояния схлопнуты в Connecting, чтобы UI не мигал между фазами.
    val hubState: StateFlow<ServerHubState> =
        combine(sshState, serverState) { ssh, server ->
            when {
                ssh is SshConnectionState.Error -> ServerHubState.Failed
                server is ServerState.Error -> ServerHubState.Failed
                server is ServerState.Working -> ServerHubState.Working(server.action)
                ssh is SshConnectionState.Connected && server is ServerState.Known ->
                    ServerHubState.Online(
                        running = server.running,
                        installed = server.installed,
                        mode = server.mode,
                        obfProfile = server.obfProfile,
                        version = server.version,
                        sshIp = ssh.ip
                    )
                else -> ServerHubState.Connecting
            }
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerHubState.Connecting)

    fun connectSsh(config: SshConfig) {
        viewModelScope.launch {
            prefs.saveSshConfig(config)
            val (success, fp) = sshRepository.connectSsh(config)
            if (success) {
                if (config.hostFingerprint.isEmpty() && fp != null) {
                    prefs.saveSshFingerprint(fp)
                }
                // Детект rootMode живёт только в сессии; без персиста cleanup/share
                // (factory, читают сохранённый cfg) упирались бы в stale needs_root.
                sshRepository.activeSshConfig?.rootMode
                    ?.takeIf { it != config.rootMode }
                    ?.let { prefs.saveSshRootMode(it) }
                HapticUtil.perform(appContext, HapticUtil.Pattern.SUCCESS)
            } else {
                HapticUtil.perform(appContext, HapticUtil.Pattern.ERROR)
            }
        }
    }

    fun reconnectSsh() {
        viewModelScope.launch {
            val cfg = sshRepository.activeSshConfig
                 ?: sshConfig.value.takeIf { it.ip.isNotEmpty() }
                 ?: prefs.sshConfigFlow.first()
            if (cfg.ip.isNotEmpty()) connectSsh(cfg)
        }
    }

    fun installServer() {
        viewModelScope.launch {
            val outcome = sshRepository.installServer()
            if (outcome is SshRepository.InstallResult.Success) {
                if (outcome.stage == "downloaded" && prefs.serverOptsFlow.first().obfKey.isBlank()) {
                    prefs.updateActiveServer { it.copy(opts = it.opts.copy(obfKey = ObfProfile.generateKey())) }
                }
                if (outcome.needsRestart) {
                    startServer()
                    orchestrator.restartProxyIfRunning()
                }
            }
        }
    }

    fun startServer() {
        viewModelScope.launch {
            val l = prefs.proxyListenFlow.first()
            val c = prefs.proxyConnectFlow.first()
            if (!HostPort.isValid(l) || !HostPort.isValid(c)) {
                sshRepository.updateServerState(ServerState.Error("Неверный формат адреса (ожидается host:port)"))
                return@launch
            }
            val opts = prefs.serverOptsFlow.first()
            sshRepository.startServer(
                listen = l, connect = c,
                proxyMode = opts.proxyMode,
                kcp = opts.kcp,
                obfProfile = if (opts.obfEnabled) opts.obfProfile else "none",
                obfKey = if (opts.obfEnabled) opts.obfKey else "",
                obfTimingMs = opts.obfTimingMs,
                clientId = prefs.ownClientId()
            )
        }
    }

    fun stopServer() {
        viewModelScope.launch { sshRepository.stopServer() }
    }

    fun fetchServerLogs(lines: Int = 200) {
        viewModelScope.launch { sshRepository.fetchServerLogs(lines) }
    }

    fun clearSshLog() = sshRepository.clearSshLog()
}
