package com.freeturn.app.domain.proxy

/**
 * Запуск/остановка платформенного прокси-сервиса. Инверсия зависимости: domain не
 * знает про Android-Service, конкретный Intent держит реализация в слое service.
 */
interface ProxyServiceLauncher {
    fun start()
    fun stop()
}
