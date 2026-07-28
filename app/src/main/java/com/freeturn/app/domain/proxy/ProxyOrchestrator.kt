package com.freeturn.app.domain.proxy

import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.config.HostPort
import com.freeturn.app.domain.ServerState
import com.freeturn.app.domain.ssh.SshRepository
import kotlinx.coroutines.flow.first

class ProxyOrchestrator(
    private val prefs: AppPreferences,
    private val proxyManager: LocalProxyManager,
    private val sshRepository: SshRepository
) {
    suspend fun restartServerIfRunning() {
        val active = sshRepository.activeSshConfig
        if (active == null) {
            sshRepository.logNote("рестарт сервера пропущен: нет активной SSH-сессии")
            return
        }
        // Сессия должна вести на хост активного профиля: после смены профиля она ещё
        // может указывать на прошлый сервер - иначе рестартнём чужой хост.
        val cfg = prefs.sshConfigFlow.first()
        if (active.ip != cfg.ip || active.port != cfg.port) {
            sshRepository.logNote("рестарт сервера пропущен: SSH-сессия указывает на другой хост")
            return
        }
        val state = sshRepository.serverState.value
        val known = state as? ServerState.Known
        if (known?.running != true) {
            val reason = if (known != null) "сервер остановлен"
                else "состояние сервера ${state::class.simpleName}"
            sshRepository.logNote("рестарт сервера пропущен: $reason")
            return
        }
        val l = prefs.proxyListenFlow.first()
        val c = prefs.proxyConnectFlow.first()
        if (!HostPort.isValid(l) || !HostPort.isValid(c)) {
            sshRepository.logNote("рестарт сервера пропущен: некорректный listen/connect ($l -> $c)")
            return
        }
        val opts = prefs.serverOptsFlow.first()
        val tcpMode = prefs.clientConfigFlow.first().tcpForward
        sshRepository.stopServer()
        sshRepository.startServer(
            listen = l, connect = c,
            tcpMode = tcpMode,
            obfProfile = if (opts.obfEnabled) opts.obfProfile else "none",
            obfKey = if (opts.obfEnabled) opts.obfKey else "",
            clientId = prefs.ownClientId()
        )
    }

    suspend fun restartProxyIfRunning() {
        if (!ProxyServiceState.isRunning.value) return
        proxyManager.stopProxy()
        proxyManager.startProxy(prefs.clientConfigFlow.first())
    }

}
