package com.freeturn.app.data.config

import java.security.SecureRandom

object DnsMode {
    const val AUTO = "auto"
    const val PLAIN = "plain"
    const val DOH = "doh"
    val VALUES = listOf(AUTO, PLAIN, DOH)
}

object Provider {
    const val VK = "vk"
    val VALUES = listOf(VK)
}

object ObfProfile {
    const val NONE = "none"
    const val RTPOPUS = "rtpopus"
    const val RTPOPUS2 = "rtpopus2"
    const val RTPOPUS3 = "rtpopus3"
    val VALUES = listOf(NONE, RTPOPUS, RTPOPUS2, RTPOPUS3)

    private val KEY_REGEX = Regex("^[0-9a-fA-F]{64}$")

    fun isValidKey(key: String): Boolean = key.matches(KEY_REGEX)

    fun generateKey(): String =
        ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

// Ядро не принимает IPv6-адреса в скобках.
object HostPort {
    private val REGEX = Regex("""^[\w.\-]+:\d{1,5}$""")

    fun isValid(value: String): Boolean =
        value.matches(REGEX) && value.substringAfterLast(":").toInt() in 1..65535
}

object ClientId {
    private val ID_REGEX = Regex("^[0-9a-f]{32}$")

    fun isValid(id: String): Boolean = id.matches(ID_REGEX)

    fun generate(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

object TunnelTransport {
    const val NONE = "none"
    const val WIREGUARD = "wireguard"
    const val DEFAULT_TUNNEL_NAME = "freeturn-wg"
    val VALUES = listOf(NONE, WIREGUARD)
}

object SplitTunnelMode {
    const val ALL = "all"
    const val INCLUDE = "include"
    const val EXCLUDE = "exclude"
    val VALUES = listOf(ALL, INCLUDE, EXCLUDE)
}
