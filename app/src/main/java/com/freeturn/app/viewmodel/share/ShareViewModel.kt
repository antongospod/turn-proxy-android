package com.freeturn.app.viewmodel.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.ClientId
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.share.ShareInfo
import com.freeturn.app.data.share.ShareLinkBuilder
import com.freeturn.app.data.share.SharedClient
import com.freeturn.app.data.share.WgPeer
import com.freeturn.app.domain.share.ShareRepository
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.viewmodel.uiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareResult(val userName: String, val link: String, val isWg: Boolean)

data class RevokeTarget(
    val name: String,
    val pubkey: String? = null,
    val clientId: String? = null
)

data class ShareUiState(
    val servers: List<Server> = emptyList(),
    val selectedServerId: String? = null,
    val shareInfo: ShareInfo? = null,
    val infoLoading: Boolean = false,
    val infoError: String? = null,
    val userName: String = "",
    val manualClientId: String = "",
    val shareAsProxy: Boolean = false,
    val shareVkLink: Boolean = false,
    val vkLinkToShare: String = "",
    val creating: Boolean = false,
    val createError: String? = null,
    val result: ShareResult? = null,
    val peers: List<WgPeer> = emptyList(),
    val clients: List<SharedClient> = emptyList(),
    val peersLoaded: Boolean = false,
    val peersLoading: Boolean = false,
    val peersError: String? = null,
    val resharePubkey: String? = null,
    val revokeTarget: RevokeTarget? = null,
    val revoking: Boolean = false
) {
    val selectedServer: Server? get() = servers.firstOrNull { it.id == selectedServerId }

    val localOnly: Boolean get() = selectedServer?.ssh?.ip?.isBlank() == true

    val ownerVkLink: String get() = selectedServer?.client?.vkLink?.trim().orEmpty()

    val sharedVkLink: String get() = if (shareVkLink) vkLinkToShare.trim() else ""

    val canManageUsers: Boolean get() = selectedServer != null && !localOnly

    val useWg: Boolean get() = shareInfo?.wgBackend == true && !shareAsProxy

    val canChooseMode: Boolean get() = shareInfo?.wgBackend == true

    val missingAddress: Boolean
        get() = selectedServer?.client?.serverAddress?.isBlank() == true

    val manualClientIdValid: Boolean get() = !localOnly || ClientId.isValid(manualClientId.trim())

    val canCreate: Boolean
        get() = !creating && userName.isNotBlank() && selectedServer != null &&
            !missingAddress && shareInfo != null && !infoLoading && manualClientIdValid
}

class ShareViewModel(
    private val repo: ShareRepository,
    private val prefs: AppPreferences,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    companion object {
        // Строже серверного лимита nameB64 (64): короткое имя плотнее в UI и QR.
        const val MAX_USER_NAME_LEN = 32
    }

    private val _uiState = MutableStateFlow(ShareUiState())
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    // share-info по серверам в рамках жизни VM: повторный выбор не дёргает SSH.
    private val infoCache = mutableMapOf<String, ShareInfo>()

    init {
        viewModelScope.launch {
            prefs.serversSnapshot.collect { snap ->
                val servers = snap.list.filter {
                    it.ssh.ip.isNotBlank() || it.client.serverAddress.isNotBlank()
                }
                _uiState.update { st ->
                    val selected = st.selectedServerId
                        ?.takeIf { id -> servers.any { it.id == id } }
                        ?: snap.activeId?.takeIf { id -> servers.any { it.id == id } }
                        ?: servers.firstOrNull()?.id
                    val base = if (selected != st.selectedServerId) st.resetForServer(selected) else st
                    base.copy(servers = servers)
                }
                // SSH-загрузка после enter-перехода не сбивает анимацию экрана.
            }
        }
    }

    private fun ShareUiState.resetForServer(id: String?) = copy(
        selectedServerId = id,
        shareInfo = null,
        manualClientId = "",
        shareAsProxy = false,
        shareVkLink = false,
        vkLinkToShare = "",
        infoError = null,
        createError = null,
        peers = emptyList(),
        clients = emptyList(),
        peersLoaded = false,
        peersLoading = false,
        peersError = null,
        resharePubkey = null
    )

    fun selectServer(id: String) {
        if (_uiState.value.selectedServerId == id) return
        _uiState.update { it.resetForServer(id) }
        loadInfoIfNeeded(id)
    }

    fun setUserName(name: String) =
        _uiState.update { it.copy(userName = name.take(MAX_USER_NAME_LEN), createError = null) }

    fun setManualClientId(id: String) =
        _uiState.update { it.copy(manualClientId = id.trim().lowercase().take(32), createError = null) }

    fun setShareMode(proxy: Boolean) =
        _uiState.update { it.copy(shareAsProxy = proxy, createError = null) }

    fun setShareVkLink(enabled: Boolean) =
        _uiState.update {
            // Включили - подставляем ссылку сервера как заготовку, её можно перебить своей.
            it.copy(
                shareVkLink = enabled,
                vkLinkToShare = if (enabled) it.ownerVkLink else "",
                createError = null
            )
        }

    fun setVkLinkToShare(value: String) =
        _uiState.update { it.copy(vkLinkToShare = value, createError = null) }

    fun retryInfo() {
        val id = _uiState.value.selectedServerId ?: return
        infoCache.remove(id)
        _uiState.update { it.copy(infoError = null) }
        loadInfoIfNeeded(id)
    }

    fun ensureInfoLoaded() {
        val st = _uiState.value
        if (st.shareInfo == null && !st.infoLoading && st.infoError == null) {
            st.selectedServerId?.let(::loadInfoIfNeeded)
        }
    }

    // Обновляем кэш без loading-состояния, чтобы повторный вход оставался мгновенным.
    fun revalidateInfo() {
        val st = _uiState.value
        val id = st.selectedServerId ?: return
        if (st.shareInfo == null) return
        val server = st.servers.firstOrNull { it.id == id } ?: return
        if (server.ssh.ip.isBlank()) return // ручной сервер: серверной правды нет
        viewModelScope.launch {
            repo.shareInfo(server.ssh).onSuccess { fresh ->
                infoCache[id] = fresh
                _uiState.update { cur ->
                    if (cur.selectedServerId == id && cur.shareInfo != fresh) cur.copy(shareInfo = fresh)
                    else cur
                }
            }
        }
    }

    private fun loadInfoIfNeeded(id: String) {
        val st = _uiState.value
        if (st.selectedServerId != id || st.infoLoading) return
        val server = st.servers.firstOrNull { it.id == id } ?: return
        if (server.ssh.ip.isBlank()) {
            // Синтетическое состояние позволяет собрать локальную ссылку без SSH.
            if (st.shareInfo == null) _uiState.update {
                it.copy(shareInfo = ShareInfo(), manualClientId = server.client.clientId)
            }
            return
        }
        infoCache[id]?.let { cached ->
            if (st.shareInfo != cached) _uiState.update { it.copy(shareInfo = cached) }
            return
        }
        _uiState.update { it.copy(infoLoading = true, infoError = null) }
        viewModelScope.launch {
            val result = repo.shareInfo(server.ssh)
            result.onSuccess { infoCache[id] = it }
            val current = _uiState.value.selectedServerId
            if (current != id) {
            // Результат SSH относится к уже снятому выбору.
                _uiState.update { it.copy(infoLoading = false) }
                current?.let(::loadInfoIfNeeded)
                return@launch
            }
            result
                .onSuccess { info ->
                    _uiState.update { it.copy(infoLoading = false, shareInfo = info) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(infoLoading = false, infoError = e.uiError(appContext))
                    }
                }
        }
    }

    fun createShare() {
        val st = _uiState.value
        val server = st.selectedServer ?: return
        val info = st.shareInfo ?: return
        if (!st.canCreate) return
        val userName = st.userName.trim()
        if (server.ssh.ip.isBlank()) {
            commitCreated(
                serverId = server.id,
                userName = userName,
                link = ShareLinkBuilder.build(
                    server, info, userName, null, st.manualClientId.trim(), st.sharedVkLink
                ),
                wg = false
            )
            return
        }
        val useWg = st.useWg
        _uiState.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            val cid = ClientId.generate()
            if (useWg) {
                repo.addPeer(server.ssh, userName, ClientConfig.DEFAULT_LOCAL_PORT, cid)
                    .onSuccess { peer ->
                        commitCreated(
                            serverId = server.id,
                            userName = userName,
                            link = ShareLinkBuilder.build(
                                server, info, userName, peer.clientConf, cid, st.sharedVkLink
                            ),
                            wg = true,
                            newPeer = WgPeer(
                                pubkey = peer.pubkey,
                                name = userName,
                                ip = peer.ip,
                                lastHandshakeEpoch = null,
                                hasStoredConf = true,
                                isSelf = false
                            )
                        )
                    }
                    .onFailure(::commitCreateError)
            } else {
                repo.addClient(server.ssh, userName, cid)
                    .onSuccess {
                        commitCreated(
                            serverId = server.id,
                            userName = userName,
                            link = ShareLinkBuilder.build(
                                server, info, userName, null, cid, st.sharedVkLink
                            ),
                            wg = false,
                            newClient = SharedClient(clientId = cid, name = userName)
                        )
                    }
                    .onFailure(::commitCreateError)
            }
        }
    }

    private fun commitCreated(
        serverId: String,
        userName: String,
        link: String,
        wg: Boolean,
        newPeer: WgPeer? = null,
        newClient: SharedClient? = null
    ) {
        HapticUtil.perform(appContext, HapticUtil.Pattern.SUCCESS)
        _uiState.update { cur ->
            val appendable = cur.selectedServerId == serverId && cur.peersLoaded
            cur.copy(
                creating = false,
                userName = "",
                result = ShareResult(userName, link, wg),
                peers = if (appendable && newPeer != null) cur.peers + newPeer else cur.peers,
                clients = if (appendable && newClient != null) cur.clients + newClient else cur.clients
            )
        }
    }

    private fun commitCreateError(e: Throwable) {
        HapticUtil.perform(appContext, HapticUtil.Pattern.ERROR)
        _uiState.update { it.copy(creating = false, createError = e.uiError(appContext)) }
    }

    fun dismissResult() = _uiState.update { it.copy(result = null) }

    fun refreshPeers(force: Boolean = false) {
        val st = _uiState.value
        val server = st.selectedServer ?: return
        if (st.peersLoading || (st.peersLoaded && !force)) return
        _uiState.update { it.copy(peersLoading = true, peersError = null) }
        viewModelScope.launch {
            val result = repo.listShared(server.ssh)
            // Результат SSH относится к уже снятому выбору.
            if (_uiState.value.selectedServerId != server.id) return@launch
            result
                .onSuccess { (peers, clients) ->
                    _uiState.update {
                        it.copy(
                            peersLoading = false,
                            peersLoaded = true,
                            peers = peers,
                            clients = clients
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(peersLoading = false, peersError = e.uiError(appContext))
                    }
                }
        }
    }

    fun resharePeer(peer: WgPeer) {
        val st = _uiState.value
        val server = st.selectedServer ?: return
        val info = st.shareInfo ?: return
        if (st.resharePubkey != null || !peer.hasStoredConf) return
        _uiState.update { it.copy(resharePubkey = peer.pubkey, peersError = null) }
        viewModelScope.launch {
            val result = repo.peerConf(server.ssh, peer.pubkey, ClientId.generate(), peer.name)
            // Результат SSH относится к уже снятому выбору.
            if (_uiState.value.selectedServerId != server.id) return@launch
            result
                .onSuccess { access ->
                    _uiState.update {
                        it.copy(
                            resharePubkey = null,
                            result = ShareResult(
                                userName = peer.name,
                                link = ShareLinkBuilder.build(
                                    server, info, peer.name, access.clientConf,
                                    access.clientId, st.sharedVkLink
                                ),
                                isWg = true
                            )
                        )
                    }
                }
                .onFailure { e ->
                    HapticUtil.perform(appContext, HapticUtil.Pattern.ERROR)
                    _uiState.update {
                        it.copy(resharePubkey = null, peersError = e.uiError(appContext))
                    }
                }
        }
    }

    fun reshareClient(client: SharedClient) {
        val st = _uiState.value
        val server = st.selectedServer ?: return
        val info = st.shareInfo ?: return
        _uiState.update {
            it.copy(
                result = ShareResult(
                    userName = client.name,
                    link = ShareLinkBuilder.build(
                        server, info, client.name, null, client.clientId, it.sharedVkLink
                    ),
                    isWg = false
                )
            )
        }
    }

    fun askRevoke(peer: WgPeer) =
        _uiState.update { it.copy(revokeTarget = RevokeTarget(name = peer.name, pubkey = peer.pubkey)) }

    fun askRevokeClient(client: SharedClient) =
        _uiState.update { it.copy(revokeTarget = RevokeTarget(name = client.name, clientId = client.clientId)) }

    fun dismissRevoke() {
        if (_uiState.value.revoking) return
        _uiState.update { it.copy(revokeTarget = null) }
    }

    fun confirmRevoke() {
        val st = _uiState.value
        val server = st.selectedServer ?: return
        val target = st.revokeTarget ?: return
        if (st.revoking) return
        _uiState.update { it.copy(revoking = true) }
        viewModelScope.launch {
            val result =
                if (target.pubkey != null) repo.removePeer(server.ssh, target.pubkey)
                else repo.removeClient(server.ssh, target.clientId.orEmpty())
            result
                .onSuccess {
                    HapticUtil.perform(appContext, HapticUtil.Pattern.SUCCESS)
                    _uiState.update {
                        it.copy(
                            revoking = false,
                            revokeTarget = null,
                            peers = it.peers.filterNot { p -> p.pubkey == target.pubkey },
                            clients = it.clients.filterNot { c -> c.clientId == target.clientId }
                        )
                    }
                }
                .onFailure { e ->
                    HapticUtil.perform(appContext, HapticUtil.Pattern.ERROR)
                    _uiState.update {
                        it.copy(
                            revoking = false,
                            revokeTarget = null,
                            peersError = e.uiError(appContext)
                        )
                    }
                }
        }
    }
}
