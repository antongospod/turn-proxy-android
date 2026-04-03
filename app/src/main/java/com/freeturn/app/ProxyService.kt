package com.freeturn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.freeturn.app.data.AppPreferences
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class StartupResult {
    data object Success : StartupResult()
    data class Failed(val message: String) : StartupResult()
}

class ProxyService : Service() {

    companion object {
        const val MAX_RESTARTS = 8
        private const val MAX_LOG_LINES = 200
        val isRunning = MutableStateFlow(false)
        val logs = MutableStateFlow<List<String>>(emptyList())
        val proxyFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val startupResult = MutableStateFlow<StartupResult?>(null)


        fun addLog(msg: String) {
            logs.update { current ->
                val next = current + msg
                if (next.size > MAX_LOG_LINES) next.drop(next.size - MAX_LOG_LINES) else next
            }
        }

        fun clearLogs() {
            logs.value = emptyList()
        }

    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var openAppIntent: PendingIntent? = null

    // P1-1: AtomicReference вместо @Volatile — проверка null + вызов destroy() атомарны
    private val process = AtomicReference<Process?>(null)

    // P1-1: AtomicBoolean — флаг читается и пишется из разных потоков
    private val userStopped = AtomicBoolean(false)

    // P1-1: AtomicBoolean — compareAndSet предотвращает двойной postDelayed при параллельных quota-ошибках
    private val sessionKillScheduled = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkInitialized = false
    private var restartCount = 0

    // P1-2: CoroutineScope для запуска startBinaryProcess без runBlocking
    private lateinit var serviceScope: CoroutineScope

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val channel = NotificationChannel("ProxyChannel", "Proxy", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning.value) return START_STICKY

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

        isRunning.value = true
        userStopped.set(false)
        restartCount = 0

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        wakeLock?.acquire(TimeUnit.HOURS.toMillis(24))

        registerNetworkCallback()

        addLog("=== ЗАПУСК ПРОКСИ ===")
        // P1-2: запуск через корутину на Dispatchers.IO — никакого runBlocking
        serviceScope.launch { startBinaryProcess() }

        return START_STICKY
    }

    // P1-2: suspend fun — читает DataStore напрямую без runBlocking
    private suspend fun startBinaryProcess() {
        if (userStopped.get()) return

        // P2-3: applicationContext вместо this@ProxyService
        val cfg = AppPreferences(applicationContext).clientConfigFlow.first()

        val customBin = File(filesDir, "custom_vkturn")
        val executable = if (customBin.exists()) {
            addLog("Используется кастомное ядро из памяти телефона")
            customBin.absolutePath
        } else {
            addLog("Используется стандартное ядро из APK")
            "${applicationInfo.nativeLibraryDir}/libvkturn.so"
        }

        val cmdArgs = mutableListOf<String>()

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex())
            cmdArgs.add(executable)
            if (parts.size > 1) cmdArgs.addAll(parts.subList(1, parts.size))
        } else {
            cmdArgs.add(executable)
            cmdArgs.add("-peer"); cmdArgs.add(cfg.serverAddress)
            
            cmdArgs.add(if (cfg.vkLink.contains("yandex")) "-yandex-link" else "-vk-link")
            cmdArgs.add(cfg.vkLink)
            cmdArgs.add("-listen"); cmdArgs.add(cfg.localPort)
            if (cfg.threads > 0) { cmdArgs.add("-n"); cmdArgs.add(cfg.threads.toString()) }
            if (cfg.useUdp) cmdArgs.add("-udp")
            if (cfg.noDtls) cmdArgs.add("-no-dtls")
        }

        var exitCode = -1
        val startedAt = System.currentTimeMillis()
        var startupEmitted = false
        var startupFailed = false
        try {
            addLog("Команда: ${cmdArgs.joinToString(" ")}")

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
                    addLog(l)

                    // P2-1: сигнализируем результат запуска по первой строке stdout
                    if (!startupEmitted) {
                        val lower = l.lowercase()
                        if (lower.contains("panic") || lower.contains("fatal") ||
                            lower.contains("rate limit")) {
                            startupResult.value = StartupResult.Failed(l)
                            updateNotification("VK TURN Proxy", "Ошибка подключения")
                            startupFailed = true
                        } else {
                            startupResult.value = StartupResult.Success
                            updateNotification("VK TURN Proxy", "Прокси активен")
                        }
                        startupEmitted = true
                    }

                    // Session Killer: compareAndSet гарантирует что postDelayed вызывается только раз
                    // даже если две quota-ошибки придут одновременно из одного потока чтения
                    if (isQuotaError(l) && sessionKillScheduled.compareAndSet(false, true)) {
                        addLog(">>> QUOTA ERROR — сброс сессии через 2с")
                        handler.postDelayed({
                            sessionKillScheduled.set(false)
                            if (!userStopped.get()) {
                                restartCount = 0
                                // P2-5: destroyForcibly() — SIGKILL, нативный процесс не игнорирует
                                val p = process.get()
                                p?.destroyForcibly()
                            }
                        }, 2_000)
                    }
                }
            }

            exitCode = if (withContext(Dispatchers.IO) {
                    proc.waitFor(5, TimeUnit.MINUTES)
                }) proc.exitValue() else -1
            addLog("=== ПРОЦЕСС ОСТАНОВЛЕН (Код: $exitCode) ===")
            if (!startupEmitted) {
                startupResult.value = StartupResult.Failed(
                    "Процесс завершился без вывода (код: $exitCode)")
                startupFailed = true
            }

        } catch (e: Exception) {
            addLog("КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
        } finally {
            process.set(null)
            when {
                userStopped.get() -> {
                    isRunning.value = false
                    stopSelf()
                }
                startupFailed -> {
                    addLog("=== Ошибка при запуске, watchdog не активирован ===")
                    isRunning.value = false
                    // Убираем proxyFailed.tryEmit, так как startProxy и так обработает StartupResult.Failed
                    stopSelf()
                }
                exitCode == 0 -> {
                    val uptime = System.currentTimeMillis() - startedAt
                    if (uptime < 5_000L) {
                        addLog("=== Быстрый выход (${uptime}мс) — проверьте VK-ссылку и настройки ===")
                    } else {
                        addLog("=== Сессия завершена нормально ===")
                    }
                    isRunning.value = false
                    stopSelf()
                }
                else -> scheduleWatchdogRestart()
            }
        }
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private fun scheduleWatchdogRestart() {
        restartCount++
        if (restartCount > MAX_RESTARTS) {
            addLog("=== WATCHDOG: превышен лимит попыток ($MAX_RESTARTS), остановка ===")
            isRunning.value = false
            proxyFailed.tryEmit(Unit)
            stopSelf()
            return
        }
        val baseDelay = minOf(1_000L * restartCount, 30_000L)
        val jitter = Random.nextLong(0, 500)
        val delay = baseDelay + jitter
        addLog("=== WATCHDOG: перезапуск через ${delay}мс (попытка $restartCount/$MAX_RESTARTS) ===")
        updateNotification("VK TURN Proxy", "Переподключение ($restartCount/$MAX_RESTARTS)...")
        handler.postDelayed({
            // Проверка userStopped + запуск через serviceScope вместо thread {}
            if (!userStopped.get()) serviceScope.launch { startBinaryProcess() }
        }, delay)
    }

    // ── Network Handover ──────────────────────────────────────────────────────

    private var networkDebounceJob: kotlinx.coroutines.Job? = null

    private fun registerNetworkCallback() {
        networkInitialized = false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
                        addLog("=== СМЕНА СЕТИ — ПЕРЕЗАПУСК ===")
                        updateNotification("VK TURN Proxy", "Смена сети, переподключение...")
                        restartCount = 0
                        val p = process.get()
                        p?.destroyForcibly()
                    }
                }
            }
        }
        networkCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun updateNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, "ProxyChannel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isQuotaError(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("486") || l.contains("quota") || l.contains("allocation quota")
    }

    override fun onDestroy() {
        super.onDestroy()
        userStopped.set(true)
        isRunning.value = false
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        addLog("=== ОСТАНОВКА ИЗ ИНТЕРФЕЙСА ===")
        // P2-5: destroyForcibly() вместо destroy()
        process.get()?.destroyForcibly()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
