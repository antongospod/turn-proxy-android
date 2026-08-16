package com.freeturn.app.domain.proxy

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Состояние прокси между сервисом (пишет) и UI (читает). Process-global object:
 * сервис живёт вне графа DI, а состояние переживает его пересоздание.
 */
object ProxyStore {

    private const val MAX_LOG_LINES = 200
    private const val LOG_FLUSH_MS = 120L
    private const val ERROR_RESET_MS = 4_000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _status = MutableStateFlow(ProxyStatus())
    val status: StateFlow<ProxyStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val logSeq = AtomicLong(0)
    private val logBuffer = ArrayDeque<LogEntry>()
    private val flushScheduled = AtomicBoolean(false)
    // Пишется из UI, читается из горутин Go.
    @Volatile private var logsEnabled = true
    private val captchaSeq = AtomicLong(0)
    // Поколение показанной ошибки: таймер гасит только свою. Ошибки приходят из горутин
    // Go, сервиса и UI - хранить Job и отменять его было гонкой, а ошибку от ядра,
    // пришедшую следом за fail(), чужой таймер сбрасывал досрочно.
    private val errorSeq = AtomicLong(0)

    /** Команда старта принята: кнопка реагирует до того, как поднимется сервис. */
    fun starting() {
        errorSeq.incrementAndGet()
        _status.value = ProxyStatus(phase = ProxyPhase.Starting)
    }

    fun idle() {
        errorSeq.incrementAndGet()
        _status.value = ProxyStatus()
    }

    /** Сервис доломан. Ошибку не затираем - её ещё показывает кнопка. */
    fun finish() {
        if (_status.value.phase != ProxyPhase.Error) idle()
    }

    /** Сессия не состоялась. Ошибка сама гаснет - иначе кнопка залипает в красном. */
    fun fail(message: String) {
        val generation = errorSeq.incrementAndGet()
        _status.value = ProxyStatus(phase = ProxyPhase.Error, error = message)
        scope.launch {
            delay(ERROR_RESET_MS)
            // Своё поколение: за 4 секунды состояние могла сменить и новая ошибка,
            // и ядро - гасить чужое нельзя.
            if (errorSeq.get() == generation && _status.value.phase == ProxyPhase.Error) idle()
        }
    }

    /** Фаза от ядра. Момент подключения ставится один раз - рестарт его не сбивает. */
    fun setPhase(phase: ProxyPhase, active: Int, total: Int, error: String = "") {
        // Ошибка от ядра - своё поколение: висящий таймер прошлого fail() её не снимет.
        if (phase == ProxyPhase.Error) errorSeq.incrementAndGet()
        _status.update {
            val connected = if (phase == ProxyPhase.Connected) {
                it.connectedSince ?: SystemClock.elapsedRealtime()
            } else {
                it.connectedSince
            }
            it.copy(phase = phase, active = active, total = total, error = error, connectedSince = connected)
        }
    }

    fun setMetrics(active: Int, total: Int, rxRate: Long, txRate: Long, tunnelUp: Boolean) {
        _status.update {
            it.copy(active = active, total = total, rxRate = rxRate, txRate = txRate, tunnelUp = tunnelUp)
        }
    }

    /** Пустой [url] - капча снята. Фазу ведёт ядро, здесь только адрес окна. */
    fun setCaptcha(url: String) {
        _status.update {
            if (url.isBlank()) it.copy(captchaUrl = "")
            else it.copy(captchaUrl = url, captchaId = captchaSeq.incrementAndGet())
        }
    }

    fun setLogsEnabled(enabled: Boolean) {
        logsEnabled = enabled
    }

    fun log(message: String, level: LogLevel = LogLevel.Event) {
        if (!logsEnabled) return
        val entry = LogEntry(logSeq.getAndIncrement(), message, level)
        synchronized(logBuffer) {
            logBuffer.addLast(entry)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
        }
        scheduleFlush()
    }

    fun clearLogs() {
        synchronized(logBuffer) { logBuffer.clear() }
        _logs.value = emptyList()
    }

    /**
     * Ядро сыплет строками пачками из своих горутин: публикуем срез не чаще
     * [LOG_FLUSH_MS]. Флаг снимается до публикации - строка следом закажет новый флаш.
     */
    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(LOG_FLUSH_MS)
            flushScheduled.set(false)
            _logs.value = synchronized(logBuffer) { logBuffer.toList() }
        }
    }
}
