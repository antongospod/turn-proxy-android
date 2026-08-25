package com.freeturn.app.data.config

import com.freeturn.app.data.DnsList
import com.freeturn.app.data.server.ServerOpts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Зеркало `config.ClientJSON` из ядра. Декодер в Go строгий
 * (`DisallowUnknownFields`) и молча не теряет опечатки, поэтому имена полей
 * менять только вместе с Go-схемой.
 */
@Serializable
data class CoreConfigJson(
    val peer: String,
    val clientId: String,
    val subUrl: String,
    val provider: String,
    val routes: Boolean,
    val turn: Turn,
    val proxy: Proxy,
    val vk: Vk,
    val obf: Obf,
    val dns: Dns,
    val log: Log,
    /** Только для `proxy.mode = tcp`: в udp ядро отвергает любое отличие от своего дефолта. */
    val kcp: Kcp? = null,
    val tunnel: Tunnel,
) {
    @Serializable
    data class Turn(val n: Int, val transport: String, val host: String, val port: String)

    @Serializable
    data class Proxy(val mode: String, val listen: String)

    @Serializable
    data class Kcp(
        val noDelay: Int,
        val interval: Int,
        val resend: Int,
        val nc: Int,
        val sndWnd: Int,
        val rcvWnd: Int,
        val mtu: Int,
        val ackNoDelay: Boolean,
    )

    @Serializable
    data class Vk(
        val links: List<String>,
        val streamsPerCred: Int,
        val manualCaptcha: Boolean,
        val platform: String,
    )

    @Serializable
    data class Obf(val profile: String, val key: String, val timingMs: Int)

    @Serializable
    data class Dns(val mode: String, val servers: List<String>)

    @Serializable
    data class Log(val debug: Boolean)

    @Serializable
    data class Tunnel(val mode: String, val config: String, val mtu: Int)

    companion object {
        // Все поля явно: пропущенное ядро молча заменит своим дефолтом. Исключение -
        // kcp: null не сериализуется, и дефолты ARQ остаются за ядром.
        private val json = Json { encodeDefaults = true; explicitNulls = false }

        const val TRANSPORT_TCP = "tcp"
        const val TRANSPORT_UDP = "udp"
        const val PROXY_MODE_TCP = "tcp"
        const val PROXY_MODE_UDP = "udp"
        const val TUNNEL_MODE_NONE = "none"
        const val TUNNEL_MODE_WG = "wg"
        const val PLATFORM_MOBILE = "mobile"

        fun encode(cfg: CoreConfigJson): String = json.encodeToString(cfg)
    }
}

/**
 * Конфиг ядра для `Mobile.start`/`startTunnel`.
 *
 * [carrierDns] - DNS физической сети; уходит в `dns.servers` только когда
 * ручного списка нет и включён свитч "DNS оператора".
 */
fun ClientConfig.toCoreJson(
    srv: ServerOpts,
    carrierDns: String? = null,
    ownClientId: String = "",
): String {
    val manualDns = DnsList.normalize(customDns)
    val dnsServers = when {
        manualDns.isNotBlank() -> manualDns.split(",")
        useCarrierDns -> carrierDns?.split(",").orEmpty()
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }

    val obfOn = srv.obfEnabled && ObfProfile.isValidKey(srv.obfKey)
    // Ядро поднимает встроенный туннель только поверх udp; UI тоже гасит выбор tcp.
    val tcpMode = srv.tcpMode && !wireGuardActive

    return CoreConfigJson.encode(
        CoreConfigJson(
            peer = serverAddress,
            clientId = clientId.ifBlank { ownClientId },
            // Подписок в приложении нет: узел всегда задан руками или ссылкой.
            subUrl = "",
            provider = provider,
            // Маршрутами на Android рулит VpnService.
            routes = false,
            turn = CoreConfigJson.Turn(
                // Поле обязательное, а CLI без -n брал свой дефолт.
                n = threads.takeIf { it > 0 } ?: ClientConfig.DEFAULT_THREADS,
                transport = if (useUdp) CoreConfigJson.TRANSPORT_UDP else CoreConfigJson.TRANSPORT_TCP,
                host = if (magicSwitch) magicTurn.trim() else "",
                port = "",
            ),
            proxy = CoreConfigJson.Proxy(
                mode = if (tcpMode) CoreConfigJson.PROXY_MODE_TCP else CoreConfigJson.PROXY_MODE_UDP,
                // В туннельном режиме порт не биндится (ядро берёт in-memory pipe),
                // но валидацию проходит и нужен прокси-режиму.
                listen = localPort,
            ),
            vk = CoreConfigJson.Vk(
                links = listOf(vkLink),
                streamsPerCred = streamsPerCred.takeIf { it > 0 }
                    ?: ClientConfig.DEFAULT_STREAMS_PER_CRED,
                manualCaptcha = manualCaptcha,
                platform = CoreConfigJson.PLATFORM_MOBILE,
            ),
            obf = CoreConfigJson.Obf(
                profile = if (obfOn) srv.obfProfile else ObfProfile.NONE,
                key = if (obfOn) srv.obfKey else "",
                timingMs = 0,
            ),
            dns = CoreConfigJson.Dns(mode = dnsMode, servers = dnsServers),
            log = CoreConfigJson.Log(debug = debugMode),
            kcp = if (tcpMode) srv.kcp.toCoreJson() else null,
            tunnel = CoreConfigJson.Tunnel(
                mode = if (wireGuardActive) CoreConfigJson.TUNNEL_MODE_WG
                else CoreConfigJson.TUNNEL_MODE_NONE,
                config = if (wireGuardActive) wireGuardConfig else "",
                mtu = ClientConfig.WG_MTU,
            ),
        )
    )
}

private fun KcpProfile.toCoreJson() = CoreConfigJson.Kcp(
    noDelay = noDelay,
    interval = interval,
    resend = resend,
    nc = nc,
    sndWnd = sndWnd,
    rcvWnd = rcvWnd,
    mtu = mtu,
    ackNoDelay = ackNoDelay,
)
