package com.freeturn.app.data.share

/**
 * Фактическое состояние сервера для построения share-ссылки (из `share-info`).
 * Используется вместо локального [com.freeturn.app.data.server.ServerOpts] для точности.
 */
data class ShareInfo(
    /** Пусто = сервер не запускался из приложения; при живых args хотя бы "none". */
    val obfProfile: String = "",
    val obfKey: String = "",
    /** Есть WG-conf в /etc/wireguard -> шарим VPN-доступ (peer-add). Иначе - прокси. */
    val wgBackend: Boolean = false
) {
    /** run.args найден - серверным значениям можно верить. */
    val hasRunArgs: Boolean get() = obfProfile.isNotEmpty()
}
