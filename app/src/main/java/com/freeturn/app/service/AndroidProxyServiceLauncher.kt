package com.freeturn.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import com.freeturn.app.domain.proxy.ProxyStore

/**
 * Единственная точка запуска/остановки [ProxyService] - для UI и внешних входов.
 * Здесь же персистится намерение пользователя: команда учтена, даже если сервис её
 * не получил (фон без права поднимать FGS), и переживает смерть процесса.
 */
class AndroidProxyServiceLauncher(
    private val context: Context,
    private val prefs: AppPreferences
) : ProxyServiceLauncher {

    override fun start() {
        prefs.setProxyDesired(true)
        ProxyStore.starting()
        try {
            val intent = command(ProxyActions.START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: Exception) {
            ProxyStore.fail(e.message ?: "Не удалось запустить сервис")
        }
    }

    /**
     * Команда, а не `stopService`: пока `startForegroundService` ждёт в очереди,
     * останавливать нечего - `stopService` уходит впустую, и сервис поднимается уже
     * после отмены. Команда встаёт в ту же очередь и гасит его гарантированно.
     */
    override fun stop() {
        prefs.setProxyDesired(false)
        ProxyStore.idle()
        try {
            context.startService(command(ProxyActions.STOP))
        } catch (_: Exception) {
            // Фон без права поднимать сервис - значит и поднимать уже нечего.
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }

    private fun command(action: String) =
        Intent(context, ProxyService::class.java).setAction(action)
}
