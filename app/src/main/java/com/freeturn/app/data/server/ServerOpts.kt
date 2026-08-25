package com.freeturn.app.data.server

import com.freeturn.app.data.config.KcpProfile
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.config.ProxyMode

/** Снимок серверных опций. obfKey хранится в шифрованном хранилище AppPreferences. */
data class ServerOpts(
    /** Wire-профиль обфускации: none | rtpopus | rtpopus2 | rtpopus3 (-obf-profile). */
    val obfProfile: String = ObfProfile.NONE,
    /** 64-hex obf-ключ (-obf-key). Должен совпадать на клиенте и сервере. */
    val obfKey: String = "",
    /** Режим проброса (-mode). Сервер отвергает сессию с другим режимом. */
    val proxyMode: String = ProxyMode.UDP,
    /** ARQ tcp-режима (-kcp-*); в udp не используется. */
    val kcp: KcpProfile = KcpProfile.DEFAULT
) {
    /** Обфускация включена, когда выбран реальный профиль. */
    val obfEnabled: Boolean get() = obfProfile != ObfProfile.NONE

    val tcpMode: Boolean get() = proxyMode == ProxyMode.TCP
}
