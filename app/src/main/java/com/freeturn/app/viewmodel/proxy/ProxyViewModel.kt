package com.freeturn.app.viewmodel.proxy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.proxy.LocalProxyManager
import com.freeturn.app.domain.ProxyState
import com.freeturn.app.domain.proxy.ProxyServiceState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProxyViewModel(
    private val proxyManager: LocalProxyManager,
    private val prefs: AppPreferences
) : ViewModel() {

    val proxyState: StateFlow<ProxyState> = proxyManager.proxyState
    val connectedSince: StateFlow<Long?> = ProxyServiceState.connectedSince
    val logs: StateFlow<List<String>> = ProxyServiceState.logs

    fun startProxy() {
        viewModelScope.launch {
            val config = prefs.clientConfigFlow.first()
            proxyManager.startProxy(config)
        }
    }

    fun stopProxy() {
        viewModelScope.launch { proxyManager.stopProxy() }
    }

    fun dismissCaptcha() {
        proxyManager.dismissCaptcha()
    }

    fun clearLogs() {
        ProxyServiceState.clearLogs()
    }
}
