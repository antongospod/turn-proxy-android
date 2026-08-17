package com.freeturn.app.domain.proxy

import com.freeturn.core.mobile.EventSink
import com.freeturn.core.mobile.Mobile
import com.freeturn.core.mobile.Protector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Защита сокетов ядра от заворачивания в свой же VpnService. Реализация - в `service`. */
fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}

/** Дескриптор tun. [dupFd] отдаёт копию: ядро закрывает то, что ему передали. */
fun interface TunHandle {
    fun dupFd(): Int
}

/**
 * Параметры tun-интерфейса из WG-конфига. Ядро их не применяет - это работа
 * платформы (`VpnService.Builder`).
 */
data class TunnelSetup(
    val addresses: List<String>,
    val dns: List<String>,
    val allowedIPs: List<String>,
    val mtu: Int,
)

/**
 * Единственное место, которое знает про `Mobile` (Koin `single`).
 *
 * Сессия адресуется id из [newSession]: заявка, отменённая или обогнанная более
 * поздней, ядро не поднимает, а [stop] гасит только свою сессию. Это и есть
 * защита от гонки "сервис умер, а начатый им старт дошёл до ядра секундой позже":
 * отмена корутины таких стартов не рвёт - вызовы ядра и `establish` блокирующие.
 *
 * Вызовы ядра сериализованы [lock] и неотменяемы: `Mobile.stop` ждёт фактической
 * остановки, `Mobile.start` синхронно валидирует конфиг и поднимает устройство.
 *
 * [stateDir] - каталог, где ядро держит состояние между запусками (поколение
 * VK-персоны, кэш TURN-реквизитов). Свои дефолты ядра из app-uid не пишутся.
 */
class ProxyEngine(private val stateDir: String) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Mutex()

    // JNI держит ссылки только со стороны Go - без своих полей GC заберёт объекты.
    private val sink = CoreEventSink()
    private var protector: Protector? = null

    // getAndSet, а не volatile-поле: снимают поллер и stop (до лока), и startPolling
    // (под локом) - при простом чтении-записи ссылка на живой цикл терялась бы.
    private val poller = AtomicReference<Job?>(null)

    // Метрики нужны только открытому экрану: в фоне их никто не читает (нотификация
    // скорости не показывает), а опрос будил бы процесс каждые 2 c всю сессию.
    @Volatile private var metricsWanted = false

    // Заявки: последняя выданная и последняя отменённая.
    private val issued = AtomicLong(0)
    private val cancelled = AtomicLong(0)

    /** id сессии, которую ядро держит прямо сейчас; 0 - ядро стоит. */
    @Volatile private var running = 0L

    val version: String get() = Mobile.version()

    val isRunning: Boolean get() = running != 0L

    /** Заявка живой сессии; 0 - ядро стоит. Для хоста, поднятого системой без своей заявки. */
    val currentSession: Long get() = running

    /**
     * Заявка на сессию. Владелец гасит по этому id только свою: пока он
     * останавливается, его место мог занять следующий старт.
     */
    fun newSession(): Long = issued.incrementAndGet()

    /**
     * [tun] задан - туннельный режим: ядро само поднимает wg/awg на этом
     * дескрипторе, а сокеты релея уходят мимо туннеля через [socketProtector].
     *
     * false - заявку успели отменить или обогнать: ядро не тронуто, вызвавшему
     * остаётся свернуть поднятый под неё интерфейс.
     */
    suspend fun start(
        session: Long,
        configJson: String,
        tun: TunHandle?,
        socketProtector: SocketProtector?
    ): Boolean = lock.withLock {
        if (session != issued.get() || session <= cancelled.get()) return@withLock false
        // Ядро держит одну сессию, Start на живой отвечает отказом - место освобождаем
        // сами. Безусловно: stop() снимает running до взятия лока, так что по флагу
        // живость ядра не проверить, а Stop на стоящем ядре - дешёвый no-op.
        stopLocked()
        // До вызова ядра: события первой же секунды уже наши.
        running = session
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                // Перед стартом, а не в init: тут уже IO-поток, и загрузка нативной
                // библиотеки не встаёт поперёк создания графа DI.
                Mobile.setStateDir(stateDir)
                Mobile.setEventSink(sink)
                // Только при туннеле: в прокси-режиме protect увёл бы ядро мимо чужого
                // активного VPN - поведение, которого сейчас нет.
                protector = socketProtector?.takeIf { tun != null }?.let { p ->
                    Protector { fd -> p.protect(fd.toInt()) }.also { Mobile.setProtect(it) }
                }
                if (tun != null) Mobile.startTunnel(configJson, tun.dupFd().toLong())
                else Mobile.start(configJson)
            }
        } catch (e: Throwable) {
            running = 0L
            throw e
        }
        startPolling()
        true
    }

    /**
     * Пересоздание сессии под одним локом ядра (смена сети). Неотменяем: брошенный
     * на полпути рестарт оставил бы ядро на сети, которой уже нет.
     */
    suspend fun restart(session: Long, configJson: String, tun: TunHandle?) =
        withContext(NonCancellable) {
            lock.withLock {
                if (running != session) return@withLock
                withContext(Dispatchers.IO) {
                    Mobile.restart(configJson, tun?.dupFd()?.toLong() ?: 0L)
                }
                startPolling()
            }
        }

    /**
     * Гасит сессию [session] и отменяет её же, если она ещё не дошла до ядра.
     * Чужую сессию не трогает - её ведёт тот, кто её заказал.
     *
     * Неотменяема целиком: `running` снимается до лока, и отмена на самом локе
     * оставила бы "ядро стоит" при живом ядре - с копией tun-fd до смерти процесса.
     * Вызывается в том числе из scope сервиса, который вот-вот отменят.
     */
    suspend fun stop(session: Long) = withContext(NonCancellable) {
        if (session == 0L) return@withContext
        cancelled.updateAndGet { maxOf(it, session) }
        // Флаг снимаем до лока: Mobile.stop блокирует до фактической остановки (в
        // туннеле - секунды), и всё это время живое ядро шлёт события. Иначе после
        // "стоп" всплывает капча уже погашенной сессии.
        if (running == session) {
            running = 0L
            stopPolling()
        }
        // За лок идём всегда, даже увидев running == 0: наша заявка могла быть уже
        // внутри start, за этим самым локом, и вот-вот поднять ядро (стоп сразу
        // после старта). Ранний выход по неатомарному чтению оставлял её жить.
        lock.withLock {
            // Наша сессия тут либо уже поднята start'ом (running == session), либо не
            // поднималась вовсе (0) - гасим оба случая. Чужую, более позднюю, не трогаем:
            // её ведёт тот, кто её заказал.
            if (running != 0L && running != session) return@withLock
            stopLocked()
        }
    }

    /**
     * Остановка с процессного scope. Для хоста, который гасит сессию на своей смерти:
     * его собственный scope отменяется раньше, чем ядро успевает остановиться.
     */
    fun stopAsync(session: Long) {
        scope.launch { stop(session) }
    }

    private suspend fun stopLocked() {
        running = 0L
        stopPolling()
        withContext(Dispatchers.IO + NonCancellable) {
            // Отписка раньше остановки - ядро гаснет не мгновенно.
            Mobile.setEventSink(null)
            Mobile.setProtect(null)
            Mobile.stop()
        }
        protector = null
    }

    /** Экран с метриками виден. Выключение обнуляет скорости - иначе они замрут на экране. */
    fun setMetricsEnabled(enabled: Boolean) {
        if (metricsWanted == enabled) return
        metricsWanted = enabled
        if (!enabled) {
            stopPolling()
            ProxyStore.clearRates()
            return
        }
        if (running != 0L) startPolling()
    }

    /**
     * Устройство проснулось: стримы бросают backoff и пересоздают TURN-аллокации,
     * не дожидаясь гэп-детектора ядра (тик 30 c) и тем более провалов ChannelBind.
     *
     * Мимо [lock]: ядро само проверяет, есть ли живая сессия, а ждать за локом
     * долгий `start`/`stop` смысла нет - к их концу пинок уже неактуален.
     */
    fun wake() {
        if (running == 0L) return
        scope.launch {
            runCatching { Mobile.wake() }
                .onFailure { ProxyStore.log("Пробуждение ядра не удалось: ${it.message}", LogLevel.Warning) }
        }
    }

    /**
     * Разбор того же текста, что уходит в `tunnel.config`. [mtu] обязан совпасть
     * с `tunnel.mtu`, иначе устройство ядра и tun разъедутся.
     */
    fun parseTunnel(wgConfig: String, mtu: Int): TunnelSetup {
        val p = Mobile.parseTunnelConfig(wgConfig, mtu.toLong())
        return TunnelSetup(
            addresses = p.addresses.splitList(),
            dns = p.dns.splitList(),
            allowedIPs = p.allowedIPs.splitList(),
            mtu = p.mtu.toInt(),
        )
    }

    /** Эквивалентная CLI-строка для экрана "команда" (гарантированно совпадает с ядром). */
    fun configToArgs(configJson: String): String = Mobile.configToArgs(configJson)

    /** Пустая строка - конфиг валиден. */
    fun validate(configJson: String): String = Mobile.validateConfig(configJson)

    /** Метрики: событий на них нет, ядро отдаёт только снимок. */
    private fun startPolling() {
        if (!metricsWanted) return
        val next = scope.launch {
            while (isActive && running != 0L) {
                delay(POLL_INTERVAL_MS)
                if (running == 0L) return@launch
                val snap = Mobile.getState()
                ProxyStore.setMetrics(
                    active = snap.streams.toInt(),
                    total = snap.total.toInt(),
                    rxRate = snap.rxRate,
                    txRate = snap.txRate,
                    tunnelUp = Mobile.tunnelStats().up,
                )
            }
        }
        poller.getAndSet(next)?.cancel()
    }

    private fun stopPolling() {
        poller.getAndSet(null)?.cancel()
    }

    /**
     * Колбэки приходят из горутин Go: блокировать их нельзя, поэтому только
     * запись в StateFlow. После [stop] игнорируются - ядро гаснет не мгновенно,
     * а его хвост не должен оживлять выключённый UI.
     */
    private inner class CoreEventSink : EventSink {

        override fun onState(state: String, streams: Long, total: Long, errMsg: String) {
            if (running == 0L) return
            ProxyStore.setPhase(state.toPhase(), streams.toInt(), total.toInt(), errMsg)
        }

        override fun onLog(level: String, msg: String, unixMillis: Long) {
            if (running == 0L) return
            ProxyStore.log(msg, level.toLogLevel())
        }

        override fun onCaptcha(url: String) {
            if (running == 0L) return
            ProxyStore.setCaptcha(url)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}

// gomobile не умеет []string - списки приходят строкой через запятую.
private fun String.splitList(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun String.toPhase(): ProxyPhase = when (this) {
    Mobile.StateConnecting -> ProxyPhase.Connecting
    Mobile.StateConnected -> ProxyPhase.Connected
    Mobile.StateCaptcha -> ProxyPhase.Captcha
    Mobile.StateError -> ProxyPhase.Error
    else -> ProxyPhase.Idle
}

private fun String.toLogLevel(): LogLevel = when (this) {
    Mobile.LevelError -> LogLevel.Error
    Mobile.LevelWarn -> LogLevel.Warning
    else -> LogLevel.Plain
}
