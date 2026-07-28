package com.freeturn.app.domain.proxy

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.ProxyState
import com.freeturn.app.domain.StartupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Управляет жизненным циклом прокси-сервиса (Koin `single`, Application scope).
 */
class LocalProxyManager(private val launcher: ProxyServiceLauncher) {

    private val _proxyState = MutableStateFlow<ProxyState>(ProxyState.Idle)
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var resetJob: kotlinx.coroutines.Job? = null

    init {
        scope.launch { observeProxyLifecycle() }
        scope.launch { observeProxyServiceStatus() }
        scope.launch { observeConnectionStats() }
        scope.launch { observeCaptchaEvents() }
        syncInitialState()
    }

    private suspend fun observeProxyLifecycle() {
        ProxyServiceState.proxyFailed.collect {
            setErrorWithAutoReset("Прокси упал $MAX_PROXY_RESTARTS раз - проверьте настройки")
        }
    }

    private suspend fun observeCaptchaEvents() {
        ProxyServiceState.captchaSession.collect { session ->
            if (session != null) {
                _proxyState.value = ProxyState.CaptchaRequired(session.url, session.sessionId)
            } else if (_proxyState.value is ProxyState.CaptchaRequired) {
                val s = ProxyServiceState.connectionStats.value
                _proxyState.value = if (s.active > 0) {
                    ProxyState.Running(s.active, s.total)
                } else {
                    ProxyState.Connecting(s.active, s.total)
                }
            }
        }
    }

    private suspend fun observeProxyServiceStatus() {
        ProxyServiceState.isRunning.collect { running ->
            val current = _proxyState.value
            if (running) {
                if (current !is ProxyState.Running &&
                    current !is ProxyState.Connecting &&
                    current !is ProxyState.Starting) {
                    _proxyState.value = ProxyState.Starting
                }
            } else {
                if (current is ProxyState.Running ||
                    current is ProxyState.Connecting ||
                    current is ProxyState.Starting ||
                    current is ProxyState.CaptchaRequired) {
                    _proxyState.value = ProxyState.Idle
                }
            }
        }
    }

    /**
     * Переводит UI в Connecting/Running по числу активных каналов.
     * Приоритет у Captcha и Error.
     */
    private suspend fun observeConnectionStats() {
        ProxyServiceState.connectionStats.collect { stats ->
            val current = _proxyState.value
            if (current is ProxyState.Error || current is ProxyState.CaptchaRequired) return@collect
            if (!ProxyServiceState.isRunning.value) return@collect

            val next: ProxyState = if (stats.active > 0) {
                ProxyState.Running(stats.active, stats.total)
            } else {
                ProxyState.Connecting(stats.active, stats.total)
            }

            if (current is ProxyState.Starting && stats.active == 0) return@collect
            _proxyState.value = next
        }
    }

    private fun syncInitialState() {
        if (ProxyServiceState.isRunning.value) {
            val s = ProxyServiceState.connectionStats.value
            _proxyState.value = if (s.active > 0) {
                ProxyState.Running(s.active, s.total)
            } else {
                ProxyState.Connecting(s.active, s.total)
            }
        }
    }

    suspend fun startProxy(cfg: ClientConfig) {
        if (ProxyServiceState.isRunning.value) return
        if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle

        if (!cfg.isRawMode && (cfg.serverAddress.isBlank() || cfg.vkLink.isBlank())) {
            setErrorWithAutoReset("Не заполнены настройки клиента")
            return
        }
        if (cfg.isRawMode && cfg.rawCommand.isBlank()) {
            setErrorWithAutoReset("Не задана raw-команда")
            return
        }

        _proxyState.value = ProxyState.Starting

        ProxyServiceState.clearLogs()
        ProxyServiceState.setStartupResult(null)
        ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
        ProxyServiceState.clearConnectedSince()

        try {
            launcher.start()
        } catch (e: Exception) {
            setErrorWithAutoReset(e.message ?: "Не удалось запустить сервис")
            return
        }

        val result = withTimeoutOrNull(5 * 60_000L) {
            var everRunning = false
            combine(
                ProxyServiceState.startupResult,
                ProxyServiceState.isRunning
            ) { sr, running -> sr to running }
                .first { (sr, running) ->
                    if (running) everRunning = true
                    sr != null || (everRunning && !running)
                }
                .first
        }

        if (_proxyState.value is ProxyState.Error) return

        when (result) {
            null -> {
                stopProxy()
                setErrorWithAutoReset("Прокси не запустился")
            }
            is StartupResult.Failed -> {
                stopProxy()
                setErrorWithAutoReset(result.message)
            }
            is StartupResult.Success -> {
                val s = ProxyServiceState.connectionStats.value
                _proxyState.value = if (s.active > 0) {
                    ProxyState.Running(s.active, s.total)
                } else {
                    ProxyState.Connecting(s.active, s.total)
                }
            }
        }
    }

    /** Ждёт фактического доламывания сервиса: `stopService` асинхронен, см. `teardownComplete`. */
    suspend fun stopProxy() {
        launcher.stop()
        _proxyState.value = ProxyState.Idle
        withTimeoutOrNull(10_000) {
            ProxyServiceState.isRunning.first { !it }
            ProxyServiceState.teardownComplete.first { it }
        }
    }

    fun dismissCaptcha() {
        ProxyServiceState.setCaptchaSession(null)
        if (_proxyState.value is ProxyState.CaptchaRequired) {
            val s = ProxyServiceState.connectionStats.value
            _proxyState.value = if (s.active > 0) {
                ProxyState.Running(s.active, s.total)
            } else {
                ProxyState.Connecting(s.active, s.total)
            }
        }
    }

    fun setErrorWithAutoReset(message: String) {
        resetJob?.cancel()
        _proxyState.value = ProxyState.Error(message)
        resetJob = scope.launch {
            delay(4_000)
            if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle
        }
    }

    fun clearState() {
        _proxyState.value = ProxyState.Idle
    }
}

