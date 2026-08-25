package com.freeturn.app.viewmodel.server

sealed interface ServerHubState {
    data object Offline : ServerHubState
    data object NotPaired : ServerHubState
    data object Connecting : ServerHubState
    data class Working(val action: String) : ServerHubState
    data class Online(
        val running: Boolean,
        val installed: Boolean,
        val mode: String?,
        val obfProfile: String?,
        val version: String?,
        val sshIp: String
    ) : ServerHubState
    data object Failed : ServerHubState
    data object SyncOff : ServerHubState
}

/**
 * Доступность "Настроек сервера": при sync ON правки пушатся на сервер -> нужен живой SSH;
 * при sync OFF настройки клиент-локальны -> доступны и оффлайн. Единственное место с этим
 * правилом - его используют и вход в экран (ServerDetailScreen), и сам экран
 * (ServerManagementScreen), иначе условия разъезжаются.
 */
fun serverSettingsAvailable(connected: Boolean, syncOn: Boolean): Boolean = connected || !syncOn
