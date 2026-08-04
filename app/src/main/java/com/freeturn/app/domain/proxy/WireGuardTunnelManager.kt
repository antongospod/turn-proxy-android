package com.freeturn.app.domain.proxy

import android.content.Context
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.SplitTunnelMode
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.data.config.splitTunnelSelection
import com.freeturn.app.data.isPackageInstalled
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Поднимает WireGuard-туннель поверх локального прокси.
 * Заменяет Endpoint в первом [Peer] на localPort прокси.
 */
class WireGuardTunnelManager(context: Context) {

    private val appContext = context.applicationContext
    private val backend by lazy { GoBackend(appContext) }
    private val tunnelRef = AtomicReference<NamedTunnel?>(null)
    // Без сериализации stop во время старта оставлял туннель поднятым (no-op до tunnelRef.set).
    private val mutex = Mutex()

    /** Поднять туннель после того как прокси-ядро установило соединение. No-op без WG-конфига. */
    suspend fun startAfterProxyReady(cfg: ClientConfig) {
        if (!cfg.wireGuardActive) return
        val rawConfig = cfg.wireGuardConfig.trim()
        if (rawConfig.isBlank()) {
            ProxyServiceState.addLog("WireGuard: конфиг пуст, запуск пропущен")
            return
        }

        val name = cfg.wireGuardTunnelName.trim().ifBlank { TunnelTransport.DEFAULT_TUNNEL_NAME }
        val endpoint = cfg.localPort.trim()
        val preparedConfig = rawConfig
            .withLocalEndpoint(endpoint)
            .withMtu(ClientConfig.WG_MTU)
            .withSplitTunnel(
                appPackage = appContext.packageName,
                mode = cfg.splitTunnelMode,
                // Непоставленные пакеты отсеиваем - addDisallowedApplication валит туннель на них.
                packages = splitTunnelSelection(cfg.splitTunnelMode, cfg.splitTunnelApps)
                    .filter { appContext.isPackageInstalled(it) }
            )
        val config = Config.parse(
            ByteArrayInputStream(preparedConfig.toByteArray(StandardCharsets.UTF_8))
        )

        mutex.withLock {
            stopLocked()
            val tunnel = NamedTunnel(name)
            // Ссылка ДО setState: при провале откат/stop опустит частично поднятый туннель.
            tunnelRef.set(tunnel)
            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
            } catch (e: Exception) {
                stopLocked()
                throw e
            }
            ProxyServiceState.setTunnelActive(true)
            ProxyServiceState.addLog("WireGuard: туннель $name поднят через $endpoint")
        }
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    private fun stopLocked() {
        ProxyServiceState.setTunnelActive(false)
        val tunnel = tunnelRef.getAndSet(null) ?: return
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
            ProxyServiceState.addLog("WireGuard: туннель ${tunnel.name} остановлен")
        } catch (e: Exception) {
            ProxyServiceState.addLog("WireGuard: ошибка остановки ${tunnel.name}: ${e.message}")
        }
    }

    private class NamedTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName

        override fun onStateChange(newState: Tunnel.State) {
            ProxyServiceState.addLog("WireGuard: состояние $tunnelName -> $newState")
        }
    }
}

private fun String.withLocalEndpoint(endpoint: String): String {
    if (endpoint.isBlank()) return this
    var inPeer = false
    var replaced = false
    // Подменяем Endpoint только в первом [Peer].
    val lines = lineSequence().map { line ->
        val section = line.trim()
        if (section.startsWith("[") && section.endsWith("]")) {
            inPeer = section.equals("[Peer]", ignoreCase = true)
        }
        if (inPeer && !replaced && section.startsWith("Endpoint", ignoreCase = true) &&
            section.contains("=")) {
            replaced = true
            "Endpoint = $endpoint"
        } else {
            line
        }
    }.toMutableList()
    if (!replaced) {
        lines += ""
        lines += "Endpoint = $endpoint"
    }
    return lines.joinToString("\n")
}

private fun String.withMtu(mtu: Int): String {
    var inInterface = false
    val lines = mutableListOf<String>()
    lineSequence().forEach { line ->
        val section = line.trim()
        if (section.startsWith("[") && section.endsWith("]")) {
            inInterface = section.equals("[Interface]", ignoreCase = true)
        }
        val isMtuLine = inInterface && section.startsWith("MTU", ignoreCase = true) &&
            section.contains("=")
        if (!isMtuLine) lines += line
    }
    val interfaceIndex = lines.indexOfFirst {
        it.trim().equals("[Interface]", ignoreCase = true)
    }
    if (interfaceIndex < 0) return lines.joinToString("\n")
    lines.add(interfaceIndex + 1, "MTU = $mtu")
    return lines.joinToString("\n")
}

private fun String.withSplitTunnel(
    appPackage: String,
    mode: String,
    packages: List<String>
): String {
    var inInterface = false
    var inserted = false
    val lines = mutableListOf<String>()
    lineSequence().forEach { line ->
        val section = line.trim()
        if (section.startsWith("[") && section.endsWith("]")) {
            inInterface = section.equals("[Interface]", ignoreCase = true)
        }
        val isSplitLine = inInterface && (
            section.startsWith("IncludedApplications", ignoreCase = true) ||
                section.startsWith("ExcludedApplications", ignoreCase = true)
            )
        if (!isSplitLine) lines += line
    }

    val interfaceIndex = lines.indexOfFirst {
        it.trim().equals("[Interface]", ignoreCase = true)
    }
    if (interfaceIndex < 0) return lines.joinToString("\n")

    val splitLines = when (mode) {
        SplitTunnelMode.INCLUDE -> {
            val included = packages.filter { it != appPackage }.distinct()
            if (included.isEmpty()) listOf("ExcludedApplications = $appPackage")
            else listOf("IncludedApplications = ${included.joinToString(",")}")
        }
        SplitTunnelMode.EXCLUDE -> {
            val excluded = (packages + appPackage).distinct()
            listOf("ExcludedApplications = ${excluded.joinToString(",")}")
        }
        else -> listOf("ExcludedApplications = $appPackage")
    }

    if (splitLines.isNotEmpty()) {
        lines.addAll(interfaceIndex + 1, splitLines)
        inserted = true
    }
    return if (inserted) lines.joinToString("\n") else this
}
