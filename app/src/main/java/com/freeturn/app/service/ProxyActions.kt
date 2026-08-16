package com.freeturn.app.service

/**
 * Команды [ProxyService]. Ими же приходят внешние входы (тайл, виджет, ярлык,
 * кнопка в шторке) через [ProxyReceiver].
 */
object ProxyActions {
    const val START = "com.freeturn.app.START_PROXY"
    const val STOP = "com.freeturn.app.STOP_PROXY"
}
