package com.freeturn.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import com.freeturn.app.domain.proxy.ProxyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Единственная точка запуска/остановки [ProxyService] - для UI и внешних входов.
 * Здесь же персистится намерение пользователя: команда учтена, даже если сервис её
 * не получил (фон без права поднимать FGS), и переживает смерть процесса.
 */
class AndroidProxyServiceLauncher(
    private val context: Context,
    private val prefs: AppPreferences
) : ProxyServiceLauncher {

    // Параллелизм 1: START и STOP обязаны уйти в систему в том же порядке, в каком их нажали.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatchScope =
        CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    /**
     * С главного потока команда уходит в очередь: байндер-транзакция запуска сервиса
     * занимает его ровно в момент нажатия, и в кадр не влезает анимация кнопки.
     * Из бродкаста и тайла зовём на месте - `onReceive` вернётся раньше, чем очередь
     * дойдёт до вызова, и заявку потеряли бы вместе с процессом.
     */
    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) dispatchScope.launch { block() } else block()
    }

    override fun start() {
        prefs.setProxyDesired(true)
        ProxyStore.starting()
        dispatch {
            try {
                val intent = command(ProxyActions.START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                ProxyStore.fail(e.message ?: "Не удалось запустить сервис")
            }
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
        dispatch {
            try {
                context.startService(command(ProxyActions.STOP))
            } catch (_: Exception) {
                // Фон без права поднимать сервис - значит и поднимать уже нечего.
                context.stopService(Intent(context, ProxyService::class.java))
            }
        }
    }

    private fun command(action: String) =
        Intent(context, ProxyService::class.java).setAction(action)
}
