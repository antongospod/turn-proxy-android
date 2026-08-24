package com.freeturn.app.service
import com.freeturn.app.domain.proxy.ProxyStore

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Следит за сменой ФИЗИЧЕСКОЙ сети (Wi-Fi <-> LTE и т.п.) и дёргает [onHandover].
 * VPN-интерфейсы отфильтрованы: иначе старт WireGuard выглядел бы как смена сети
 * и уводил прокси в бесконечный рестарт.
 */
class NetworkHandoverMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onHandover: () -> Unit,
) {
    companion object {
        // Игнорируем сетевые события первые секунды после регистрации - иначе
        // initial onAvailable/onCapabilitiesChanged триггерят ложный рестарт.
        private const val WARMUP_MS = 3_000L
    }

    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var debounceJob: Job? = null
    @Volatile private var lastKey: String? = null
    // Сеть в linger: система уже увела на неё default, но сокеты ещё живут. Из выбора
    // приоритетной физсети исключена - иначе DNS и ключ остались бы от уходящей.
    @Volatile private var lingering: Network? = null

    fun register() {
        // START прилетает в живой сервис на каждом рестарте сессии: без снятия
        // прошлого колбэка они копятся (дубли переподключений, а после ~100
        // регистраций в процессе - TooManyRequestsException).
        unregister()
        val cm = cm
        val registeredAt = SystemClock.elapsedRealtime()
        lastKey = physicalNetworkKey(cm)

        fun schedule(reason: String) {
            if (SystemClock.elapsedRealtime() - registeredAt < WARMUP_MS) return
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(2_000)
                val oldKey = lastKey
                val newKey = physicalNetworkKey(cm)
                // Ключ тот же - ожидаемый no-op. onCapabilitiesChanged сыплет
                // десятки раз/мин (сигнал, link speed, валидация инета), не логаем.
                if (oldKey == newKey) return@launch
                lastKey = newKey
                if (newKey == null) {
                    ProxyStore.log("Сеть: физическая сеть недоступна ($reason)")
                    return@launch
                }
                onHandover()
            }
        }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network == lingering) lingering = null
                val caps = cm.getNetworkCapabilities(network)
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    ProxyStore.log("Сеть: VPN-событие проигнорировано")
                    return
                }
                schedule("available")
            }

            // Единственное окно, когда аллокации TURN можно освободить по живому сокету:
            // сеть ещё принимает трафик. Без debounce - оно короткое.
            override fun onLosing(network: Network, maxMsToLive: Int) {
                if (SystemClock.elapsedRealtime() - registeredAt < WARMUP_MS) return
                lingering = network
                val newKey = physicalNetworkKey(cm)
                if (newKey == null || newKey == lastKey) return
                debounceJob?.cancel()
                lastKey = newKey
                ProxyStore.log("Сеть: уходит через $maxMsToLive мс - переподключение заранее")
                onHandover()
            }

            override fun onLost(network: Network) {
                if (network == lingering) lingering = null
                schedule("lost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    return
                }
                schedule("capabilities")
            }
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, cb)
    }

    fun unregister() {
        // Debounce живёт своими 2 секундами и после снятия колбэка: без отмены он
        // дёргает onHandover уже на сворачиваемой сессии, а при перерегистрации -
        // мимо прогрева, рестартя только что поднятую.
        debounceJob?.cancel()
        debounceJob = null
        lingering = null
        callback?.let { cb ->
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        callback = null
    }

    /**
     * DNS ФИЗИЧЕСКОЙ сети (оператор/Wi-Fi) для `dns.servers` ядра. Именно физической:
     * при поднятом туннеле activeNetwork - наш же VPN, и в конфиг уехали бы адреса
     * из WG-конфига. Пусто, если сети нет или у linkProperties нет DNS (норма на эмуляторе).
     */
    fun physicalDnsServers(): String = try {
        val cm = cm
        physicalNetwork(cm)?.let { net ->
            cm.getLinkProperties(net)?.dnsServers
                ?.mapNotNull { it.hostAddress }
                ?.filter { it.isNotBlank() }
                ?.joinToString(",")
        }.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /**
     * Ключ ОДНОЙ приоритетной физсети (транспорт + iface). Берём приоритетную, а не
     * весь allNetworks: при активном Wi-Fi cellular флапает в фоне, набор прыгал бы ->
     * ложная "смена сети". link-адреса не в ключе - ротация IPv6/DHCP идёт на той же
     * сети; реальный хендовер меняет транспорт/iface.
     */
    private fun physicalNetworkKey(cm: ConnectivityManager): String? =
        rankedPhysicalNetworks(cm).minWithOrNull(comparator)?.let { "${it.transport}|${it.iface}" }

    /** Та же приоритетная физсеть, что даёт ключ - её DNS уходят в конфиг ядра. */
    private fun physicalNetwork(cm: ConnectivityManager): Network? =
        rankedPhysicalNetworks(cm).minWithOrNull(comparator)?.network

    private class Ranked(
        val priority: Int,
        val transport: String,
        val iface: String,
        val network: Network,
    )

    // tie-break по iface - детерминированный выбор при равном приоритете.
    private val comparator = compareBy<Ranked>({ it.priority }, { it.iface })

    private fun rankedPhysicalNetworks(cm: ConnectivityManager): List<Ranked> {
        // allNetworks deprecated с API 31, но это единственный синхронный способ снять
        // полный снимок текущих сетей внутри колбэка. Подавляем осознанно.
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { network ->
            if (network == lingering) return@mapNotNull null
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                return@mapNotNull null
            }
            val (priority, transport) = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0 to "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1 to "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2 to "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3 to "bluetooth"
                else -> return@mapNotNull null
            }
            Ranked(priority, transport, cm.getLinkProperties(network)?.interfaceName.orEmpty(), network)
        }
    }
}
