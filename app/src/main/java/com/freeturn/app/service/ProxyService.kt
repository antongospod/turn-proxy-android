package com.freeturn.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.freeturn.app.R
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.CoreCommand
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.toCoreJson
import com.freeturn.app.domain.proxy.LogLevel
import com.freeturn.app.domain.proxy.ProxyEngine
import com.freeturn.app.domain.proxy.ProxyPhase
import com.freeturn.app.domain.proxy.ProxyStore
import com.freeturn.app.domain.proxy.SocketProtector
import com.freeturn.app.domain.proxy.Socks5Server
import com.freeturn.app.domain.proxy.TunHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import org.koin.android.ext.android.inject

/**
 * Foreground-`VpnService`: держит tun-интерфейс и жизненный цикл сессии
 * [ProxyEngine]. Трафик идёт мимо - его ведёт ядро.
 *
 * Сессия адресуется id ядра ([session]): отмена корутины её не рвёт (`establish`
 * и вызовы ядра блокирующие), поэтому каждый шаг сверяется с текущим id, а
 * отменённую заявку ядро отбрасывает само.
 */
class ProxyService : VpnService() {

    private val prefs: AppPreferences by inject()
    private val engine: ProxyEngine by inject()

    private lateinit var scope: CoroutineScope
    private lateinit var notifier: ProxyNotifier
    private lateinit var network: NetworkHandoverMonitor

    private var tun: ParcelFileDescriptor? = null
    private var socks5: Socks5Server? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Сколько устройство успело проспать к прошлой проверке: разница elapsedRealtime
    // (идёт во сне) и uptimeMillis (стоит) - и есть накопленный сон.
    @Volatile private var sleptMillis = 0L

    // Нотификация должна сказать про туннель раньше, чем метрики его увидят.
    @Volatile private var tunnelMode = false
    // Остановка решена: всё, что поднимет хвост уже начатого старта, сворачиваем сразу.
    @Volatile private var stopping = false
    // Заявка ядру на текущую сессию: гасим по ней именно свою, а не следующую.
    @Volatile private var session = 0L
    // Гасимся всегда по последнему startId: свежий START делает остановку неактуальной.
    @Volatile private var lastStartId = 0
    // Сессию сворачивает либо STOP, либо onDestroy - кто успел первым.
    private val shutdownDone = AtomicBoolean(false)

    // Экран зажёгся после глубокого сна - аллокации протухли, пинаем ядро сразу, не
    // дожидаясь его гэп-детектора (тик 30 c). Короткие блокировки экрана пропускаем:
    // рецикл на каждой разблокировке рвал бы живые стримы на ровном месте.
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val slept = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
            val gap = slept - sleptMillis
            sleptMillis = slept
            if (gap < DEEP_SLEEP_KICK_MS) return
            // Длительность сна - опора при разборе отвалов: по ней видно, пережила ли
            // аллокация паузу и не мы ли сами её выбросили.
            ProxyStore.log("Пробуждение после сна ${gap / 1000} c - пинок ядру")
            engine.wake()
        }
    }

    /** Ядро закрывает то, что ему отдали, поэтому наружу уходит только копия. */
    private val tunHandle = TunHandle { checkNotNull(tun).dup().detachFd() }
    private val protector = SocketProtector { fd -> protect(fd) }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        notifier = ProxyNotifier(this)
        notifier.createChannels()
        network = NetworkHandoverMonitor(applicationContext, scope) { onNetworkHandover() }
        sleptMillis = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
        // Только динамически: SCREEN_ON манифестом не ловится.
        ContextCompat.registerReceiver(
            this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scope.launch { observeStatus() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Отмена могла догнать ещё не обработанный START: гасимся, не поднимая ядро.
        // stopSelf(startId), а не stopSelf(): START, пришедший следом за отменой,
        // делает её неактуальной - иначе он поднял бы сессию в умирающем сервисе.
        if (intent?.action == ProxyActions.STOP) {
            shutdown("команда STOP")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        lastStartId = startId
        // Sticky-рестарт вернул сервис без intent: своей заявки у экземпляра нет, а
        // гасить живую сессию при смерти он обязан - иначе ядро остаётся крутиться,
        // а его копия tun-дескриптора держит VPN поднятым до конца процесса.
        if (intent == null && engine.isRunning) session = engine.currentSession

        // startForeground - первым, иначе ForegroundServiceDidNotStartInTimeException.
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, ProxyNotifier.NOTIF_ID_FG, notifier.build(), type)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException и родня: сессии не будет.
            fail("Не удалось запустить foreground-сервис: ${e.message}")
            return START_NOT_STICKY
        }

        // START - всегда свежая сессия (настройки могли поменяться); пустой intent -
        // возврат после sticky-рестарта, там поднимаем, только если ядро не живёт.
        if (intent?.action == ProxyActions.START || !engine.isRunning) {
            val previous = session
            val next = engine.newSession()
            session = next
            val fresh = intent?.action == ProxyActions.START
            // Инстанс мог уже свернуть сессию (STOP при забинденном сервисе его не
            // уничтожает): для новой сессии он снова рабочий, флаги снимаем.
            stopping = false
            shutdownDone.set(false)
            scope.launch {
                // Sticky-рестарт после отказа: fail() снял намерение, а система вернула
                // сервис. Без этой проверки он поднимал сессию заново - и так по кругу,
                // сжигая персону и кредиты VK на каждом витке.
                if (!fresh && !prefs.proxyDesiredFlow.first()) {
                    ProxyStore.log("Сервис возвращён системой, но прокси выключен - не поднимаем")
                    shutdown("возврат сервиса без намерения")
                    stopSelf(lastStartId)
                    return@launch
                }
                // Прошлая сессия могла ещё подниматься: сначала ядро отпускает свою
                // копию fd, только потом закрываем прошлый интерфейс.
                engine.stop(previous)
                // Хвост прошлой сессии целиком: раньше снимался только tun, а её SOCKS5
                // оставался на порту - новая падала бы с "Address already in use".
                releaseSessionOf(next)
                startSession(next, fresh)
            }
        }
        return START_STICKY
    }

    /** [fresh] - команда пользователя; иначе это возврат сервиса после смерти процесса. */
    private suspend fun startSession(session: Long, fresh: Boolean) {
        val cfg = prefs.clientConfigFlow.first()
        ProxyStore.setLogsEnabled(cfg.logsEnabled)
        // Лог рестарта не чистим: строка "Процесс запущен" от App - единственный след того,
        // что процесс убивали, и после clearLogs от неё ничего бы не осталось.
        if (fresh) ProxyStore.clearLogs()
        ProxyStore.log(if (fresh) "Запуск прокси" else "Восстановление после перезапуска процесса")

        if (cfg.serverAddress.isBlank() || cfg.vkLink.isBlank()) {
            fail("Не заполнены настройки клиента")
            return
        }

        val json = buildConfigJson(cfg)
        val argv = try {
            engine.configToArgs(json)
        } catch (e: Exception) {
            fail("Конфиг отклонён ядром: ${e.message}")
            return
        }
        ProxyStore.log("Команда: ${CoreCommand.redact(argv, prefs.privacyModeFlow.first())}")

        if (!isCurrent(session)) return

        acquireWakeLock()
        logEnvironment()
        // Флаг снимается только штатной остановкой: следующий запуск процесса по нему
        // отличит убийство системой от нормального выхода.
        prefs.setCleanExit(false)
        scope.launch { heartbeat(session) }
        network.register()

        tunnelMode = cfg.wireGuardActive
        // Раздача только поверх туннеля: без tun сокеты сервера ушли бы напрямую.
        val hotspot = tunnelMode && prefs.hotspotProxyEnabledFlow.first()
        if (tunnelMode) {
            // На старте интерфейс обязателен: без него сессии просто нет.
            when (val tunResult = openTun(cfg, session, hotspot)) {
                is TunResult.Failed -> {
                    fail(tunResult.message)
                    return
                }
                TunResult.Stale -> return
                TunResult.Ok -> Unit
            }
        }

        val started = try {
            engine.start(session, json, tun?.let { tunHandle }, protector)
        } catch (e: Exception) {
            fail("Ядро не запустилось: ${e.message}")
            return
        }
        // Заявку отменили, пока поднимался интерфейс: ядро её не взяло, интерфейс не нужен.
        if (!started) {
            closeTunOf(session)
            return
        }
        if (hotspot) startHotspot(session)
    }

    /**
     * Раздача поднимается последней и только для актуальной сессии: ядро уже взяло
     * заявку, а пока оно поднималось, её могли отменить.
     */
    @Synchronized
    private fun startHotspot(session: Long) {
        if (!isCurrent(session)) return
        // Порт занимает ровно один сервер: потерянный тут экземпляр держал бы 1080 до
        // смерти процесса.
        socks5?.stop()
        socks5 = Socks5Server(protect = { socket -> protect(socket) }).also { it.start() }
    }

    /** Исход попытки поднять tun. Судьбу сессии решает вызывающий, а не сама попытка. */
    private sealed interface TunResult {
        data object Ok : TunResult
        /** Заявку отменили, пока поднимался интерфейс - жаловаться не на что. */
        data object Stale : TunResult
        data class Failed(val message: String) : TunResult
    }

    private fun openTun(cfg: ClientConfig, session: Long, hotspot: Boolean): TunResult {
        val setup = try {
            engine.parseTunnel(cfg.wireGuardConfig, ClientConfig.WG_MTU)
        } catch (e: Exception) {
            return TunResult.Failed("WireGuard: конфиг не разобран - ${e.message}")
        }

        val pfd = try {
            Builder().applyTunnel(applicationContext, cfg, setup, hotspot).establish()
        } catch (e: Exception) {
            return TunResult.Failed("VPN-интерфейс не поднят: ${e.message}")
        }
        // null - пользователь не дал согласия: старт из тайла, виджета или
        // broadcast'а идёт мимо экрана, где его спрашивают.
        if (pfd == null) return TunResult.Failed(getString(R.string.notif_proxy_vpn_permission))

        return if (adoptTun(pfd, session)) TunResult.Ok else TunResult.Stale
    }

    /**
     * Дескриптор принимает только актуальная сессия: `establish` блокирующий, и
     * её могли отменить, пока он поднимал интерфейс - тогда закрываем сразу, иначе
     * VPN остался бы висеть до смерти процесса.
     */
    @Synchronized
    private fun adoptTun(pfd: ParcelFileDescriptor, session: Long): Boolean {
        if (!isCurrent(session)) {
            pfd.close()
            return false
        }
        tun?.close()
        tun = pfd
        return true
    }

    private suspend fun buildConfigJson(cfg: ClientConfig): String = cfg.toCoreJson(
        srv = prefs.serverOptsFlow.first(),
        carrierDns = if (cfg.useCarrierDns) network.physicalDnsServers() else null,
        ownClientId = prefs.ownClientId(),
    )

    private fun onNetworkHandover() {
        // stopping, а не только isRunning: остановка идёт в фоне, и ядро всё ещё живо -
        // без проверки рестарт поднимал бы сессию, которую сворачивают.
        if (stopping || !engine.isRunning) return
        val slept = (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis() - sleptMillis) / 1000
        ProxyStore.log("Смена сети - переподключение (сон с прошлой проверки $slept c)")
        scope.launch {
            val cfg = prefs.clientConfigFlow.first()
            engine.reconnect(if (cfg.useCarrierDns) network.physicalDnsServers() else "")
        }
    }

    /** Нотификация ведётся тем же состоянием, что видит UI. */
    private suspend fun observeStatus() {
        ProxyStore.status.collect { status ->
            // После решения об остановке молчим: нотификация уже снята.
            if (stopping) return@collect
            notifier.update(status, tunnelMode)
            // Ошибка ядра - сессии больше нет: держать поднятый tun не за чем,
            // иначе трафик уходит в интерфейс, за которым никого.
            if (status.phase == ProxyPhase.Error) {
                shutdown("ошибка ядра: ${status.error}")
                stopSelf(lastStartId)
            }
        }
    }

    /** Заявка ещё актуальна? Отменённая молчит: её ошибки уже не про текущую сессию. */
    private fun isCurrent(session: Long) = !stopping && session == this.session

    /** Свой интерфейс, а не чужой: следующая сессия могла уже поднять и принять свой. */
    @Synchronized
    private fun closeTunOf(session: Long) {
        if (isCurrent(session)) closeTun()
    }

    @Synchronized
    private fun closeTun() {
        tun?.close()
        tun = null
    }

    /**
     * Без исключения из оптимизации батареи система в Doze игнорирует wake lock и режет
     * приложению сеть, поэтому статус нужен в логе рядом с моментом отвала.
     */
    private fun logEnvironment() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        ProxyStore.log(
            "Окружение: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
                "батарея-исключение=${pm.isIgnoringBatteryOptimizations(packageName)}, " +
                "doze=${pm.isDeviceIdleMode}"
        )
    }

    /**
     * Метка живого процесса. Обрыв этих строк - точный момент, когда процесс заморозили
     * или убили: остальной лог в этот момент уже молчит, и отличить одно от другого
     * иначе нечем.
     */
    private suspend fun heartbeat(session: Long) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        while (isCurrent(session)) {
            delay(HEARTBEAT_MS)
            if (!isCurrent(session)) return
            val slept = (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()) / 1000
            ProxyStore.log(
                "hb up=${SystemClock.elapsedRealtime() / 1000}s сон=${slept}s doze=${pm.isDeviceIdleMode}",
                LogLevel.Plain
            )
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // Без таймаута: сессия живёт дольше суток, release гарантирован в shutdown.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreeTurn::Session").apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /** Всегда false - удобно возвращать из веток, где сессия не состоялась. */
    private fun fail(message: String): Boolean {
        // Уже гасимся - об отменённой сессии сообщать нечего.
        if (stopping) return false
        stopping = true
        // Сессия не состоялась по своей вине (конфиг, отказ системы) - восстанавливать
        // нечего: без вмешательства пользователя следующая попытка упрётся в то же самое.
        prefs.setProxyDesired(false)
        ProxyStore.log(message, LogLevel.Error)
        ProxyStore.fail(message)
        shutdown(message)
        stopSelf(lastStartId)
        return false
    }

    /**
     * Сворачивает сессию. Зовётся из обработки STOP, а не только из [onDestroy]:
     * пока tun поднят, система держит `VpnService` забинденным, и `stopSelf` его
     * не уничтожает - надеясь на `onDestroy`, мы оставляли бы ядро крутиться с
     * открытой копией дескриптора, а тот держал бы VPN, а VPN - сервис.
     *
     * Идемпотентна: STOP и следующий за ним onDestroy не должны гасить дважды.
     */
    @Synchronized
    private fun shutdown(reason: String) {
        if (!shutdownDone.compareAndSet(false, true)) return
        stopping = true
        val session = this.session
        notifier.cancelCaptcha()
        // Причина обязательна: по логу после гибернации надо отличать команду пользователя
        // от ошибки ядра и от отзыва VPN системой.
        ProxyStore.log("Остановка: $reason")
        prefs.setCleanExit(true)
        ProxyStore.finish()
        releaseAll()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Процессный scope движка: onDestroy и onStartCommand блокировать нельзя, а
        // свой scope сервис вот-вот отменит. Гасим именно свою сессию - ядро уже могло
        // перейти к следующей. Этим же снимается заявка, не дошедшая до ядра.
        engine.stopAsync(session)
    }

    /**
     * Ресурсы сессии [session] - слушающий порт раздачи и tun. Чужие не трогает:
     * следующая сессия могла уже поднять свои, и она же их и освободит.
     */
    @Synchronized
    private fun releaseSessionOf(session: Long) {
        if (isCurrent(session)) releaseAll()
    }

    /**
     * Безусловно - сервис уходит и обязан отпустить всё.
     *
     * Свою копию fd отпускаем сразу, не дожидаясь ядра: `Mobile.stop` ждёт сессию (в
     * туннеле - секунды), а зависни он совсем - интерфейс остался бы поднятым до
     * смерти процесса. Ядро продолжает писать в свою копию, она валидна.
     */
    @Synchronized
    private fun releaseAll() {
        network.unregister()
        socks5?.stop()
        socks5 = null
        closeTun()
    }

    /**
     * VPN перехватило другое приложение (или пользователь отключил его в системных
     * настройках). Дефолт зовёт `stopSelf` мимо [shutdown] - ядро осталось бы крутиться
     * с открытой копией tun-дескриптора. Намерение снимаем: восстанавливать сессию,
     * которую только что отобрали, значит драться с системой.
     */
    override fun onRevoke() {
        prefs.setProxyDesired(false)
        ProxyStore.log("VPN отключён системой", LogLevel.Warning)
        shutdown("VPN отозван системой")
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdown("сервис уничтожен")
        // Регистрация могла не состояться, если onCreate упал раньше.
        runCatching { unregisterReceiver(screenOn) }
        scope.cancel()
    }

    private companion object {
        // Тот же порог, что у гэп-детектора ядра: сон короче аллокации переживают.
        const val DEEP_SLEEP_KICK_MS = 60_000L
        const val HEARTBEAT_MS = 60_000L
    }
}
