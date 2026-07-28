package com.freeturn.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.freeturn.app.R
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.CoreArgs
import com.freeturn.app.domain.CaptchaSession
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.proxy.CoreConnectionTracker
import com.freeturn.app.domain.proxy.CoreLogEvent
import com.freeturn.app.domain.proxy.CoreLogParser
import com.freeturn.app.domain.StartupResult
import com.freeturn.app.domain.proxy.MAX_PROXY_RESTARTS
import com.freeturn.app.domain.proxy.ProxyServiceState
import com.freeturn.app.domain.proxy.WireGuardTunnelManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class CoreProcessController(
    private val context: Context,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val notifier: ProxyNotifier,
    private val carrierDns: () -> String,
    private val onStopRequested: () -> Unit,
) {
    companion object {
        // Даём TURN-туннелю "устаканиться" перед поднятием WireGuard поверх него.
        private const val WIREGUARD_START_DELAY_MS = 2_000L
    }

    private val wireGuard = WireGuardTunnelManager(context)
    private val handler = Handler(Looper.getMainLooper())

    private val process = AtomicReference<Process?>(null)
    private val userStopped = AtomicBoolean(false)
    private val sessionKillScheduled = AtomicBoolean(false)
    private val restartCount = AtomicInteger(0)
    // Single-flight: двойной старт (tile+UI/watchdog) затёр бы первый процесс -> зомби.
    private val startInFlight = AtomicBoolean(false)

    val isRunning: Boolean get() = process.get() != null
    val isUserStopped: Boolean get() = userStopped.get()

    fun start() {
        userStopped.set(false)
        restartCount.set(0)
        scope.launch { startBinaryProcess() }
    }

    fun onNetworkHandover() {
        if (userStopped.get() || process.get() == null) return
        ProxyServiceState.addLog("Смена сети - переподключение")
        notifier.setStatus(context.getString(R.string.notif_proxy_network_change))
        restartCount.set(0)
        process.get()?.destroyCompat()
    }

    fun beginShutdown() {
        userStopped.set(true)
        handler.removeCallbacksAndMessages(null)
    }

    fun destroyProcessAndTunnel() {
        process.get()?.destroyCompat()
        val wg = wireGuard
        Thread {
            try {
                runBlocking { wg.stop() }
            } finally {
                ProxyServiceState.markTeardownComplete()
            }
        }.start()
    }

    private suspend fun startBinaryProcess() {
        if (userStopped.get()) return
        if (!startInFlight.compareAndSet(false, true)) return
        try {
            runProcessSession()
        } finally {
            startInFlight.set(false)
        }
    }

    private suspend fun runProcessSession() {
        if (userStopped.get()) return

        val cfg = prefs.clientConfigFlow.first()
        ProxyServiceState.setLogsEnabled(cfg.logsEnabled)
        val srv = prefs.serverOptsFlow.first()
        val privacy = prefs.privacyModeFlow.first()

        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val executableFile = libDir.listFiles { f ->
            f.name.startsWith("libfreeturn") && f.name.endsWith(".so")
        }?.maxByOrNull { it.name }

        if (executableFile == null) {
            ProxyServiceState.addLog(
                "Ядро libfreeturn*.so не найдено в ${libDir.path}. " +
                "Положите бинарник в jniLibs/arm64-v8a/ (имя начинается с lib и оканчивается на .so)."
            )
            ProxyServiceState.setStartupResult(StartupResult.Failed("core binary not found"))
            ProxyServiceState.setRunning(false)
            onStopRequested()
            return
        }
        val executable = executableFile.absolutePath

        val cmdArgs = mutableListOf<String>()

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            cmdArgs.add(executable)
            cmdArgs.addAll(parts.drop(1))
        } else {
            cmdArgs.add(executable)
            val carrierDnsValue = if (cfg.useCarrierDns) carrierDns() else null
            cmdArgs.addAll(CoreArgs.client(cfg, srv, carrierDnsValue, prefs.ownClientId()))
        }

        var exitCode = -1
        val startedAt = System.currentTimeMillis()
        var startupEmitted = false
        var startupFailed = false
        var wireGuardStarted = false
        var captchaSessionCounter = 0L

        val tracker = CoreConnectionTracker(
            udpTotal = if (cfg.isRawMode) 0 else if (cfg.threads > 0) cfg.threads else 1,
            tcpMode = cfg.tcpForward
        )

        fun publishStats() {
            ProxyServiceState.setConnectionStats(ConnectionStats(tracker.active, tracker.total))
            notifier.refreshStats()
        }
        publishStats()
        try {
            ProxyServiceState.addLog("Команда: ${CoreArgs.redactForLog(cmdArgs, privacy)}")

            val proc = withContext(Dispatchers.IO) {
                // Легаси: сносим осиротевшие профили захвата (сам механизм захвата удалён).
                context.filesDir.listFiles { f -> f.name.startsWith("vk_profile") }?.forEach { it.delete() }
                val pb = ProcessBuilder(cmdArgs).redirectErrorStream(true)
                // CWD подменяем на writeable dir для логов кэша tls-client и т.п.
                pb.directory(context.filesDir)
                pb.start()
            }
            process.set(proc)
            // Stop в окне старта: destroyProcessAndTunnel видел ещё null - убиваем сами.
            if (userStopped.get()) {
                proc.destroyCompat()
            }

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (true) {
                    line = try {
                        reader.readLine()
                    } catch (e: java.io.IOException) {
                        // При остановке процесса pipe закрывается, readLine() бросает IOException.
                        val msg = e.message.orEmpty()
                        val benign = userStopped.get() ||
                            msg.contains("interrupted by close", ignoreCase = true) ||
                            msg.contains("Stream closed", ignoreCase = true) ||
                            msg.contains("Bad file descriptor", ignoreCase = true)
                        if (!benign) {
                            ProxyServiceState.addLog("Чтение лога ядра прервано: ${e.message}")
                        }
                        null
                    }
                    if (line == null) break
                    val l = line
                    ProxyServiceState.addLog(l)

                    val events = CoreLogParser.parse(l)
                    var statsChanged = false
                    for (event in events) when (event) {
                        is CoreLogEvent.CaptchaUrl -> {
                            captchaSessionCounter += 1
                            ProxyServiceState.setCaptchaSession(
                                CaptchaSession(event.url, captchaSessionCounter)
                            )
                            notifier.showCaptcha()
                        }
                        CoreLogEvent.CaptchaResolved -> {
                            if (ProxyServiceState.captchaSession.value != null) {
                                ProxyServiceState.setCaptchaSession(null)
                                notifier.cancelCaptcha()
                            }
                        }
                        else -> if (tracker.apply(event)) statsChanged = true
                    }
                    if (statsChanged) publishStats()

                    if (!startupEmitted) {
                        val hasFatal = events.any { it is CoreLogEvent.FatalStartup }
                        val hasConnection = tracker.hasConnection
                        when {
                            hasFatal -> {
                                ProxyServiceState.setStartupResult(StartupResult.Failed(l))
                                notifier.setStatus(context.getString(R.string.notif_proxy_connect_error))
                                startupFailed = true
                                startupEmitted = true
                            }
                            hasConnection -> {
                                try {
                                    if (cfg.wireGuardActive) {
                                        ProxyServiceState.addLog(
                                            "WireGuard: подъём через ${WIREGUARD_START_DELAY_MS} мс после старта TURN-туннеля"
                                        )
                                        delay(WIREGUARD_START_DELAY_MS)
                                        if (userStopped.get() || process.get() !== proc) {
                                            ProxyServiceState.addLog(
                                                "WireGuard: старт отменён, прокси останавливается"
                                            )
                                            break
                                        }
                                    }
                                    wireGuard.startAfterProxyReady(cfg)
                                    wireGuardStarted = cfg.wireGuardActive
                                    ProxyServiceState.setStartupResult(StartupResult.Success)
                                    ProxyServiceState.markConnectedIfAbsent(SystemClock.elapsedRealtime())
                                    notifier.setStatus(
                                        if (wireGuardStarted) context.getString(R.string.proxy_active_wireguard)
                                        else context.getString(R.string.proxy_active)
                                    )
                                } catch (e: Exception) {
                                    val message = e.message ?: e.javaClass.simpleName
                                    ProxyServiceState.addLog("WireGuard: ошибка запуска - $message")
                                    ProxyServiceState.setStartupResult(
                                        StartupResult.Failed("WireGuard не запустился: $message")
                                    )
                                    notifier.setStatus(context.getString(R.string.notif_proxy_wireguard_error))
                                    startupFailed = true
                                    proc.destroyCompat()
                                }
                                startupEmitted = true
                            }
                        }
                    }

                    // compareAndSet гарантирует единственный postDelayed даже при параллельных quota-ошибках
                    if (events.any { it is CoreLogEvent.QuotaError } &&
                        sessionKillScheduled.compareAndSet(false, true)) {
                        ProxyServiceState.addLog("Превышена квота - сброс сессии через 2 с")
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
            ProxyServiceState.addLog("Процесс остановлен (код $exitCode)")
            if (!startupEmitted) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(
                    "Процесс завершился без вывода (код: $exitCode)"))
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Исключения при пользовательской остановке - следствие закрытия пайпов.
            if (userStopped.get()) {
                startupFailed = false
            } else {
                val msg = e.message ?: ""
                if (msg.contains("error=13") || msg.contains("Permission denied")) {
                    ProxyServiceState.addLog("Ошибка: устройство блокирует запуск файлов из внутреннего хранилища (SELinux/noexec). Используйте встроенное ядро.")
                    ProxyServiceState.setStartupResult(StartupResult.Failed(msg))
                    startupFailed = true
                } else {
                    ProxyServiceState.addLog("Ошибка: ${e.message}")
                }
            }
        } finally {
            ProxyServiceState.setCaptchaSession(null)
            notifier.cancelCaptcha()
            if (wireGuardStarted) wireGuard.stop()
            ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
            process.set(null)
            when {
                userStopped.get() -> {
                    ProxyServiceState.setRunning(false)
                    onStopRequested()
                }
                startupFailed -> {
                    ProxyServiceState.addLog("Ошибка при запуске, watchdog не активирован")
                    ProxyServiceState.setRunning(false)
                    onStopRequested()
                }
                exitCode == 0 -> {
                    val uptime = System.currentTimeMillis() - startedAt
                    if (uptime < 5_000L) {
                        ProxyServiceState.addLog("Быстрый выход (${uptime} мс) - проверьте ссылку и настройки")
                    } else {
                        ProxyServiceState.addLog("Сессия завершена")
                    }
                    ProxyServiceState.setRunning(false)
                    onStopRequested()
                }
                else -> scheduleWatchdogRestart()
            }
        }
    }

    private fun scheduleWatchdogRestart() {
        val count = restartCount.incrementAndGet()
        if (count > MAX_PROXY_RESTARTS) {
            ProxyServiceState.addLog("Watchdog: превышен лимит попыток ($MAX_PROXY_RESTARTS), остановка")
            ProxyServiceState.setRunning(false)
            ProxyServiceState.emitFailed()
            onStopRequested()
            return
        }
        val baseDelay = minOf(1_000L * count, 30_000L)
        val jitter = Random.nextLong(0, 500)
        val delayMs = baseDelay + jitter
        ProxyServiceState.addLog("Watchdog: перезапуск через ${delayMs} мс (попытка $count/$MAX_PROXY_RESTARTS)")
        notifier.setStatus(context.getString(R.string.notif_proxy_reconnecting, count, MAX_PROXY_RESTARTS))
        handler.postDelayed({
            if (!userStopped.get()) scope.launch { startBinaryProcess() }
        }, delayMs)
    }

}

