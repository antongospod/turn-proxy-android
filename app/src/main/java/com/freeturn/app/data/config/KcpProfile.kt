package com.freeturn.app.data.config

/**
 * Профиль ARQ-слоя tcp-режима: секция `kcp` конфига ядра и флаги `-kcp-*`.
 *
 * [DEFAULT] обязан совпадать с дефолтом ядра: в udp-режиме любое отличие ядро считает
 * ошибкой запуска, поэтому в udp секция в конфиг не кладётся вовсе.
 */
data class KcpProfile(
    val noDelay: Int = 1,
    val interval: Int = 20,
    val resend: Int = 2,
    val nc: Int = 1,
    val sndWnd: Int = 512,
    val rcvWnd: Int = 512,
    val mtu: Int = 1200,
    val ackNoDelay: Boolean = true,
) {
    val valid: Boolean
        get() = noDelay in 0..1 && nc in 0..1 && interval > 0 && resend >= 0 &&
            sndWnd > 0 && rcvWnd > 0 && mtu in MTU_MIN..MTU_MAX

    companion object {
        val DEFAULT = KcpProfile()

        /** Щадящий вариант для мобильной сети (docs/flags.md ядра). */
        val MOBILE = KcpProfile(interval = 40, sndWnd = 256, rcvWnd = 256, ackNoDelay = false)

        // Сегмент KCP едет внутри DTLS, obf-обёртки, TURN и IP/UDP - выше режется по пути.
        const val MTU_MIN = 300
        const val MTU_MAX = 1350
    }
}

object KcpPreset {
    const val STANDARD = "standard"
    const val MOBILE = "mobile"
    const val CUSTOM = "custom"
    val VALUES = listOf(STANDARD, MOBILE, CUSTOM)

    fun of(profile: KcpProfile): String = when (profile) {
        KcpProfile.DEFAULT -> STANDARD
        KcpProfile.MOBILE -> MOBILE
        else -> CUSTOM
    }
}
