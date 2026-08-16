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

    private var wakeLock: PowerManager.WakeLock? = null
    private var tun: ParcelFileDescriptor? = null
    private var socks5: Socks5Server? = null

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

    // Экран зажёгся - устройство точно не спит: пинаем ядро, не дожидаясь его
    // собственного детектора сна (тик 5 c) и тем более провалов ChannelBind.
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = engine.wake()
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
            shutdown()
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
        network.register()

        tunnelMode = cfg.wireGuardActive
        // Раздача только поверх туннеля: без tun сокеты сервера ушли бы напрямую.
        val hotspot = tunnelMode && prefs.hotspotProxyEnabledFlow.first()
        if (tunnelMode && !openTun(cfg, session, hotspot)) return

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

    /** false - интерфейс не поднялся, сессия дальше не идёт. */
    private fun openTun(cfg: ClientConfig, session: Long, hotspot: Boolean): Boolean {
        val setup = try {
            engine.parseTunnel(cfg.wireGuardConfig, ClientConfig.WG_MTU)
        } catch (e: Exception) {
            return fail("WireGuard: конфиг не разобран - ${e.message}")
        }

        val pfd = try {
            Builder().applyTunnel(applicationContext, cfg, setup, hotspot).establish()
        } catch (e: Exception) {
            return fail("VPN-интерфейс не поднят: ${e.message}")
        }
        // null - пользователь не дал согласия: старт из тайла, виджета или
        // broadcast'а идёт мимо экрана, где его спрашивают.
        if (pfd == null) return fail(getString(R.string.notif_proxy_vpn_permission))

        return adoptTun(pfd, session)
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

    /** Пересборка конфига: DNS оператора мог смениться вместе с сетью. */
    private suspend fun buildConfigJson(cfg: ClientConfig): String = cfg.toCoreJson(
        srv = prefs.serverOptsFlow.first(),
        carrierDns = if (cfg.useCarrierDns) network.physicalDnsServers() else null,
        ownClientId = prefs.ownClientId(),
    )

    private fun onNetworkHandover() {
        // stopping, а не только isRunning: остановка идёт в фоне, и ядро всё ещё живо -
        // без проверки рестарт поднимал бы сессию, которую сворачивают.
        if (stopping || !engine.isRunning) return
        ProxyStore.log("Смена сети - переподключение")
        val session = this.session
        scope.launch {
            val cfg = prefs.clientConfigFlow.first()
            try {
                engine.restart(session, buildConfigJson(cfg), tun?.let { tunHandle })
            } catch (e: Exception) {
                ProxyStore.log("Перезапуск не удался: ${e.message}", LogLevel.Error)
            }
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
                shutdown()
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
        shutdown()
        stopSelf(lastStartId)
        return false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // Без таймаута: сессия живёт дольше суток, release гарантирован в shutdown.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreeTurn::Session").apply { acquire() }
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
    private fun shutdown() {
        if (!shutdownDone.compareAndSet(false, true)) return
        stopping = true
        val session = this.session
        notifier.cancelCaptcha()
        ProxyStore.log("Остановка")
        ProxyStore.finish()
        releaseAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        wakeLock?.takeIf { it.isHeld }?.release()
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
        shutdown()
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdown()
        unregisterReceiver(screenOn)
        scope.cancel()
    }
}
