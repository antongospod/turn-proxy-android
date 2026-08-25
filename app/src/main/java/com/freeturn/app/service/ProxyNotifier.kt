package com.freeturn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.freeturn.app.R
import com.freeturn.app.domain.proxy.ProxyPhase
import com.freeturn.app.domain.proxy.ProxyStatus
import java.util.Locale

/**
 * Нотификации сервиса: постоянный статус подключения и отдельный алерт ручной
 * капчи. Рисуется целиком из [ProxyStatus] - своего состояния не держит.
 */
class ProxyNotifier(private val service: Service) {

    companion object {
        const val NOTIF_ID_FG = 1
        private const val NOTIF_ID_CAPTCHA = 2
        private const val CHANNEL_PROXY = "ProxyChannel"
        private const val CHANNEL_CAPTCHA = "CaptchaChannel"

        /**
         * Каналы заводятся один раз за процесс, из [android.app.Application]. В onCreate
         * сервиса эти две транзакции к NotificationManager ложились на главный поток
         * ровно в момент нажатия - вместе с ними в кадр не влезала анимация кнопки.
         */
        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROXY,
                    context.getString(R.string.notif_channel_proxy),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CAPTCHA,
                    context.getString(R.string.notif_channel_captcha),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private var shown: ProxyStatus? = null
    private var captchaShown = false

    private val openApp: PendingIntent? by lazy {
        service.packageManager.getLaunchIntentForPackage(service.packageName)?.let {
            PendingIntent.getActivity(service, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private val stopAction: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            service,
            0,
            Intent(service, ProxyReceiver::class.java).setAction(ProxyActions.STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Первая нотификация для `startForeground` - до неё сессии ещё нет. */
    fun build(): Notification = build(ProxyStatus(phase = ProxyPhase.Connecting), tunnelMode = false)

    fun update(status: ProxyStatus, tunnelMode: Boolean) {
        if (status.captchaUrl.isNotEmpty()) showCaptcha() else cancelCaptcha()
        // Метрики тикают каждые 2 с: перерисовываем, только если что-то видимое изменилось.
        if (status.visible() == shown?.visible()) return
        shown = status
        notify(NOTIF_ID_FG, build(status, tunnelMode))
    }

    private fun build(status: ProxyStatus, tunnelMode: Boolean): Notification {
        val connected = status.phase == ProxyPhase.Connected
        val title = when {
            connected && tunnelMode -> service.getString(R.string.tunnel_active)
            connected -> service.getString(R.string.proxy_active)
            status.phase == ProxyPhase.Error -> service.getString(R.string.notif_proxy_connect_error)
            else -> service.getString(R.string.notif_proxy_connecting)
        }
        // Сразу после коннекта метрик ещё нет (поллер тикает раз в 2 с) - строка была бы пустой.
        val details = listOfNotNull(streamsText(status), speedText(status))
            .takeIf { connected && it.isNotEmpty() }
            ?.joinToString(" • ")
            ?: service.getString(R.string.notif_proxy_title)
        return NotificationCompat.Builder(service, CHANNEL_PROXY)
            .setContentTitle(title)
            .setContentText(details)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, service.getString(R.string.notif_proxy_stop_action), stopAction)
            .build()
    }

    private fun streamsText(status: ProxyStatus): String? =
        if (status.total > 0) String.format(
            Locale.US,
            service.getString(R.string.notif_proxy_threads_format),
            status.active,
            status.total
        ) else null

    private fun speedText(status: ProxyStatus): String? =
        if (status.rxRate == 0L && status.txRate == 0L) null
        else "↓ ${rate(status.rxRate)} ↑ ${rate(status.txRate)}"

    private fun rate(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B/s"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB/s"
        else -> String.format(Locale.US, "%.1f MB/s", bytes / (1024f * 1024f))
    }

    /** Дедуп: пока предыдущий алерт не закрыт - повторно не шумим. */
    private fun showCaptcha() {
        if (captchaShown) return
        captchaShown = true
        notify(
            NOTIF_ID_CAPTCHA,
            NotificationCompat.Builder(service, CHANNEL_CAPTCHA)
                .setContentTitle(service.getString(R.string.notif_captcha_title))
                .setContentText(service.getString(R.string.notif_captcha_text))
                .setSmallIcon(R.drawable.ic_notification_captcha)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build()
        )
    }

    fun cancelCaptcha() {
        if (!captchaShown) return
        captchaShown = false
        NotificationManagerCompat.from(service).cancel(NOTIF_ID_CAPTCHA)
    }

    // POST_NOTIFICATIONS могли отозвать: диалог капчи UI покажет и без алерта.
    private fun notify(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(service).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }
}

/** Что видно в нотификации: скорость округляем, чтобы не дёргать её каждый тик. */
private fun ProxyStatus.visible() =
    listOf(phase, active, total, rxRate / 1024, txRate / 1024)
