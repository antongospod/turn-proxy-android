package com.freeturn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.DnsMode
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class StartupResult {
    data object Success : StartupResult()
    data class Failed(val message: String) : StartupResult()
}

class ProxyService : Service() {

    companion object {
        const val MAX_RESTARTS = 8
        // Жёстко привязываемся к строке-объявлению капчи в бинарнике, чтобы
        // случайные localhost-URL в других логах не открывали диалог.
        private val CAPTCHA_URL_REGEX =
            Pattern.compile("""Open this URL in your browser:\s*(https?://\S+)""")

        // События жизненного цикла соединений, публикуемые ядром (client/main.go).
        // Не-VLESS: несколько потоков со своим [STREAM N], у каждого свой Established/Closed.
        private val STREAM_ESTABLISHED_REGEX =
            Pattern.compile("""\[STREAM (\d+)\] Established DTLS connection""")
        private val STREAM_CLOSED_REGEX =
            Pattern.compile("""\[STREAM (\d+)\] Closed DTLS connection""")
        // VLESS: ядро само пишет агрегированное число активных сессий.
        private val VLESS_ACTIVE_REGEX =
            Pattern.compile("""\[session \d+\] (?:connected|disconnected) \(active: (\d+)\)""")
        // VLESS: целевое число сессий приходит в этой строке до первого connected.
        private val VLESS_TOTAL_REGEX =
            Pattern.compile("""VLESS mode: waiting for sessions to connect \(total: (\d+)\)""")
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var openAppIntent: PendingIntent? = null

    private val process = AtomicReference<Process?>(null)
    private val userStopped = AtomicBoolean(false)
    private val sessionKillScheduled = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkInitialized = false
    @Volatile private var networkDebounceJob: kotlinx.coroutines.Job? = null
    private val restartCount = AtomicInteger(0)

    private lateinit var prefs: AppPreferences
    private lateinit var serviceScope: CoroutineScope

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(applicationContext)
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("ProxyChannel", "Proxy", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ProxyServiceState.isRunning.value) return START_STICKY

        openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, "ProxyChannel")
            .setContentTitle("VK TURN Proxy")
            .setContentText("Подключение...")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
        startForeground(1, notification)

        ProxyServiceState.setRunning(true)
        userStopped.set(false)
        restartCount.set(0)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        wakeLock?.acquire(TimeUnit.HOURS.toMillis(24))

        registerNetworkCallback()

        ProxyServiceState.addLog("=== ЗАПУСК ПРОКСИ ===")
        serviceScope.launch { startBinaryProcess() }

        return START_STICKY
    }

    private suspend fun startBinaryProcess() {
        if (userStopped.get()) return

        val cfg = prefs.clientConfigFlow.first()

        val customBin = File(filesDir, "custom_vkturn")
        val useCustom = customBin.exists()
        val executable = if (useCustom) {
            ProxyServiceState.addLog("Используется кастомное ядро из памяти телефона")
            customBin.absolutePath
        } else {
            ProxyServiceState.addLog("Используется стандартное ядро из APK")
            "${applicationInfo.nativeLibraryDir}/libvkturn.so"
        }

        val cmdArgs = mutableListOf<String>()

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            cmdArgs.add(executable)
            cmdArgs.addAll(parts.drop(1))
        } else {
            cmdArgs.add(executable)
            cmdArgs.add("-peer"); cmdArgs.add(cfg.serverAddress)

            cmdArgs.add(if (cfg.vkLink.contains("yandex")) "-yandex-link" else "-vk-link")
            cmdArgs.add(cfg.vkLink)
            cmdArgs.add("-listen"); cmdArgs.add(cfg.localPort)
            if (cfg.threads > 0) { cmdArgs.add("-n"); cmdArgs.add(cfg.threads.toString()) }
            if (cfg.vlessMode) cmdArgs.add("-vless")
            else if (cfg.useUdp) cmdArgs.add("-udp")
            if (cfg.manualCaptcha) cmdArgs.add("--manual-captcha")
            // -dns передаём только если пользователь выбрал не-дефолт: ядро по
            // умолчанию уже использует auto (UDP/53 с sticky-fallback на DoH).
            if (cfg.dnsMode in DnsMode.ALL && cfg.dnsMode != DnsMode.AUTO) {
                cmdArgs.add("-dns"); cmdArgs.add(cfg.dnsMode)
            }
            if (cfg.forcePort443) { cmdArgs.add("-port"); cmdArgs.add("443") }
            if (cfg.debugMode) cmdArgs.add("-debug")
        }

        // Кастомное ядро лежит в filesDir, откуда SELinux (untrusted_app) запрещает execve.
        // Запускаем через системный линкер: /system/bin/linker* — ему execve разрешён,
        // а целевой ELF мапится как данные. Работает для PIE-бинарников
        // (Go-сборки android/arm64 PIE по умолчанию).
        if (useCustom) {
            val linker = if (Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true) {
                "/system/bin/linker64"
            } else {
                "/system/bin/linker"
            }
            cmdArgs.add(0, linker)
        }

        var exitCode = -1
        val startedAt = System.currentTimeMillis()
        var startupEmitted = false
        var startupFailed = false
        var captchaSessionCounter = 0L

        // --- Трекинг активных соединений для индикации состояния в UI. ---
        // Не-VLESS: каждый поток логирует свой [STREAM N] Established/Closed парой
        // (defer Closed ставится ДО логирования Established, см. client/main.go).
        // Считаем именно инкрементами, а не множеством уникальных ID: в ядре есть
        // особенность — первый поток запускается с id=1 и цикл снова итерируется
        // с i=1, из-за чего один streamID дублируется при -n N. Для счётчика это
        // безопасно (на два Established придёт два Closed), для Set это давало
        // бы заниженное число активных (N-1 вместо N).
        var nonVlessActive = 0
        // Для не-VLESS целевое число потоков известно из конфига (-n). Если threads == 0,
        // ядро запускает один поток, считаем total = 1.
        val nonVlessTotal = if (cfg.isRawMode) 0 else if (cfg.threads > 0) cfg.threads else 1
        var vlessActive = 0
        var vlessTotal = 0
        var isVless = cfg.vlessMode

        fun publishStats() {
            val stats = if (isVless) {
                ConnectionStats(vlessActive, vlessTotal)
            } else {
                ConnectionStats(nonVlessActive, nonVlessTotal)
            }
            ProxyServiceState.setConnectionStats(stats)
        }
        // Сброс на старте сессии (в том числе на watchdog-рестарте).
        publishStats()
        try {
            ProxyServiceState.addLog("Команда: ${cmdArgs.joinToString(" ")}")

            val proc = withContext(Dispatchers.IO) {
                ProcessBuilder(cmdArgs)
                    .redirectErrorStream(true)
                    .start()
            }
            process.set(proc)

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    ProxyServiceState.addLog(l)

                    // Детекция URL ручной капчи. Каждый раз выдаём новый sessionId,
                    // чтобы диалог пересоздавал WebView, даже если URL не поменялся
                    // (бинарник всегда использует http://localhost:8765).
                    val captchaMatcher = CAPTCHA_URL_REGEX.matcher(l)
                    if (captchaMatcher.find()) {
                        val url = captchaMatcher.group(1)!!
                        captchaSessionCounter += 1
                        ProxyServiceState.setCaptchaSession(
                            CaptchaSession(url, captchaSessionCounter)
                        )
                    }

                    // Капча-сессия закончилась: бинарник либо завершил auth-чейн
                    // (Failed/Success), либо сама капча провалилась (timeout). Закрываем
                    // диалог — следующая капча-сессия откроет его заново через новый sessionId.
                    if (ProxyServiceState.captchaSession.value != null && (
                            l.contains("[VK Auth] Failed") ||
                            l.contains("[VK Auth] Success") ||
                            (l.contains("[Captcha]") && l.contains("failed"))
                        )) {
                        ProxyServiceState.setCaptchaSession(null)
                    }

                    // Парсинг событий жизненного цикла соединений. Обновляем stats
                    // и используем первое "реально подключилось" как сигнал успешного старта.
                    var statsChanged = false
                    STREAM_ESTABLISHED_REGEX.matcher(l).let { m ->
                        if (m.find()) {
                            nonVlessActive += 1
                            statsChanged = true
                            isVless = false
                        }
                    }
                    STREAM_CLOSED_REGEX.matcher(l).let { m ->
                        if (m.find()) {
                            if (nonVlessActive > 0) nonVlessActive -= 1
                            statsChanged = true
                        }
                    }
                    VLESS_TOTAL_REGEX.matcher(l).let { m ->
                        if (m.find()) {
                            vlessTotal = m.group(1)!!.toInt()
                            isVless = true
                            statsChanged = true
                        }
                    }
                    VLESS_ACTIVE_REGEX.matcher(l).let { m ->
                        if (m.find()) {
                            vlessActive = m.group(1)!!.toInt()
                            isVless = true
                            statsChanged = true
                        }
                    }
                    if (statsChanged) publishStats()

                    // Startup: ядро упало с panic/fatal/rate limit ДО того, как удалось
                    // подключиться — считаем запуск неудачным. Первая строка без этих
                    // маркеров больше не трактуется как Success (ядро могло написать
                    // "Connecting..." и только потом упасть).
                    if (!startupEmitted) {
                        val lower = l.lowercase()
                        val hasFatal = lower.contains("panic") || lower.contains("fatal") ||
                            lower.contains("rate limit")
                        val hasConnection = (isVless && vlessActive > 0) ||
                            (!isVless && nonVlessActive > 0)
                        when {
                            hasFatal -> {
                                ProxyServiceState.setStartupResult(StartupResult.Failed(l))
                                updateNotification("Ошибка подключения")
                                startupFailed = true
                                startupEmitted = true
                            }
                            hasConnection -> {
                                ProxyServiceState.setStartupResult(StartupResult.Success)
                                ProxyServiceState.markConnectedIfAbsent(SystemClock.elapsedRealtime())
                                updateNotification("Прокси активен")
                                startupEmitted = true
                            }
                        }
                    }

                    // compareAndSet гарантирует единственный postDelayed даже при параллельных quota-ошибках
                    if (isQuotaError(l) && sessionKillScheduled.compareAndSet(false, true)) {
                        ProxyServiceState.addLog(">>> QUOTA ERROR — сброс сессии через 2с")
                        handler.postDelayed({
                            sessionKillScheduled.set(false)
                            if (!userStopped.get()) {
                                restartCount.set(0)
                                process.get()?.destroyCompat()
                            }
                        }, 2_000)
                    }
                }
            }

            exitCode = if (withContext(Dispatchers.IO) {
                    proc.waitForCompat(5, TimeUnit.MINUTES)
                }) proc.exitValue() else -1
            ProxyServiceState.addLog("=== ПРОЦЕСС ОСТАНОВЛЕН (Код: $exitCode) ===")
            if (!startupEmitted) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(
                    "Процесс завершился без вывода (код: $exitCode)"))
            }

        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("error=13") || msg.contains("Permission denied")) {
                ProxyServiceState.addLog("КРИТИЧЕСКАЯ ОШИБКА: Отказано в запуске ядра — ваше устройство блокирует выполнение файлов из внутреннего хранилища (SELinux/noexec). Используйте встроенное ядро.")
                ProxyServiceState.setStartupResult(StartupResult.Failed(msg))
                startupFailed = true
            } else {
                ProxyServiceState.addLog("КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
            }
        } finally {
            ProxyServiceState.setCaptchaSession(null)
            // Процесс мёртв — активных соединений нет. При watchdog-рестарте
            // publishStats на новом старте снова выставит правильный target.
            ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
            process.set(null)
            when {
                userStopped.get() -> {
                    ProxyServiceState.setRunning(false)
                    stopSelf()
                }
                startupFailed -> {
                    ProxyServiceState.addLog("=== Ошибка при запуске, watchdog не активирован ===")
                    ProxyServiceState.setRunning(false)
                    // Убираем proxyFailed.tryEmit, так как startProxy и так обработает StartupResult.Failed
                    stopSelf()
                }
                exitCode == 0 -> {
                    val uptime = System.currentTimeMillis() - startedAt
                    if (uptime < 5_000L) {
                        ProxyServiceState.addLog("=== Быстрый выход (${uptime}мс) — проверьте VK-ссылку и настройки ===")
                    } else {
                        ProxyServiceState.addLog("=== Сессия завершена нормально ===")
                    }
                    ProxyServiceState.setRunning(false)
                    stopSelf()
                }
                else -> scheduleWatchdogRestart()
            }
        }
    }

    // Watchdog

    private fun scheduleWatchdogRestart() {
        val count = restartCount.incrementAndGet()
        if (count > MAX_RESTARTS) {
            ProxyServiceState.addLog("=== WATCHDOG: превышен лимит попыток ($MAX_RESTARTS), остановка ===")
            ProxyServiceState.setRunning(false)
            ProxyServiceState.emitFailed()
            stopSelf()
            return
        }
        val baseDelay = minOf(1_000L * count, 30_000L)
        val jitter = Random.nextLong(0, 500)
        val delay = baseDelay + jitter
        ProxyServiceState.addLog("=== WATCHDOG: перезапуск через ${delay}мс (попытка $count/$MAX_RESTARTS) ===")
        updateNotification("Переподключение ($count/$MAX_RESTARTS)...")
        handler.postDelayed({
            if (!userStopped.get()) serviceScope.launch { startBinaryProcess() }
        }, delay)
    }

    // Network handover

    private fun registerNetworkCallback() {
        networkInitialized = false
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!networkInitialized) {
                    networkInitialized = true
                    return
                }
                
                // Дебаунс: отменяем предыдущий ждущий перезапуск, если он был
                networkDebounceJob?.cancel()
                networkDebounceJob = serviceScope.launch {
                    kotlinx.coroutines.delay(2000)
                    if (!userStopped.get() && process.get() != null) {
                        ProxyServiceState.addLog("=== СМЕНА СЕТИ — ПЕРЕЗАПУСК ===")
                        updateNotification("Смена сети, переподключение...")
                        restartCount.set(0)
                        val p = process.get()
                        p?.destroyCompat()
                    }
                }
            }
        }
        networkCallback = cb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(cb)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, cb)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    // Notification

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, "ProxyChannel")
            .setContentTitle("VK TURN Proxy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    // Helpers

    private fun isQuotaError(line: String): Boolean =
        line.lowercase().contains("quota")

    override fun onDestroy() {
        super.onDestroy()
        userStopped.set(true)
        ProxyServiceState.setRunning(false)
        ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
        ProxyServiceState.clearConnectedSince()
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        ProxyServiceState.addLog("=== ОСТАНОВКА ИЗ ИНТЕРФЕЙСА ===")
        process.get()?.destroyCompat()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}

private fun Process.destroyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
}

private fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return waitFor(timeout, unit)
    val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
    while (System.currentTimeMillis() < deadline) {
        try { exitValue(); return true } catch (_: IllegalThreadStateException) { Thread.sleep(100) }
    }
    return try { exitValue(); true } catch (_: IllegalThreadStateException) { false }
}
