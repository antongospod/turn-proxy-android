package com.freeturn.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.ProxyService
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.SshConfig
import com.freeturn.app.domain.LocalProxyManager
import com.freeturn.app.domain.SshRepository
import com.freeturn.app.ui.HapticUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val sshRepository = SshRepository()
    private val proxyManager = LocalProxyManager(application)

    val sshState: StateFlow<SshConnectionState> = sshRepository.sshState
    val serverState: StateFlow<ServerState> = sshRepository.serverState
    val serverVersion: StateFlow<String?> = sshRepository.serverVersion
    val serverLogs: StateFlow<String?> = sshRepository.serverLogs
    val sshLog: StateFlow<List<String>> = sshRepository.sshLog

    val proxyState: StateFlow<ProxyState> = proxyManager.proxyState
    val logs: StateFlow<List<String>> = ProxyService.logs
    val customKernelExists: StateFlow<Boolean> = proxyManager.customKernelExists

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.onboardingDoneFlow.first()
            _isInitialized.value = true
        }
        viewModelScope.launch {
            proxyManager.observeProxyLifecycle()
        }
        viewModelScope.launch {
            proxyManager.observeProxyServiceStatus()
        }
        proxyManager.syncInitialState()
    }

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

    val dynamicTheme: StateFlow<Boolean> = prefs.dynamicThemeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setDynamicTheme(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicTheme(enabled) }
    }

    // ── SSH ────────────────────────────────────────────────────────────────
    fun connectSsh(config: SshConfig) {
        viewModelScope.launch {
            prefs.saveSshConfig(config)
            val (success, fp) = sshRepository.connectSsh(config)
            if (success) {
                if (config.hostFingerprint.isEmpty() && fp != null) {
                    prefs.saveSshFingerprint(fp)
                }
                HapticUtil.perform(getApplication(), HapticUtil.Pattern.SUCCESS)
            } else {
                HapticUtil.perform(getApplication(), HapticUtil.Pattern.ERROR)
            }
        }
    }

    fun disconnectSsh() {
        sshRepository.disconnect()
    }

    fun reconnectSsh() {
        viewModelScope.launch {
            val cfg = sshRepository.activeSshConfig
                 ?: sshConfig.value.takeIf { it.ip.isNotEmpty() }
                 ?: prefs.sshConfigFlow.first()
            if (cfg.ip.isNotEmpty()) connectSsh(cfg)
        }
    }

    // ── Server management ──────────────────────────────────────────────────
    fun checkServerState(config: SshConfig? = null) {
        viewModelScope.launch { sshRepository.checkServerState(config ?: sshConfig.value) }
    }

    fun fetchServerVersion(config: SshConfig? = null) {
        viewModelScope.launch { sshRepository.fetchServerVersion(config ?: sshConfig.value) }
    }

    fun installServer() {
        viewModelScope.launch { sshRepository.installServer() }
    }

    fun startServer() {
        val l = proxyListen.value
        val c = proxyConnect.value
        if (!l.matches(Regex("""^[\w.\-]+:\d{1,5}$""")) || !c.matches(Regex("""^[\w.\-]+:\d{1,5}$"""))) {
            sshRepository.updateServerState(ServerState.Error("Неверный формат адреса (ожидается host:port)"))
            return
        }
        viewModelScope.launch { sshRepository.startServer(l, c) }
    }

    fun stopServer() {
        viewModelScope.launch { sshRepository.stopServer() }
    }

    fun fetchServerLogs() {
        viewModelScope.launch { sshRepository.fetchServerLogs() }
    }

    fun clearSshLog() {
        sshRepository.clearSshLog()
    }

    // ── Local proxy ────────────────────────────────────────────────────────
    fun startProxy(context: Context) {
        viewModelScope.launch {
            proxyManager.startProxy(clientConfig.value)
        }
    }

    fun stopProxy(context: Context) {
        proxyManager.stopProxy()
    }

    fun clearLogs() {
        ProxyService.clearLogs()
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

    // ── Custom kernel ──────────────────────────────────────────────────────
    fun setCustomKernel(uri: Uri) {
        viewModelScope.launch { proxyManager.setCustomKernel(uri) }
    }

    fun clearCustomKernel() {
        proxyManager.clearCustomKernel()
    }

    fun resetAllSettings(context: Context) {
        viewModelScope.launch {
            if (ProxyService.isRunning.value) {
                context.stopService(Intent(context, ProxyService::class.java))
            }
            prefs.resetAll()
            sshRepository.resetAll()
            proxyManager.clearState()
            ProxyService.clearLogs()

            val intent = (context as? android.app.Activity)?.intent
                ?: Intent(context, com.freeturn.app.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
    }
}
