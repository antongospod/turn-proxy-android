package com.freeturn.app.domain.proxy

/** Стадия сессии. Idle/Starting ставит приложение, остальное приходит от ядра. */
enum class ProxyPhase { Idle, Starting, Connecting, Connected, Captcha, Error }

/**
 * Всё, что показывает UI про прокси, одним снимком: расходиться нечему.
 *
 * [captchaId] меняется на каждую новую капчу - по нему пересоздаётся WebView,
 * когда ядро повторно выдаёт тот же URL.
 */
data class ProxyStatus(
    val phase: ProxyPhase = ProxyPhase.Idle,
    val active: Int = 0,
    val total: Int = 0,
    val rxRate: Long = 0,
    val txRate: Long = 0,
    val tunnelUp: Boolean = false,
    val connectedSince: Long? = null,
    val error: String = "",
    val captchaUrl: String = "",
    val captchaId: Long = 0,
) {
    /** Прокси запущен или запускается - настройки подключения трогать нельзя. */
    val busy: Boolean get() = phase != ProxyPhase.Idle && phase != ProxyPhase.Error
}
