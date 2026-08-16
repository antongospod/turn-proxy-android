package com.freeturn.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.freeturn.app.data.backup.BackupData
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.ClientId
import com.freeturn.app.data.config.SshConfig
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerJson
import com.freeturn.app.data.server.ServerOpts
import com.freeturn.app.data.server.ServersSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

// Битый файл переживаем с дефолтами: чтение/запись на пути старта туннеля
// (ownClientId, оркестратор) не должны ронять процесс.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class AppPreferences(context: Context) {
    private val context = context.applicationContext

    companion object {
        val DYNAMIC_THEME = booleanPreferencesKey("dynamic_theme")
        val NERD_MODE = booleanPreferencesKey("nerd_mode")
        val PRIVACY_MODE = booleanPreferencesKey("privacy_mode")
        val TG_SUBSCRIBE_SHOWN = booleanPreferencesKey("tg_subscribe_shown")
        val SUPPRESS_UPDATE_PROMPT = booleanPreferencesKey("suppress_update_prompt")
        val SUPPRESS_TG_PROMPT = booleanPreferencesKey("suppress_tg_prompt")
        val BATTERY_PROMPT_SHOWN = booleanPreferencesKey("battery_prompt_shown")
        val RESTART_SERVER_ON_SWITCH = booleanPreferencesKey("restart_server_on_switch")
        val HOTSPOT_PROXY = booleanPreferencesKey("hotspot_proxy")
        val SERVERS_JSON = stringPreferencesKey("servers_json")
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")
        val OWN_CLIENT_ID = stringPreferencesKey("own_client_id")
        val PROXY_DESIRED = booleanPreferencesKey("proxy_desired")
    }

    // Намерение пользователя переживает смерть процесса, поэтому пишется своим scope:
    // STOP гасит сервис вместе с его корутинами раньше, чем dataStore успевает записать.
    // Параллелизм 1: пул IO раскидал бы быструю пару START->STOP по разным потокам, и
    // на диске мог остаться true - прокси поднимался бы сам после выключения.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val desiredScope =
        CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    private fun <T> prefFlow(transform: (Preferences) -> T): Flow<T> =
        context.dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map(transform)

    val serversSnapshot: Flow<ServersSnapshot> = prefFlow { prefs ->
        ServersSnapshot(
            list = ServerJson.decodeList(prefs[SERVERS_JSON]),
            activeId = prefs[ACTIVE_SERVER_ID]?.takeIf { it.isNotBlank() },
            loaded = true
        )
    }

    // Производные от serversSnapshot: их читает рантайм (ProxyService, оркестратор,
    // SSH). Без активного сервера отдают дефолты - запускать в этом случае нечего.

    private val activeServerFlow: Flow<Server?> =
        serversSnapshot.map { it.active }.distinctUntilChanged()

    val sshConfigFlow: Flow<SshConfig> =
        activeServerFlow.map { it?.ssh ?: SshConfig() }.distinctUntilChanged()

    val clientConfigFlow: Flow<ClientConfig> =
        activeServerFlow.map { it?.client ?: ClientConfig() }.distinctUntilChanged()

    val proxyListenFlow: Flow<String> =
        activeServerFlow.map { it?.proxyListen ?: "0.0.0.0:56000" }.distinctUntilChanged()

    val proxyConnectFlow: Flow<String> =
        activeServerFlow.map { it?.proxyConnect ?: "127.0.0.1:40537" }.distinctUntilChanged()

    val serverOptsFlow: Flow<ServerOpts> =
        activeServerFlow.map { it?.opts ?: ServerOpts() }.distinctUntilChanged()

    val dynamicThemeFlow: Flow<Boolean> = prefFlow { prefs -> prefs[DYNAMIC_THEME] ?: true }

    val nerdModeFlow: Flow<Boolean> = prefFlow { prefs -> prefs[NERD_MODE] ?: true }

    val privacyModeFlow: Flow<Boolean> = prefFlow { prefs -> prefs[PRIVACY_MODE] ?: false }

    val restartServerOnSwitchFlow: Flow<Boolean> =
        prefFlow { prefs -> prefs[RESTART_SERVER_ON_SWITCH] ?: false }

    // Раздача туннеля по SOCKS5 наружу. Работает только в WG-режиме: без tun сокеты
    // сервера уходят напрямую, и клиенты хотспота получили бы канал мимо туннеля.
    val hotspotProxyEnabledFlow: Flow<Boolean> = prefFlow { prefs -> prefs[HOTSPOT_PROXY] ?: false }

    val tgSubscribeShownFlow: Flow<Boolean> = prefFlow { prefs -> prefs[TG_SUBSCRIBE_SHOWN] ?: false }

    val suppressUpdatePromptFlow: Flow<Boolean> = prefFlow { prefs -> prefs[SUPPRESS_UPDATE_PROMPT] ?: false }

    val suppressTgPromptFlow: Flow<Boolean> = prefFlow { prefs -> prefs[SUPPRESS_TG_PROMPT] ?: false }

    // Один раз за установку: на MIUI/HyperOS isIgnoringBatteryOptimizations остаётся false
    // даже после "не ограничивать" - без флага диалог всплывал бы каждый запуск.
    val batteryPromptShownFlow: Flow<Boolean> = prefFlow { prefs -> prefs[BATTERY_PROMPT_SHOWN] ?: false }

    // "Прокси должен работать" по последней команде пользователя. Живое состояние сессии -
    // в ProxyStore; здесь только намерение, по которому её восстанавливают после убийства.
    val proxyDesiredFlow: Flow<Boolean> = prefFlow { prefs -> prefs[PROXY_DESIRED] ?: false }

    /** Не suspend: зовётся с путей, где вызывающий вот-вот умрёт (см. [desiredScope]). */
    fun setProxyDesired(desired: Boolean) {
        desiredScope.launch {
            context.dataStore.edit { prefs -> prefs[PROXY_DESIRED] = desired }
        }
    }

    // Каждая операция - одна транзакция dataStore.edit: атомарный read-modify-write,
    // параллельные записи не теряются и не оставляют активный id без сервера.

    suspend fun updateServer(id: String, transform: (Server) -> Server): Boolean {
        var changed = false
        context.dataStore.edit { prefs ->
            val list = ServerJson.decodeList(prefs[SERVERS_JSON])
            val updated = list.map { if (it.id == id) transform(it) else it }
            changed = updated != list
            if (changed) prefs[SERVERS_JSON] = ServerJson.encodeList(updated)
        }
        return changed
    }

    suspend fun updateActiveServer(transform: (Server) -> Server): Boolean {
        var changed = false
        context.dataStore.edit { prefs ->
            val activeId = prefs[ACTIVE_SERVER_ID]?.takeIf { it.isNotBlank() } ?: return@edit
            val list = ServerJson.decodeList(prefs[SERVERS_JSON])
            val updated = list.map { if (it.id == activeId) transform(it) else it }
            changed = updated != list
            if (changed) prefs[SERVERS_JSON] = ServerJson.encodeList(updated)
        }
        return changed
    }

    suspend fun addServer(server: Server, activate: Boolean = false): String {
        context.dataStore.edit { prefs ->
            val list = ServerJson.decodeList(prefs[SERVERS_JSON])
            val base = server.name.trim().ifBlank { Server.FALLBACK_NAME }
            val named = server.copy(name = uniqueServerName(base, list))
            prefs[SERVERS_JSON] = ServerJson.encodeList(list + named)
            if (activate || list.isEmpty()) prefs[ACTIVE_SERVER_ID] = named.id
        }
        return server.id
    }

    suspend fun cloneServer(id: String): String? {
        var newId: String? = null
        context.dataStore.edit { prefs ->
            val list = ServerJson.decodeList(prefs[SERVERS_JSON])
            val source = list.firstOrNull { it.id == id } ?: return@edit
            val copy = source.copy(
                id = UUID.randomUUID().toString(),
                name = uniqueServerName(source.name, list)
            )
            prefs[SERVERS_JSON] = ServerJson.encodeList(list + copy)
            newId = copy.id
        }
        return newId
    }

    suspend fun renameServer(id: String, name: String) {
        context.dataStore.edit { prefs ->
            val list = ServerJson.decodeList(prefs[SERVERS_JSON])
            val target = list.firstOrNull { it.id == id } ?: return@edit
            val unique = uniqueServerName(name.trim().ifBlank { target.name }, list, excludingId = id)
            if (unique == target.name) return@edit
            prefs[SERVERS_JSON] =
                ServerJson.encodeList(list.map { if (it.id == id) it.copy(name = unique) else it })
        }
    }

    suspend fun deleteServer(id: String) {
        context.dataStore.edit { prefs ->
            val list = ServerJson.decodeList(prefs[SERVERS_JSON])
            val remaining = list.filterNot { it.id == id }
            if (remaining.size == list.size) return@edit
            prefs[SERVERS_JSON] = ServerJson.encodeList(remaining)
            if (prefs[ACTIVE_SERVER_ID] == id) {
                val next = remaining.firstOrNull()
                if (next == null) prefs.remove(ACTIVE_SERVER_ID)
                else prefs[ACTIVE_SERVER_ID] = next.id
            }
        }
    }

    suspend fun setActiveServerId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_SERVER_ID)
            else prefs[ACTIVE_SERVER_ID] = id
        }
    }

    private fun uniqueServerName(base: String, existing: List<Server>, excludingId: String? = null): String {
        val taken = existing
            .filter { it.id != excludingId }
            .map { it.name.trim().lowercase() }
            .toSet()
        if (base.lowercase() !in taken) return base
        var i = 2
        while ("$base ($i)".lowercase() in taken) i++
        return "$base ($i)"
    }

    suspend fun saveSshConfig(config: SshConfig) {
        updateActiveServer { it.copy(ssh = config) }
    }

    suspend fun saveSshFingerprint(fingerprint: String) {
        updateActiveServer { it.copy(ssh = it.ssh.copy(hostFingerprint = fingerprint)) }
    }

    suspend fun saveSshRootMode(mode: String) {
        updateActiveServer { it.copy(ssh = it.ssh.copy(rootMode = mode)) }
    }

    suspend fun setDynamicTheme(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DYNAMIC_THEME] = enabled }
    }

    suspend fun setNerdMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[NERD_MODE] = enabled }
    }

    suspend fun setPrivacyMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PRIVACY_MODE] = enabled }
    }

    suspend fun setRestartServerOnSwitch(enabled: Boolean) {
        context.dataStore.edit { it[RESTART_SERVER_ON_SWITCH] = enabled }
    }

    suspend fun setHotspotProxyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[HOTSPOT_PROXY] = enabled }
    }

    suspend fun setTgSubscribeShown() {
        context.dataStore.edit { prefs -> prefs[TG_SUBSCRIBE_SHOWN] = true }
    }

    suspend fun setSuppressUpdatePrompt(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SUPPRESS_UPDATE_PROMPT] = enabled }
    }

    suspend fun setSuppressTgPrompt(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SUPPRESS_TG_PROMPT] = enabled }
    }

    suspend fun setBatteryPromptShown() {
        context.dataStore.edit { prefs -> prefs[BATTERY_PROMPT_SHOWN] = true }
    }

    suspend fun ownClientId(): String {
        val cur = prefFlow { it[OWN_CLIENT_ID] }.first()
        if (cur != null && ClientId.isValid(cur)) return cur
        var id = ""
        context.dataStore.edit { prefs ->
            val existing = prefs[OWN_CLIENT_ID]
            id = if (existing != null && ClientId.isValid(existing)) existing
            else ClientId.generate().also { prefs[OWN_CLIENT_ID] = it }
        }
        return id
    }

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }

    suspend fun exportData(): BackupData {
        val snap = serversSnapshot.first()
        return BackupData(
            servers = snap.list,
            activeId = snap.activeId,
            ownClientId = ownClientId(),
            dynamicTheme = dynamicThemeFlow.first(),
            nerdMode = nerdModeFlow.first(),
            privacyMode = privacyModeFlow.first(),
            restartServerOnSwitch = restartServerOnSwitchFlow.first(),
            hotspotProxy = hotspotProxyEnabledFlow.first(),
            suppressUpdatePrompt = suppressUpdatePromptFlow.first(),
            suppressTgPrompt = suppressTgPromptFlow.first()
        )
    }

    /**
     * Восстанавливает профиль целиком: чистит DataStore и пишет содержимое бэкапа одной
     * транзакцией. Частичное слияние недопустимо - личность (own client-id) поверх непустого
     * профиля не применялась бы, и ядро уходило бы на сервер с чужим cid (allowlist рвёт
     * сессию сразу после DTLS-хендшейка). Возвращает число восстановленных серверов.
     *
     * Одноразовые флаги подсказок (батарея, TG) не в бэкапе - они про установку, не про
     * профиль, и сбрасываются вместе со всем остальным.
     */
    suspend fun restoreBackup(data: BackupData): Int {
        context.dataStore.edit { prefs ->
            prefs.clear()
            prefs[SERVERS_JSON] = ServerJson.encodeList(data.servers)
            val active = data.activeId?.takeIf { id -> data.servers.any { it.id == id } }
                ?: data.servers.firstOrNull()?.id
            active?.let { prefs[ACTIVE_SERVER_ID] = it }
            prefs[OWN_CLIENT_ID] = data.ownClientId
            prefs[DYNAMIC_THEME] = data.dynamicTheme
            prefs[NERD_MODE] = data.nerdMode
            prefs[PRIVACY_MODE] = data.privacyMode
            prefs[RESTART_SERVER_ON_SWITCH] = data.restartServerOnSwitch
            prefs[HOTSPOT_PROXY] = data.hotspotProxy
            prefs[SUPPRESS_UPDATE_PROMPT] = data.suppressUpdatePrompt
            prefs[SUPPRESS_TG_PROMPT] = data.suppressTgPrompt
        }
        return data.servers.size
    }
}
