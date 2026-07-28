package com.freeturn.app.data.backup

import com.freeturn.app.data.config.ClientId
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerJson
import org.json.JSONArray
import org.json.JSONObject

/** Содержимое бэкапа: профили + активный + личность устройства + интерфейсные тоггл-настройки. */
data class BackupData(
    val servers: List<Server>,
    val activeId: String?,
    // Постоянный client-id устройства (allowlist на сервере). Без него restore на новом
    // девайсе даёт свежий cid -> сервер отклоняет подключение, поэтому поле обязательное.
    val ownClientId: String,
    val dynamicTheme: Boolean,
    val nerdMode: Boolean,
    val privacyMode: Boolean,
    val restartServerOnSwitch: Boolean,
    val suppressUpdatePrompt: Boolean,
    val suppressTgPrompt: Boolean,
    val hotspotProxyEnabled: Boolean
)

/** Сериализация [BackupData] в JSON (серверы - через тот же [ServerJson], что и в DataStore). */
object SettingsBackup {
    private const val FORMAT_VERSION = 3

    fun encode(data: BackupData): String = JSONObject().apply {
        put("v", FORMAT_VERSION)
        put("servers", JSONArray(ServerJson.encodeList(data.servers)))
        data.activeId?.let { put("activeId", it) }
        put("ownClientId", data.ownClientId)
        put("dynamicTheme", data.dynamicTheme)
        put("nerdMode", data.nerdMode)
        put("privacyMode", data.privacyMode)
        put("restartServerOnSwitch", data.restartServerOnSwitch)
        put("suppressUpdatePrompt", data.suppressUpdatePrompt)
        put("suppressTgPrompt", data.suppressTgPrompt)
        put("hotspotProxyEnabled", data.hotspotProxyEnabled)
    }.toString()

    fun decode(json: String): BackupData {
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            throw BackupCrypto.FormatException("bad payload")
        }
        val serversJson = o.optJSONArray("servers")?.toString() ?: "[]"
        // Битый/отсутствующий cid валим здесь, до затирания профиля: применить такой бэкап -
        // значит подменить личность на чужую и получить обрыв на allowlist.
        val cid = o.optString("ownClientId")
        if (!ClientId.isValid(cid)) throw BackupCrypto.FormatException("bad client id")
        return BackupData(
            servers = ServerJson.decodeList(serversJson),
            activeId = o.optString("activeId").takeIf { it.isNotBlank() },
            ownClientId = cid,
            dynamicTheme = o.optBoolean("dynamicTheme", true),
            nerdMode = o.optBoolean("nerdMode", true),
            privacyMode = o.optBoolean("privacyMode", false),
            restartServerOnSwitch = o.optBoolean("restartServerOnSwitch", false),
            suppressUpdatePrompt = o.optBoolean("suppressUpdatePrompt", false),
            suppressTgPrompt = o.optBoolean("suppressTgPrompt", false),
            hotspotProxyEnabled = o.optBoolean("hotspotProxyEnabled", false)
        )
    }
}
