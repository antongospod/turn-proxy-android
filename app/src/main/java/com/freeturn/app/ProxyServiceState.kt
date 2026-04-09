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

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
