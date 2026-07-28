package com.freeturn.app.data.share

import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.server.Server

/**
 * Собирает freeturn://-ссылку для пользователя.
 * vkLink в ссылку не входит, получатель вводит свой.
 */
object ShareLinkBuilder {

    fun build(
        server: Server,
        info: ShareInfo,
        userName: String,
        wgConf: String?,
        clientId: String = ""
    ): String {
        val tcpMode = if (info.hasRunArgs) info.mode == "tcp" else server.client.tcpForward
        val obfProfile = if (info.hasRunArgs) info.obfProfile else server.opts.obfProfile
        val obfKey = if (info.hasRunArgs) info.obfKey else server.opts.obfKey
        return FreeturnLink(
            provider = server.client.provider,
            peer = server.client.serverAddress,
            transport = if (server.client.useUdp) "udp" else "",
            mode = if (tcpMode) "tcp" else "",
            bond = tcpMode && server.client.bond,
            obfProfile = if (ObfProfile.isValidKey(obfKey)) obfProfile else "",
            obfKey = if (ObfProfile.isValidKey(obfKey)) obfKey else "",
            n = server.client.threads,
            streamsPerCred = server.client.streamsPerCred,
            clientId = clientId.trim(),
            name = userName.trim(),
            wgConf = wgConf?.let(::normalizeConf).orEmpty()
        ).encode()
    }

    /**
     * Срезает комментарии, пустые строки и MTU из WG-conf - короче ссылка, плотнее QR.
     * MTU не передаём: получатель подставляет свой (константа туннеля).
     */
    internal fun normalizeConf(conf: String): String =
        conf.lineSequence()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") &&
                    !(it.startsWith("MTU", ignoreCase = true) && it.contains("="))
            }
            .joinToString("\n")
}
