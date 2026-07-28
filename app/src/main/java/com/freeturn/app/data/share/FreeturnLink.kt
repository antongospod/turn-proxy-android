package com.freeturn.app.data.share

import org.json.JSONObject
import java.util.Base64

/**
 * Share-ссылка `freeturn://base64url(JSON)`.
 * JSON собирается вручную для сохранения порядка ключей (как в Go json.Marshal).
 */
data class FreeturnLink(
    val provider: String,
    val peer: String,
    val transport: String = "",
    val mode: String = "",
    val bond: Boolean = false,
    val obfProfile: String = "",
    val obfKey: String = "",
    val n: Int = 0,
    val streamsPerCred: Int = 0,
    val clientId: String = "",
    val listen: String = "",
    val dnsMode: String = "",
    val dnsServers: String = "",
    val manualCaptcha: Boolean = false,
    val name: String = "",
    val wgConf: String = ""
) {
    fun encode(): String {
        val sb = StringBuilder("{")
        sb.field("v", VERSION.toString())
        sb.field("provider", jsonString(provider))
        sb.field("peer", jsonString(peer))
        if (transport.isNotEmpty()) sb.field("transport", jsonString(transport))
        if (mode.isNotEmpty()) sb.field("mode", jsonString(mode))
        if (bond) sb.field("bond", "true")
        if (obfProfile.isNotEmpty() && obfProfile != "none") {
            sb.field("obf", jsonString(obfProfile))
            sb.field("key", jsonString(obfKey))
        }
        if (n != 0) sb.field("n", n.toString())
        if (streamsPerCred != 0) sb.field("spc", streamsPerCred.toString())
        if (clientId.isNotEmpty()) sb.field("cid", jsonString(clientId))
        if (listen.isNotEmpty()) sb.field("listen", jsonString(listen))
        if (dnsMode.isNotEmpty()) sb.field("dns", jsonString(dnsMode))
        if (dnsServers.isNotEmpty()) sb.field("dnss", jsonString(dnsServers))
        if (manualCaptcha) sb.field("mcap", "true")
        if (name.isNotEmpty()) sb.field("name", jsonString(name))
        if (wgConf.isNotEmpty()) sb.field("wg", jsonString(wgConf))
        sb.append('}')
        return SCHEME + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(sb.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val SCHEME = "freeturn://"
        const val VERSION = 1

        fun looksLikeLink(raw: String): Boolean =
            raw.trim().startsWith(SCHEME, ignoreCase = true)

        fun parse(raw: String): Result<FreeturnLink> = runCatching {
            val trimmed = raw.trim()
            require(trimmed.startsWith(SCHEME, ignoreCase = true)) { "invalid scheme" }
            val payload = trimmed.substring(SCHEME.length)
            require(payload.isNotEmpty()) { "empty payload" }
            val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            val o = JSONObject(json)
            require(o.optInt("v", -1) == VERSION) { "unsupported link version" }
            val provider = o.optString("provider")
            require(provider.isNotEmpty()) { "missing provider" }
            val peer = o.optString("peer")
            require(peer.isNotEmpty()) { "missing peer" }
            FreeturnLink(
                provider = provider,
                peer = peer,
                transport = o.optString("transport"),
                mode = o.optString("mode"),
                bond = o.optBoolean("bond", false),
                obfProfile = o.optString("obf"),
                obfKey = o.optString("key"),
                n = o.optInt("n", 0),
                streamsPerCred = o.optInt("spc", 0),
                clientId = o.optString("cid"),
                listen = o.optString("listen"),
                dnsMode = o.optString("dns"),
                dnsServers = o.optString("dnss"),
                manualCaptcha = o.optBoolean("mcap", false),
                name = o.optString("name"),
                wgConf = o.optString("wg")
            )
        }

        private fun StringBuilder.field(key: String, rawValue: String) {
            if (length > 1) append(',')
            append('"').append(key).append("\":").append(rawValue)
        }

        /** Минимальное JSON-экранирование (кавычки, бэкслеш, control-символы). */
        private fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
            return sb.append('"').toString()
        }
    }
}
