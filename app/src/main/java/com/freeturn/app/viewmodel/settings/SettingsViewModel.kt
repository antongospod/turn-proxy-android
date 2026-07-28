package com.freeturn.app.viewmodel.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.backup.BackupCrypto
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.control.UninstallData
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServersSnapshot
import com.freeturn.app.domain.backup.BackupManager
import com.freeturn.app.domain.update.AppUpdater
import com.freeturn.app.domain.proxy.LocalProxyManager
import com.freeturn.app.domain.proxy.ProxyOrchestrator
import com.freeturn.app.domain.server.ServerSetupRepository
import com.freeturn.app.domain.ssh.SshRepository
import com.freeturn.app.viewmodel.uiError
import com.freeturn.app.domain.UpdateState
import com.freeturn.app.domain.proxy.ProxyServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

sealed interface ServerCleanupState {
    data object Idle : ServerCleanupState
    data object Running : ServerCleanupState
    data class Done(val data: UninstallData) : ServerCleanupState
    data class Error(val message: String) : ServerCleanupState
}

class SettingsViewModel(
    private val prefs: AppPreferences,
    private val proxyManager: LocalProxyManager,
    private val sshRepository: SshRepository,
    private val serverSetup: ServerSetupRepository,
    private val appUpdater: AppUpdater,
    private val orchestrator: ProxyOrchestrator,
    private val backupManager: BackupManager,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    val clientConfig: StateFlow<ClientConfig> = prefs.clientConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClientConfig())

    val proxyListen: StateFlow<String> = prefs.proxyListenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "0.0.0.0:56000")

    val proxyConnect: StateFlow<String> = prefs.proxyConnectFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "127.0.0.1:40537")

    val dynamicTheme: StateFlow<Boolean> = prefs.dynamicThemeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val nerdMode: StateFlow<Boolean> = prefs.nerdModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hotspotProxyEnabled: StateFlow<Boolean> = prefs.hotspotProxyEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val serversSnapshot: StateFlow<ServersSnapshot> = prefs.serversSnapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServersSnapshot())

    val updateState: StateFlow<UpdateState> = appUpdater.state

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _initialTgSubscribeShown = MutableStateFlow(false)
    val initialTgSubscribeShown: StateFlow<Boolean> = _initialTgSubscribeShown.asStateFlow()

    // Снимок не даёт диалогу мигнуть на дефолтном значении до первого emit.
    private val _initialSuppressTgPrompt = MutableStateFlow(false)
    val initialSuppressTgPrompt: StateFlow<Boolean> = _initialSuppressTgPrompt.asStateFlow()

    val suppressUpdatePrompt: StateFlow<Boolean> = prefs.suppressUpdatePromptFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val suppressTgPrompt: StateFlow<Boolean> = prefs.suppressTgPromptFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val privacyMode: StateFlow<Boolean> = prefs.privacyModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val restartServerOnSwitch: StateFlow<Boolean> = prefs.restartServerOnSwitchFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Ожидаем DataStore, чтобы дефолт StateFlow не пропустил диалог первой сессии.
    suspend fun batteryPromptShownOnce(): Boolean = prefs.batteryPromptShownFlow.first()

    // Persist мгновенный; сетевые рестарты коалесцируются.
    private val syncSideEffectMutex = Mutex()
    private var syncSideEffectJob: Job? = null
    private val syncSideEffectDebounceMs = 600L

    init {
        viewModelScope.launch {
            _initialTgSubscribeShown.value = prefs.tgSubscribeShownFlow.first()
            _initialSuppressTgPrompt.value = prefs.suppressTgPromptFlow.first()
            ProxyServiceState.setLogsEnabled(prefs.clientConfigFlow.first().logsEnabled)
            _isInitialized.value = true
        }
        viewModelScope.launch {
            appUpdater.checkForUpdate(silent = true)
        }
    }

    fun setPrivacyMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setPrivacyMode(enabled) }
    }

    fun setRestartServerOnSwitch(enabled: Boolean) {
        viewModelScope.launch { prefs.setRestartServerOnSwitch(enabled) }
    }

    fun setDynamicTheme(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicTheme(enabled) }
    }

    fun setNerdMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setNerdMode(enabled) }
    }

    fun setHotspotProxyEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setHotspotProxyEnabled(enabled) }
    }

    fun setTgSubscribeShown() {
        viewModelScope.launch { prefs.setTgSubscribeShown() }
    }

    fun setBatteryPromptShown() {
        viewModelScope.launch { prefs.setBatteryPromptShown() }
    }

    fun setSuppressUpdatePrompt(enabled: Boolean) {
        viewModelScope.launch { prefs.setSuppressUpdatePrompt(enabled) }
    }

    fun setSuppressTgPrompt(enabled: Boolean) {
        viewModelScope.launch { prefs.setSuppressTgPrompt(enabled) }
    }

    // expectedActiveId не даёт отложенной записи затереть новый активный сервер.
    fun saveClientConfig(config: ClientConfig, expectedActiveId: String? = null) {
        viewModelScope.launch {
            val targetId = expectedActiveId
                ?: prefs.serversSnapshot.first().activeId ?: return@launch
            if (!prefs.updateServer(targetId) { it.copy(client = config) }) return@launch
            if (targetId == prefs.serversSnapshot.first().activeId) {
                ProxyServiceState.setLogsEnabled(config.logsEnabled)
            }
        }
    }

    fun setSplitTunnelMode(value: String) {
        viewModelScope.launch {
            prefs.updateActiveServer {
                it.copy(client = it.client.copy(splitTunnelMode = value))
            }
        }
    }

    fun setSplitTunnelApps(value: String) {
        viewModelScope.launch {
            val trimmed = value.trim()
            prefs.updateActiveServer {
                it.copy(client = it.client.copy(splitTunnelApps = trimmed))
            }
        }
    }

    // Ручной сервер создаётся неактивным и с sync OFF, чтобы его можно было донастроить без SSH.
    fun addManualServer(name: String, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            val server = Server(name = name, client = ClientConfig(syncServerSwitches = false))
            onAdded(prefs.addServer(server))
        }
    }

    fun renameServer(id: String, name: String) {
        viewModelScope.launch { prefs.renameServer(id, name) }
    }

    fun cloneServer(id: String, onCloned: (String) -> Unit) {
        viewModelScope.launch { prefs.cloneServer(id)?.let(onCloned) }
    }

    fun applyServer(id: String) {
        viewModelScope.launch {
            val target = prefs.serversSnapshot.first().list.firstOrNull { it.id == id }
                ?: return@launch
            prefs.setActiveServerId(target.id)

            if (prefs.restartServerOnSwitchFlow.first()) {
                orchestrator.restartServerIfRunning()
            }

            sshRepository.activeSshConfig?.let { prev ->
                if (prev.ip != target.ssh.ip || prev.port != target.ssh.port) {
                    sshRepository.disconnect()
                }
            }

            orchestrator.restartProxyIfRunning()
        }
    }

    private val _cleanupState = MutableStateFlow<ServerCleanupState>(ServerCleanupState.Idle)
    val cleanupState: StateFlow<ServerCleanupState> = _cleanupState.asStateFlow()

    fun resetCleanupState() { _cleanupState.value = ServerCleanupState.Idle }

    fun deleteServer(id: String) {
        viewModelScope.launch { prefs.deleteServer(id) }
    }

    fun cleanupServer(id: String) {
        viewModelScope.launch {
            val cfg = serversSnapshot.value.list.firstOrNull { it.id == id }?.ssh ?: return@launch
            _cleanupState.value = ServerCleanupState.Running
            // Перед удалением обновляем rootMode: сохранённое значение могло устареть.
            val mode = serverSetup.detectRootMode(cfg) ?: cfg.rootMode
            serverSetup.uninstall(cfg.copy(rootMode = mode), withWgPkg = true)
                .onSuccess { _cleanupState.value = ServerCleanupState.Done(it) }
                .onFailure { _cleanupState.value = ServerCleanupState.Error(it.uiError(appContext)) }
        }
    }

    fun updateServerClient(id: String, transform: (ClientConfig) -> ClientConfig) {
        viewModelScope.launch {
            if (!prefs.updateServer(id) { it.copy(client = transform(it.client)) }) return@launch
            val snap = prefs.serversSnapshot.first()
            snap.active?.takeIf { it.id == id }?.let {
                ProxyServiceState.setLogsEnabled(it.client.logsEnabled)
            }
        }
    }

    fun setBond(enabled: Boolean) {
        viewModelScope.launch {
            val changed = prefs.updateActiveServer {
                it.copy(client = it.client.copy(bond = enabled))
            }
            if (changed) orchestrator.restartProxyIfRunning()
        }
    }

    fun setActiveVkLink(link: String) {
        viewModelScope.launch {
            prefs.updateActiveServer {
                it.copy(client = it.client.copy(vkLink = link.trim()))
            }
        }
    }

    fun setSyncServerSwitches(enabled: Boolean) {
        viewModelScope.launch {
            val changed = prefs.updateActiveServer {
                it.copy(client = it.client.copy(syncServerSwitches = enabled))
            }
            if (!changed) return@launch
            // NonCancellable не даёт новому переключению оборвать SSH-команду на полпути.
            syncSideEffectJob?.cancel()
            syncSideEffectJob = viewModelScope.launch {
                delay(syncSideEffectDebounceMs)
                syncSideEffectMutex.withLock {
                    if (!prefs.clientConfigFlow.first().syncServerSwitches) return@withLock
                    withContext(NonCancellable) {
                        orchestrator.restartServerIfRunning()
                        orchestrator.restartProxyIfRunning()
                    }
                }
            }
        }
    }

    // Одна транзакция и один рестарт сохраняют атомарность apply-модели.
    fun applyServerConfig(
        listen: String,
        connect: String,
        tcpForward: Boolean,
        obfProfile: String,
        obfKey: String
    ) {
        viewModelScope.launch {
            val sync = prefs.clientConfigFlow.first().syncServerSwitches
            val trimmedKey = obfKey.trim()
            val changed = prefs.updateActiveServer { s ->
                val effKey = trimmedKey.ifBlank {
                    s.opts.obfKey.ifBlank {
                        if (obfProfile != ObfProfile.NONE) ObfProfile.generateKey() else ""
                    }
                }
                s.copy(
                    proxyListen = listen,
                    proxyConnect = connect,
                    client = s.client.copy(tcpForward = tcpForward),
                    opts = s.opts.copy(obfProfile = obfProfile, obfKey = effKey)
                )
            }
            if (changed) {
                if (sync) {
                    orchestrator.restartServerIfRunning()
                } else {
                    sshRepository.logNote("рестарт сервера пропущен: синхронизация выключена")
                }
                orchestrator.restartProxyIfRunning()
            }
        }
    }

    // Неактивный сервер обновляется без вмешательства в рантайм активного.
    fun updateServerConfig(
        id: String,
        listen: String,
        connect: String,
        tcpForward: Boolean,
        obfProfile: String,
        obfKey: String
    ) {
        viewModelScope.launch {
            prefs.updateServer(id) { target ->
                val effKey = obfKey.trim().ifBlank {
                    target.opts.obfKey.ifBlank {
                        if (obfProfile != ObfProfile.NONE) ObfProfile.generateKey() else ""
                    }
                }
                target.copy(
                    proxyListen = listen,
                    proxyConnect = connect,
                    client = target.client.copy(tcpForward = tcpForward),
                    opts = target.opts.copy(obfProfile = obfProfile, obfKey = effKey)
                )
            }
        }
    }

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

    // Буфер сохраняет событие, пока экран не подписан.
    private val _backupEvents = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 1)
    val backupEvents: SharedFlow<BackupEvent> = _backupEvents.asSharedFlow()

    fun exportBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            val event = try {
                val bytes = backupManager.export(password)
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IOException("no output stream")
                }
                BackupEvent.ExportSuccess
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BackupEvent.ExportFailed
            }
            _backupEvents.emit(event)
        }
    }

    fun restoreBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            val event = try {
                val bytes = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("no input stream")
                }
                // Разбор до остановки рантайма: неверный пароль не должен гасить подключение.
                val data = backupManager.decode(bytes, password)
                if (ProxyServiceState.isRunning.value) proxyManager.stopProxy()
                val count = backupManager.restore(data)
                sshRepository.resetAll()
                proxyManager.clearState()
                ProxyServiceState.clearLogs()
                BackupEvent.RestoreSuccess(count)
            } catch (e: CancellationException) {
                throw e
            } catch (_: BackupCrypto.BadPasswordException) {
                BackupEvent.RestoreFailed(RestoreFailReason.BAD_PASSWORD)
            } catch (_: BackupCrypto.FormatException) {
                BackupEvent.RestoreFailed(RestoreFailReason.BAD_FILE)
            } catch (_: Exception) {
                BackupEvent.RestoreFailed(RestoreFailReason.IO)
            }
            _backupEvents.emit(event)
        }
    }

    fun resetAllSettings() {
        viewModelScope.launch {
            if (ProxyServiceState.isRunning.value) {
                proxyManager.stopProxy()
            }
            prefs.resetAll()
            sshRepository.resetAll()
            proxyManager.clearState()
            ProxyServiceState.clearLogs()

            val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                appContext.startActivity(intent)
            }
        }
    }
}

enum class RestoreFailReason { BAD_PASSWORD, BAD_FILE, IO }

sealed interface BackupEvent {
    data object ExportSuccess : BackupEvent
    data object ExportFailed : BackupEvent
    data class RestoreSuccess(val count: Int) : BackupEvent
    data class RestoreFailed(val reason: RestoreFailReason) : BackupEvent
}
