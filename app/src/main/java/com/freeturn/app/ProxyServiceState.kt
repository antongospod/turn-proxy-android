package com.freeturn.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Сессия ручной капчи. sessionId позволяет диалогу различать соседние
 * капча-сессии с одинаковым URL и пересоздавать WebView через `key(sessionId)`.
 */
data class CaptchaSession(val url: String, val sessionId: Long)

/**
 * Агрегированная статистика подключений прокси-ядра.
 *
 * - [active] — число реально живых каналов (DTLS-потоков для не-VLESS, smux-сессий для VLESS).
 * - [total]  — целевое число каналов. 0 означает «ещё неизвестно» (VLESS до первой waiting-строки).
 */
data class ConnectionStats(val active: Int, val total: Int) {
    companion object {
        val IDLE = ConnectionStats(0, 0)
    }
}

/**
 * Централизованное состояние прокси-сервиса.
 * Публичный API — только read-only Flow, мутация через явные методы.
 */
object ProxyServiceState {

    private const val MAX_LOG_LINES = 200

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _proxyFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val proxyFailed: SharedFlow<Unit> = _proxyFailed.asSharedFlow()

    private val _startupResult = MutableStateFlow<StartupResult?>(null)
    val startupResult: StateFlow<StartupResult?> = _startupResult.asStateFlow()

    private val _captchaSession = MutableStateFlow<CaptchaSession?>(null)
    val captchaSession: StateFlow<CaptchaSession?> = _captchaSession.asStateFlow()

    private val _connectionStats = MutableStateFlow(ConnectionStats.IDLE)
    val connectionStats: StateFlow<ConnectionStats> = _connectionStats.asStateFlow()

    /**
     * Момент первого успешного подключения в рамках текущей сессии пользователя
     * (`SystemClock.elapsedRealtime()` — устойчиво к переводу часов).
     *
     * null = ни одного успешного подключения в этой сессии ещё не было, либо
     * прокси остановлен. Watchdog-рестарт НЕ сбрасывает значение: с точки
     * зрения пользователя он нажал «вкл» один раз, и время активности —
     * это время от первого Established до остановки.
     */
    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    fun setRunning(value: Boolean) {
        _isRunning.value = value
    }

    fun setStartupResult(result: StartupResult?) {
        _startupResult.value = result
    }

    fun emitFailed() {
        _proxyFailed.tryEmit(Unit)
    }

    fun addLog(msg: String) {
        _logs.update { current ->
            val next = current + msg
            if (next.size > MAX_LOG_LINES) next.drop(next.size - MAX_LOG_LINES) else next
        }
    }

    fun setCaptchaSession(session: CaptchaSession?) {
        _captchaSession.value = session
    }

    fun setConnectionStats(stats: ConnectionStats) {
        _connectionStats.value = stats
    }

    /**
     * Запомнить момент первого успешного подключения сессии. Повторные вызовы
     * игнорируются — таймер стартует один раз и не перезапускается при
     * watchdog-рестарте/временной потере всех потоков.
     */
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
