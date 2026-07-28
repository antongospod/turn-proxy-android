package com.freeturn.app.data.config

data class ClientConfig(
    val serverAddress: String = "",
    val vkLink: String = "",
    val provider: String = Provider.VK,
    val threads: Int = DEFAULT_THREADS,
    val streamsPerCred: Int = DEFAULT_STREAMS_PER_CRED,
    val useUdp: Boolean = false,
    val manualCaptcha: Boolean = false,
    val localPort: String = DEFAULT_LOCAL_PORT,
    val isRawMode: Boolean = false,
    val rawCommand: String = "",
    val tcpForward: Boolean = false,
    val bond: Boolean = false,
    val debugMode: Boolean = false,
    val useCarrierDns: Boolean = true,
    val dnsMode: String = DnsMode.AUTO,
    val customDns: String = "",
    val syncServerSwitches: Boolean = true,
    val magicSwitch: Boolean = false,
    val magicTurn: String = "",
    val tunnelTransport: String = TunnelTransport.NONE,
    val wireGuardConfig: String = "",
    val wireGuardTunnelName: String = TunnelTransport.DEFAULT_TUNNEL_NAME,
    val wireGuardMtu: Int = DEFAULT_WG_MTU,
    val splitTunnelMode: String = SplitTunnelMode.EXCLUDE,
    /**
     * Package-имена для include/exclude (разделители: запятая/пробел/перенос строки).
     * Пустой в exclude-режиме = дефолтный список рос-сервисов (см. [splitTunnelSelection]).
     */
    val splitTunnelApps: String = "",
    val logsEnabled: Boolean = true,
    val clientId: String = ""
) {
    val wireGuardActive: Boolean
        get() = tunnelTransport == TunnelTransport.WIREGUARD && wireGuardConfig.isNotBlank()

    companion object {
        const val DEFAULT_LOCAL_PORT = "127.0.0.1:9000"
        const val DEFAULT_THREADS = 12
        const val DEFAULT_STREAMS_PER_CRED = 12
        // Минимум IPv6 снижает риск фрагментации поверх TURN.
        const val DEFAULT_WG_MTU = 1280
        const val MIN_WG_MTU = 1280
        const val MAX_WG_MTU = 1500
    }
}
