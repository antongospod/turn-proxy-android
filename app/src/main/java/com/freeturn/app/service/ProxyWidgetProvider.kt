package com.freeturn.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.freeturn.app.MainActivity
import com.freeturn.app.R
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.proxy.ProxyPhase
import com.freeturn.app.domain.proxy.ProxyStatus
import com.freeturn.app.domain.proxy.ProxyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Виджет домашнего экрана: статус прокси + имя активного сервера + кнопка-тумблер.
 * RemoteViews не реактивны - перерисовку двигает [refresh] из App-scope collector'а
 * (смена isRunning или активного сервера), плюс системный onUpdate при добавлении/ребуте.
 */
class ProxyWidgetProvider : AppWidgetProvider(), KoinComponent {

    private val appPreferences: AppPreferences by inject()

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // goAsync: чтение имени сервера из DataStore асинхронно, держим процесс живым.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            try {
                // Таймаут: не эмитни DataStore - finish() не вызвался бы, а у ресивера
                // на всё про всё 10 секунд до ANR. Виджет переживёт имя-заглушку.
                val serverName = withTimeoutOrNull(WIDGET_READ_TIMEOUT_MS) {
                    appPreferences.serversSnapshot.first().active?.name
                }
                manager.updateAppWidget(ids, buildViews(context, ProxyStore.status.value, serverName))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val WIDGET_READ_TIMEOUT_MS = 5_000L

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ProxyWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, ProxyWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }

        private fun buildViews(
            context: Context,
            status: ProxyStatus,
            serverName: String?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_proxy)

            val running = status.busy
            val connecting = running && status.phase != ProxyPhase.Connected

            val statusRes = when {
                !running -> R.string.widget_status_off
                connecting -> R.string.widget_status_connecting
                else -> R.string.widget_status_on
            }
            views.setTextViewText(R.id.widget_status_text, context.getString(statusRes))

            // Потоки в подзаголовке рядом с сервером: "Amsterdam · 3/12" (формат главного экрана).
            val counts = when {
                !running -> null
                status.total > 0 -> "${status.active}/${status.total}"
                else -> status.active.toString()
            }
            val subtitle = listOfNotNull(
                serverName ?: context.getString(R.string.widget_server_none),
                counts
            ).joinToString(" · ")
            views.setTextViewText(R.id.widget_server_name, subtitle)

            // nearby-иконка кодирует статус тоном динамической палитры.
            val statusColor = when {
                !running -> R.color.widget_outline
                connecting -> R.color.widget_tertiary
                else -> R.color.widget_primary
            }
            views.setInt(R.id.widget_status_icon, "setColorFilter", context.getColor(statusColor))

            // Кнопка play/stop - тон primary, состояние читается формой иконки.
            views.setInt(R.id.widget_toggle, "setColorFilter", context.getColor(R.color.widget_primary))
            if (running) {
                views.setImageViewResource(R.id.widget_toggle, R.drawable.stop_24px)
                views.setContentDescription(R.id.widget_toggle, context.getString(R.string.widget_action_stop))
            } else {
                views.setImageViewResource(R.id.widget_toggle, R.drawable.play_arrow_24px)
                views.setContentDescription(R.id.widget_toggle, context.getString(R.string.widget_action_start))
            }

            val toggleAction = if (running) ProxyActions.STOP else ProxyActions.START
            views.setOnClickPendingIntent(
                R.id.widget_toggle,
                PendingIntent.getBroadcast(
                    context,
                    if (running) 1 else 2,
                    Intent(context, ProxyReceiver::class.java).setAction(toggleAction),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            views.setOnClickPendingIntent(
                R.id.widget_body,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            return views
        }
    }
}
