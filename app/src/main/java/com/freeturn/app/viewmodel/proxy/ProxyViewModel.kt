package com.freeturn.app.viewmodel.proxy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.proxy.LogEntry
import com.freeturn.app.domain.proxy.ProxyEngine
import com.freeturn.app.domain.proxy.ProxyPhase
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import com.freeturn.app.domain.proxy.ProxyStatus
import com.freeturn.app.domain.proxy.ProxyStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProxyViewModel(
    private val launcher: ProxyServiceLauncher,
    private val prefs: AppPreferences,
    private val engine: ProxyEngine
) : ViewModel() {

    val status: StateFlow<ProxyStatus> = ProxyStore.status
    val logs: StateFlow<List<LogEntry>> = ProxyStore.logs

    fun start() = launcher.start()

    fun stop() = launcher.stop()

    /** Окно видно - только на это время ядро опрашивают на метрики. */
    fun setMetricsVisible(visible: Boolean) = engine.setMetricsEnabled(visible)

    /**
     * Возврат приложения на передний план. Живую сессию не трогаем - открытие окна
     * не событие сети, а пинок ядру рециклил бы рабочие аллокации. Мёртвую поднимаем:
     * намерение живо, значит процесс убивали, и sticky-рестарт до нас не дошёл
     * (типично для OEM-киллеров, которым FGS не указ).
     *
     * [vpnConsent] - согласие на VpnService уже дано. Без него в WG-режиме стартовать
     * нечем, а спрашивать молча, без действия пользователя, нельзя.
     */
    fun onForeground(vpnConsent: Boolean) {
        if (ProxyStore.status.value.phase != ProxyPhase.Idle) return
        viewModelScope.launch {
            if (!prefs.proxyDesiredFlow.first()) return@launch
            if (!vpnConsent && prefs.clientConfigFlow.first().wireGuardActive) return@launch
            launcher.start()
        }
    }

    /** Окно закрыл пользователь; ядро ждёт решения дальше и выдаст новую капчу. */
    fun dismissCaptcha() = ProxyStore.setCaptcha("")

    fun clearLogs() = ProxyStore.clearLogs()
}
