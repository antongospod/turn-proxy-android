package com.freeturn.app.viewmodel.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerOpts
import com.freeturn.app.data.config.SshConfig
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.data.share.FreeturnLink
import com.freeturn.app.domain.share.LinkImportBus
import com.freeturn.app.data.HapticUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportUiState(
    val link: FreeturnLink? = null,
    val serverName: String = "",
    val vkLink: String = "",
    val duplicateAddress: Boolean = false,
    val duplicateConf: Boolean = false,
    val parseError: Boolean = false,
    val saving: Boolean = false,
    val saveError: Boolean = false,
    val saved: Boolean = false
) {
    val canConfirm: Boolean
        get() = link != null && !saving && !saved && vkLink.isNotBlank()
}

class ImportViewModel(
    private val prefs: AppPreferences,
    bus: LinkImportBus,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    val privacyMode: StateFlow<Boolean> = prefs.privacyModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            bus.links.collect(::offer)
        }
    }

    private suspend fun offer(raw: String) {
        if (_uiState.value.saving) return
        FreeturnLink.parse(raw).fold(
            onSuccess = { link ->
                val servers = prefs.serversSnapshot.first().list
                val normalizedConf = link.wgConf.trim()
                _uiState.value = ImportUiState(
                    link = link,
                    serverName = link.name.ifBlank { link.peer.substringBefore(':') },
                    duplicateAddress = servers.any {
                        it.client.serverAddress.equals(link.peer, ignoreCase = true)
                    },
                    duplicateConf = normalizedConf.isNotEmpty() && servers.any {
                        it.client.wireGuardConfig.trim() == normalizedConf
                    }
                )
            },
            onFailure = {
                HapticUtil.perform(appContext, HapticUtil.Pattern.ERROR)
                _uiState.value = ImportUiState(parseError = true)
            }
        )
    }

    fun setServerName(name: String) = _uiState.update { it.copy(serverName = name) }

    fun setVkLink(value: String) = _uiState.update { it.copy(vkLink = value) }

    fun confirm(fallbackName: String) {
        val st = _uiState.value
        val link = st.link ?: return
        if (!st.canConfirm) return
        _uiState.update { it.copy(saving = true, saveError = false) }
        viewModelScope.launch {
            try {
                prefs.addServer(buildServer(link, st, fallbackName), activate = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HapticUtil.perform(appContext, HapticUtil.Pattern.ERROR)
                _uiState.update { it.copy(saving = false, saveError = true) }
                return@launch
            }
            HapticUtil.perform(appContext, HapticUtil.Pattern.SUCCESS)
            _uiState.update { it.copy(saving = false, saved = true) }
        }
    }

    fun dismiss() {
        if (_uiState.value.saving) return
        _uiState.value = ImportUiState()
    }

    private fun buildServer(link: FreeturnLink, st: ImportUiState, fallbackName: String): Server {
        val wgConf = link.wgConf.trim()
        return Server(
            name = st.serverName.trim().ifBlank { fallbackName },
            ssh = SshConfig(),
            client = ClientConfig(
                serverAddress = link.peer,
                vkLink = st.vkLink.trim(),
                provider = link.provider,
                tcpForward = link.mode == "tcp",
                bond = link.bond,
                useUdp = link.transport == "udp",
                threads = link.n.takeIf { it > 0 } ?: ClientConfig.DEFAULT_THREADS,
                streamsPerCred = link.streamsPerCred.takeIf { it > 0 }
                    ?: ClientConfig.DEFAULT_STREAMS_PER_CRED,
                tunnelTransport = if (wgConf.isNotEmpty()) TunnelTransport.WIREGUARD
                else TunnelTransport.NONE,
                wireGuardConfig = wgConf,
                clientId = link.clientId.trim()
            ),
            opts = ServerOpts(
                obfProfile = link.obfProfile.ifBlank { ObfProfile.NONE },
                obfKey = link.obfKey
            )
        )
    }
}
