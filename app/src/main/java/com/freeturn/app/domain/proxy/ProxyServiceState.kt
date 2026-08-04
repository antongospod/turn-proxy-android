package com.freeturn.app.domain.proxy

import com.freeturn.app.domain.CaptchaSession
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.StartupResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Централизованное состояние прокси-сервиса.
 * Публичный API - только read-only Flow, мутация через явные методы.
 */
object ProxyServiceState {

    private const val MAX_LOG_LINES = 200

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val logSeq = AtomicLong(0)

    private val _logsEnabled = MutableStateFlow(true)

    private val _proxyFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val proxyFailed: SharedFlow<Unit> = _proxyFailed.asSharedFlow()

    private val _startupResult = MutableStateFlow<StartupResult?>(null)
    val startupResult: StateFlow<StartupResult?> = _startupResult.asStateFlow()

    private val _captchaSession = MutableStateFlow<CaptchaSession?>(null)
    val captchaSession: StateFlow<CaptchaSession?> = _captchaSession.asStateFlow()

    private val _connectionStats = MutableStateFlow(ConnectionStats.IDLE)
    val connectionStats: StateFlow<ConnectionStats> = _connectionStats.asStateFlow()

    /** WG поднят поверх прокси: UI и нотификация говорят про туннель, а не про прокси. */
    private val _tunnelActive = MutableStateFlow(false)
    val tunnelActive: StateFlow<Boolean> = _tunnelActive.asStateFlow()

    /**
     * Момент первого подключения (SystemClock.elapsedRealtime()).
     * При watchdog-рестарте не сбрасывается.
     */
    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    /**
     * Сервис доломан до конца, включая остановку WG в фоновом потоке. `isRunning` гаснет
     * раньше - в начале `onDestroy`, - поэтому ждать надо именно этот флаг: иначе поздние
     * строки WG попадут в уже очищенный лог, а отложенный рестарт поднимет ядро с конфигом,
     * который к тому моменту успели подменить (restore бэкапа).
     */
    private val _teardownComplete = MutableStateFlow(true)
    val teardownComplete: StateFlow<Boolean> = _teardownComplete.asStateFlow()

    fun setRunning(value: Boolean) {
        _isRunning.value = value
    }

    fun markTeardownStarted() {
        _teardownComplete.value = false
    }

    fun markTeardownComplete() {
        _teardownComplete.value = true
    }

    fun setStartupResult(result: StartupResult?) {
        _startupResult.value = result
    }

    fun emitFailed() {
        _proxyFailed.tryEmit(Unit)
    }

    fun setLogsEnabled(value: Boolean) {
        _logsEnabled.value = value
    }

    fun addLog(msg: String) {
        if (!_logsEnabled.value) return
        val entry = LogEntry(logSeq.getAndIncrement(), msg, classifyLogLine(msg))
        _logs.update { current ->
            val next = current + entry
            if (next.size > MAX_LOG_LINES) next.drop(next.size - MAX_LOG_LINES) else next
        }
    }

    fun setCaptchaSession(session: CaptchaSession?) {
        _captchaSession.value = session
    }

    fun setConnectionStats(stats: ConnectionStats) {
        _connectionStats.value = stats
    }

    fun setTunnelActive(value: Boolean) {
        _tunnelActive.value = value
    }

    /** Запомнить момент первого подключения сессии. Повторные вызовы игнорируются. */
    fun markConnectedIfAbsent(nowElapsed: Long) {
        _connectedSince.compareAndSet(null, nowElapsed)
    }

    fun clearConnectedSince() {
        _connectedSince.value = null
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
