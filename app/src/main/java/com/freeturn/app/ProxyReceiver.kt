package com.freeturn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ProxyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.freeturn.app.START_PROXY" -> {
                ProxyServiceState.clearLogs()
                ProxyServiceState.setStartupResult(null)
                val serviceIntent = Intent(context, ProxyService::class.java)
                context.startForegroundService(serviceIntent)
            }
            "com.freeturn.app.STOP_PROXY" -> {
                val serviceIntent = Intent(context, ProxyService::class.java)
                context.stopService(serviceIntent)
            }
        }
    }
}
