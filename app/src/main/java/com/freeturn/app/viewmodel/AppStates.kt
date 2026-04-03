package com.freeturn.app.viewmodel

// ── SSH connection states ──────────────────────────────────────────────────
sealed class SshConnectionState {
    object Disconnected : SshConnectionState()
    data class Connecting(val step: String = "Подключение к серверу...") : SshConnectionState()
    data class Connected(val ip: String) : SshConnectionState()
    data class Error(val message: String) : SshConnectionState()
}

// ── Remote server states ───────────────────────────────────────────────────
sealed class ServerState {
    object Unknown : ServerState()
    object Checking : ServerState()
    data class Known(val installed: Boolean, val running: Boolean) : ServerState()
    data class Working(val action: String) : ServerState()
    data class Error(val message: String) : ServerState()
}

// ── Local proxy client states ──────────────────────────────────────────────
sealed class ProxyState {
    object Idle : ProxyState()
    object Starting : ProxyState()
    object Running : ProxyState()
    data class Error(val message: String) : ProxyState()
}
