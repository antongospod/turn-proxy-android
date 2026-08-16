package com.freeturn.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Тайл, виджет, ярлык и кнопка в шторке - через тот же launcher, что и экран. */
class ProxyReceiver : BroadcastReceiver(), KoinComponent {

    private val launcher: ProxyServiceLauncher by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ProxyActions.START -> launcher.start()
            ProxyActions.STOP -> launcher.stop()
        }
    }
}
