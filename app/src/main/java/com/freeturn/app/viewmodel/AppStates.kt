package com.freeturn.app.viewmodel

// SSH connection states
sealed class SshConnectionState {
    object Disconnected : SshConnectionState()
    object Connecting : SshConnectionState()
    data class Connected(val ip: String) : SshConnectionState()
    data class Error(val message: String) : SshConnectionState()
}

// Remote server states
sealed class ServerState {
    object Unknown : ServerState()
    object Checking : ServerState()
    data class Known(val installed: Boolean, val running: Boolean, val vlessMode: Boolean? = null) : ServerState()
    data class Working(val action: String) : ServerState()
    data class Error(val message: String) : ServerState()
}

// Local proxy client states
sealed class ProxyState {
    object Idle : ProxyState()
    // Процесс поднимается, ещё не было ни одной строки, которая подтвердила бы
    // работу соединения — жёлтый.
    object Starting : ProxyState()
    // Процесс работает, но ни один поток/сессия не подключены (active == 0) — жёлтый.
    // total == 0 означает, что мы пока не знаем целевое число потоков.
    data class Connecting(val active: Int, val total: Int) : ProxyState()
    // Хотя бы один поток/сессия подключены — зелёный. active <= total.
    data class Running(val active: Int, val total: Int) : ProxyState()
    data class Error(val message: String) : ProxyState()
    data class CaptchaRequired(val url: String, val sessionId: Long = 0L) : ProxyState()
}

// App update states
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val version: String, val changelog: String) : UpdateState()
    object NoUpdate : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    object ReadyToInstall : UpdateState()
    data class Error(val message: String) : UpdateState()
}
