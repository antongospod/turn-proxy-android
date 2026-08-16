package com.freeturn.app.domain

sealed class SshConnectionState {
    object Disconnected : SshConnectionState()
    object Connecting : SshConnectionState()
    data class Connected(val ip: String) : SshConnectionState()
    data class Error(val message: String) : SshConnectionState()
}

sealed class ServerState {
    object Unknown : ServerState()
    object Checking : ServerState()
    data class Known(
        val installed: Boolean,
        val running: Boolean,
        val tcpMode: Boolean? = null,
        val obfProfile: String? = null,
        val version: String? = null
    ) : ServerState()
    data class Working(val action: String) : ServerState()
    data class Error(val message: String) : ServerState()
}

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val version: String) : UpdateState()
    object NoUpdate : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    object ReadyToInstall : UpdateState()
    data class Error(val message: String) : UpdateState()
}
