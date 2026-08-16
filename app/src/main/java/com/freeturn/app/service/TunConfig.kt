package com.freeturn.app.service

import android.content.Context
import android.net.VpnService
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.SplitTunnelMode
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.data.config.splitTunnelSelection
import com.freeturn.app.data.isPackageInstalled
import com.freeturn.app.domain.proxy.TunnelSetup

/**
 * Переносит параметры из WG-конфига и split-tunnel в билдер tun-интерфейса.
 * Ядро получает готовый дескриптор и маршрутизацией не занимается.
 *
 * [hotspot] - раздача через SOCKS5: своё приложение обязано остаться в туннеле,
 * иначе клиенты получат канал мимо него.
 */
fun VpnService.Builder.applyTunnel(
    context: Context,
    cfg: ClientConfig,
    setup: TunnelSetup,
    hotspot: Boolean,
): VpnService.Builder = apply {
    setMtu(setup.mtu)
    setSession(cfg.wireGuardTunnelName.trim().ifBlank { TunnelTransport.DEFAULT_TUNNEL_NAME })
    // Ядро читает tun само; блокирующий режим упёрся бы в его же горутину.
    setBlocking(false)

    setup.addresses.forEach { addr ->
        val (ip, prefix) = addr.splitCidr()
        addAddress(ip, prefix)
    }
    setup.allowedIPs.forEach { route ->
        val (ip, prefix) = route.splitCidr()
        addRoute(ip, prefix)
    }
    setup.dns.forEach { addDnsServer(it) }

    // Непоставленные пакеты кидают NameNotFoundException и рушат establish.
    val packages = splitTunnelSelection(cfg.splitTunnelMode, cfg.splitTunnelApps)
        .filter { context.isPackageInstalled(it) }
    when (cfg.splitTunnelMode) {
        // Своё имя не добавляем: сокеты ядра и так вне туннеля через protect().
        // Исключение - хотспот: в туннель ходит сам SOCKS5-сервер, и своё имя нужно
        // именно добавить - в пользовательском списке его может не быть вовсе.
        SplitTunnelMode.INCLUDE -> {
            val allowed =
                if (hotspot) packages + context.packageName
                else packages.filter { it != context.packageName }
            allowed.distinct().forEach { addAllowedApplication(it) }
        }
        // Своё имя пользователь мог выбрать руками - при хотспоте это выключило бы
        // раздачу, оставив клиентам прямой канал.
        SplitTunnelMode.EXCLUDE ->
            packages.filter { !(hotspot && it == context.packageName) }
                .forEach { addDisallowedApplication(it) }
        else -> Unit
    }
}

/** "10.8.0.2/32" -> ip + длина префикса; без маски - хостовый адрес. */
private fun String.splitCidr(): Pair<String, Int> {
    val ip = substringBefore('/')
    val prefix = substringAfter('/', "").toIntOrNull()
        ?: if (ip.contains(':')) 128 else 32
    return ip to prefix
}
