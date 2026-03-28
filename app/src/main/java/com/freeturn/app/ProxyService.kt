package com.freeturn.app

import android.app.NotificationChannel
import android.app.NotificationManager
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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class ProxyService : Service() {

    companion object {
        const val MAX_RESTARTS = 8
        val isRunning = MutableStateFlow(false)
        val logs = MutableStateFlow<List<String>>(emptyList())
        val proxyFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        fun addLog(msg: String) {
            logs.update { (it + msg).takeLast(200) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var process: Process? = null
    @Volatile private var userStopped = false
    @Volatile private var sessionKillScheduled = false
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkInitialized = false
    private var restartCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("ProxyChannel", "Proxy", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning.value) return START_STICKY

        val notification = NotificationCompat.Builder(this, "ProxyChannel")
            .setContentTitle("VK TURN Proxy")
            .setContentText("Работает в фоне")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .build()
        startForeground(1, notification)

        isRunning.value = true
        userStopped = false
        restartCount = 0

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        @Suppress("WakelockTimeout")
        wakeLock?.acquire() // foreground service сам управляет временем жизни; release() в onDestroy()

        registerNetworkCallback()

        addLog("=== ЗАПУСК ПРОКСИ ===")
        thread { startBinaryProcess() }

        return START_STICKY
    }

    private fun startBinaryProcess() {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val isRaw = prefs.getBoolean("isRaw", false)

        val customBin = File(filesDir, "custom_vkturn")
        val executable = if (customBin.exists()) {
            addLog("Используется кастомное ядро из памяти телефона")
            customBin.absolutePath
        } else {
            addLog("Используется стандартное ядро из APK")
            "${applicationInfo.nativeLibraryDir}/libvkturn.so"
        }

        val cmdArgs = mutableListOf<String>()

        if (isRaw) {
            val rawCmd = prefs.getString("rawCmd", "") ?: ""
            val parts = rawCmd.trim().split("\\s+".toRegex())
            cmdArgs.add(executable)
            if (parts.size > 1) cmdArgs.addAll(parts.subList(1, parts.size))
        } else {
            val peer = prefs.getString("peer", "") ?: ""
            val link = prefs.getString("link", "") ?: ""
            val n = prefs.getString("n", "") ?: ""
            val listen = prefs.getString("listen", "127.0.0.1:9000") ?: ""

            cmdArgs.add(executable)
            cmdArgs.add("-peer"); cmdArgs.add(peer)
            cmdArgs.add(if (link.contains("yandex")) "-yandex-link" else "-vk-link")
            cmdArgs.add(link)
            cmdArgs.add("-listen"); cmdArgs.add(listen)
            if (n.isNotEmpty()) { cmdArgs.add("-n"); cmdArgs.add(n) }
            if (prefs.getBoolean("udp", false)) cmdArgs.add("-udp")
            if (prefs.getBoolean("noDtls", false)) cmdArgs.add("-no-dtls")
        }

        var exitCode = -1
        val startedAt = System.currentTimeMillis()
        try {
            addLog("Команда: ${cmdArgs.joinToString(" ")}")

            val proc = ProcessBuilder(cmdArgs)
                .redirectErrorStream(true)
                .start()
            process = proc

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    addLog(l)

                    // Session Killer: обнаружение ошибки квоты → сброс сессии
                    if (!sessionKillScheduled && isQuotaError(l)) {
                        sessionKillScheduled = true
                        addLog(">>> QUOTA ERROR — сброс сессии через 2с")
                        handler.postDelayed({
                            sessionKillScheduled = false
                            if (!userStopped) {
                                restartCount = 0  // сброс бэкоффа — квота не краш
                                process?.destroy()
                            }
                        }, 2_000)
                    }
                }
            }

            exitCode = if (proc.waitFor(5, TimeUnit.MINUTES)) proc.exitValue() else -1
            addLog("=== ПРОЦЕСС ОСТАНОВЛЕН (Код: $exitCode) ===")

        } catch (e: Exception) {
            addLog("КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
        } finally {
            process = null
            when {
                userStopped -> {
                    isRunning.value = false
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
        handler.postDelayed({
            if (!userStopped) thread { startBinaryProcess() }
        }, delay)
    }

    // ── Network Handover ──────────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        networkInitialized = false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!networkInitialized) {
                    // Первый вызов — текущая сеть при регистрации, игнорируем
                    networkInitialized = true
                    return
                }
                if (!userStopped && process != null) {
                    addLog("=== СМЕНА СЕТИ — ПЕРЕЗАПУСК ===")
                    restartCount = 0  // сброс бэкоффа — не краш
                    process?.destroy()
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isQuotaError(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("486") || l.contains("quota") || l.contains("allocation quota")
    }

    override fun onDestroy() {
        super.onDestroy()
        userStopped = true
        isRunning.value = false
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        addLog("=== ОСТАНОВКА ИЗ ИНТЕРФЕЙСА ===")
        process?.destroy()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
