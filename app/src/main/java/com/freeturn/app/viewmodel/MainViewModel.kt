package com.freeturn.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.ProxyService
import com.freeturn.app.ProxyServiceState
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.SshConfig
import com.freeturn.app.domain.AppUpdater
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
    private val appUpdater = AppUpdater(application)

    val sshState: StateFlow<SshConnectionState> = sshRepository.sshState
    val serverState: StateFlow<ServerState> = sshRepository.serverState
    val serverVersion: StateFlow<String?> = sshRepository.serverVersion
    val serverLogs: StateFlow<String?> = sshRepository.serverLogs
    val sshLog: StateFlow<List<String>> = sshRepository.sshLog

    val proxyState: StateFlow<ProxyState> = proxyManager.proxyState
    val logs: StateFlow<List<String>> = ProxyServiceState.logs
    val customKernelExists: StateFlow<Boolean> = proxyManager.customKernelExists
    val updateState: StateFlow<UpdateState> = appUpdater.state

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

        // Автоматическая проверка обновлений при холодном запуске (silent — без ошибок)
        viewModelScope.launch {
            appUpdater.checkForUpdate(silent = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        proxyManager.destroy()
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

    private val _privacyMode = MutableStateFlow(false)
    val privacyMode: StateFlow<Boolean> = _privacyMode.asStateFlow()

    fun setPrivacyMode(enabled: Boolean) { _privacyMode.value = enabled }

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
    fun startProxy() {
        viewModelScope.launch {
            proxyManager.startProxy(clientConfig.value)
        }
    }

    fun stopProxy() {
        proxyManager.stopProxy()
    }

    fun clearLogs() {
        ProxyServiceState.clearLogs()
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
    private val _kernelError = MutableStateFlow<String?>(null)
    val kernelError: StateFlow<String?> = _kernelError.asStateFlow()

    fun setCustomKernel(uri: Uri) {
        viewModelScope.launch {
            _kernelError.value = proxyManager.setCustomKernel(uri)
        }
    }

    fun clearCustomKernel() {
        proxyManager.clearCustomKernel()
    }

    fun clearKernelError() {
        _kernelError.value = null
    }

    // ── App update ──────────────────────────────────────────────────────
    fun checkForUpdate() {
        viewModelScope.launch { appUpdater.checkForUpdate(silent = false) }
    }

    fun downloadUpdate() {
        viewModelScope.launch { appUpdater.downloadUpdate() }
    }

    fun installUpdate() {
        appUpdater.installUpdate()
    }

    fun resetUpdateState() {
        appUpdater.resetState()
    }

    fun resetAllSettings(context: Context) {
        viewModelScope.launch {
            if (ProxyServiceState.isRunning.value) {
                context.stopService(Intent(context, ProxyService::class.java))
            }
            prefs.resetAll()
            sshRepository.resetAll()
            proxyManager.clearState()
            ProxyServiceState.clearLogs()

            val intent = (context as? android.app.Activity)?.intent
                ?: Intent(context, com.freeturn.app.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
    }
}
