package com.freeturn.app.data.control

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Типизированные data-пейлоады команд (поле `data` в [ControlResponse]).
 * Контракт с server-control: имена полей = ключи JSON (см. 80-cmd.sh).
 */

@Serializable
data class ProbeData(
    val installed: Boolean = false,
    val version: String? = null,
    @SerialName("bin_sha256") val binSha256: String? = null,
    val running: Boolean = false,
    val mode: String? = null,
    val obf: String? = null,
    val runtime: String = "",
    val euid: Int = -1,
    val wg: WgInfo = WgInfo(),
    val virt: String = "",
    @SerialName("wg_kernel") val wgKernel: Boolean = false,
    val conflicts: Conflicts = Conflicts(),
)

@Serializable
data class WgInfo(
    val present: Boolean = false,
    val port: Int? = null,
)

@Serializable
data class Conflicts(
    val warp: Boolean = false,
    val x3ui: Boolean = false,
    val wgeasy: Boolean = false,
    val tailscale: Boolean = false,
    @SerialName("other_ifaces") val otherIfaces: List<String> = emptyList(),
)

@Serializable
data class InstallData(
    val stage: String = "ok",
    val bin: String = "",
    val version: String? = null,
    val runtime: String = "",
    @SerialName("needs_restart") val needsRestart: Boolean = false,
)

@Serializable
data class WgSetupData(
    val wg: WgSetupInfo = WgSetupInfo(),
    @SerialName("client_conf_b64") val clientConfB64: String? = null,
)

@Serializable
data class WgSetupInfo(
    val port: Int = 0,
    val existed: Boolean = false,
)

@Serializable
data class ShareInfoData(
    @SerialName("wg_backend") val wgBackend: Boolean = false,
    val mode: String = "",
    @SerialName("obf_profile") val obfProfile: String = "",
    @SerialName("obf_key") val obfKey: String = "",
)

@Serializable
data class ShareListData(
    val peers: List<PeerDto> = emptyList(),
    @SerialName("self_pub") val selfPub: String = "",
    val clients: List<ClientDto> = emptyList(),
)

@Serializable
data class PeerDto(
    val pub: String,
    @SerialName("name_b64") val nameB64: String? = null,
    val ip: String = "",
    val hs: Long? = null,
    @SerialName("has_conf") val hasConf: Boolean = false,
)

@Serializable
data class ClientDto(
    val id: String,
    @SerialName("name_b64") val nameB64: String? = null,
)

@Serializable
data class PeerAddData(
    val peer: PeerRef = PeerRef(),
    @SerialName("client_id") val clientId: String = "",
    @SerialName("client_conf_b64") val clientConfB64: String = "",
)

@Serializable
data class PeerRef(
    val pub: String = "",
    val ip: String = "",
)

@Serializable
data class PeerConfData(
    @SerialName("client_id") val clientId: String = "",
    @SerialName("client_conf_b64") val clientConfB64: String = "",
)

@Serializable
data class UninstallData(
    @SerialName("dry_run") val dryRun: Boolean = false,
    val removed: UninstallRemoved = UninstallRemoved(),
    @SerialName("wg_pkg_removed") val wgPkgRemoved: Boolean = false,
    /** Что НЕ тронули (чужой WG, ip_forward, общий пакет) - для пояснения в UI. */
    val kept: List<String> = emptyList(),
)

@Serializable
data class UninstallRemoved(
    val binary: Boolean = false,
    val unit: Boolean = false,
    @SerialName("wg_iface") val wgIface: Boolean = false,
    val prefix: Boolean = false,
    val sysctl: Boolean = false,
    val ufw: Boolean = false,
    @SerialName("legacy_unit") val legacyUnit: Boolean = false,
)
