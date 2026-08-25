package com.freeturn.app.domain.share

import android.content.Context
import com.freeturn.app.data.config.SshConfig
import com.freeturn.app.data.control.PeerAddData
import com.freeturn.app.data.control.PeerConfData
import com.freeturn.app.data.control.ShareInfoData
import com.freeturn.app.data.control.ShareListData
import com.freeturn.app.data.control.decodeBase64
import com.freeturn.app.data.share.ShareInfo
import com.freeturn.app.data.share.SharedClient
import com.freeturn.app.data.share.SharedClientParser
import com.freeturn.app.data.share.WgPeer
import com.freeturn.app.data.share.WgPeerParser
import com.freeturn.app.domain.server.ServerCommand
import com.freeturn.app.domain.server.ServerCommandException
import com.freeturn.app.domain.server.ServerControl
import com.freeturn.app.domain.server.asUnit
import com.freeturn.app.domain.server.requireData
import com.freeturn.app.domain.ssh.SSHManager
import java.util.Base64

class ShareRepository(context: Context, ssh: SSHManager) {

    private val control = ServerControl(context, ssh)

    data class NewPeer(val pubkey: String, val ip: String, val clientConf: String)

    data class PeerAccess(val clientConf: String, val clientId: String)

    suspend fun shareInfo(cfg: SshConfig): Result<ShareInfo> =
        control.run(cfg, ServerCommand.ShareInfo)
            .requireData<ShareInfoData>()
            .map { ShareInfo(it.mode, it.obfProfile, it.obfKey, it.wgBackend) }

    suspend fun addPeer(
        cfg: SshConfig,
        name: String,
        endpoint: String,
        clientId: String
    ): Result<NewPeer> =
        control.run(cfg, ServerCommand.PeerAdd(nameB64(name), endpoint, clientId))
            .requireData<PeerAddData>()
            .mapCatching { d ->
                val conf = decodeBase64(d.clientConfB64)
                if (d.peer.pub.isBlank() || conf.isNullOrBlank()) {
                    throw ServerCommandException("server returned no peer config")
                }
                NewPeer(d.peer.pub, d.peer.ip, conf)
            }

    suspend fun listShared(cfg: SshConfig): Result<Pair<List<WgPeer>, List<SharedClient>>> =
        control.run(cfg, ServerCommand.ShareList)
            .requireData<ShareListData>()
            .map { WgPeerParser.from(it) to SharedClientParser.from(it) }

    /**
     * Conf пира + его cid. [candidateClientId]/[name] - backfill: пир без
     * cid-маппинга (выдан до allowlist) регистрируется этим cid на сервере.
     */
    suspend fun peerConf(
        cfg: SshConfig,
        pubkey: String,
        candidateClientId: String,
        name: String
    ): Result<PeerAccess> =
        control.run(cfg, ServerCommand.PeerConf(pubkey, candidateClientId, nameB64(name)))
            .requireData<PeerConfData>()
            .mapCatching { d ->
                val conf = decodeBase64(d.clientConfB64)
                    ?: throw ServerCommandException("server returned no peer config")
                PeerAccess(conf, d.clientId)
            }

    suspend fun removePeer(cfg: SshConfig, pubkey: String): Result<Unit> =
        control.run(cfg, ServerCommand.PeerRemove(pubkey)).asUnit()

    suspend fun addClient(cfg: SshConfig, name: String, clientId: String): Result<Unit> =
        control.run(cfg, ServerCommand.ClientAdd(nameB64(name), clientId)).asUnit()

    suspend fun removeClient(cfg: SshConfig, clientId: String): Result<Unit> =
        control.run(cfg, ServerCommand.ClientRemove(clientId)).asUnit()

    // До 64 символов: влезает в b64-лимит скрипта и не раздувает маркер в conf.
    private fun nameB64(name: String): String = Base64.getEncoder()
        .encodeToString(name.trim().take(64).toByteArray(Charsets.UTF_8))
}
