package com.freeturn.app.data.config

import com.freeturn.app.data.server.ServerOpts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Схему JSON декодирует Go со `DisallowUnknownFields`: рассинхрон имён полей
 * ловится только здесь или на первом старте ядра.
 */
class CoreConfigJsonTest {

    private val base = ClientConfig(
        serverAddress = "1.2.3.4:56000",
        vkLink = "https://vk.com/x",
        clientId = "0123456789abcdef0123456789abcdef",
    )

    private fun parse(cfg: ClientConfig, srv: ServerOpts = ServerOpts(), carrierDns: String? = null) =
        Json.parseToJsonElement(cfg.toCoreJson(srv, carrierDns)).jsonObject

    @Test
    fun mapsFlatFields() {
        val o = parse(base)
        assertEquals("1.2.3.4:56000", o["peer"]!!.jsonPrimitive.content)
        assertEquals(Provider.VK, o["provider"]!!.jsonPrimitive.content)
        // Маршрутами рулит VpnService, подписок в приложении нет.
        assertEquals(false, o["routes"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("", o["subUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun ownClientIdFillsBlank() {
        val o = parse(base.copy(clientId = ""), carrierDns = null)
        assertEquals("", o["clientId"]!!.jsonPrimitive.content)

        val own = base.copy(clientId = "").toCoreJson(ServerOpts(), null, "ffffffffffffffffffffffffffffffff")
        assertTrue(own.contains("ffffffffffffffffffffffffffffffff"))
    }

    @Test
    fun tunnelForcesUdpProxyMode() {
        val wg = base.copy(
            tcpForward = true,
            bond = true,
            tunnelTransport = TunnelTransport.WIREGUARD,
            wireGuardConfig = "[Interface]\nAddress = 10.8.0.2/32\n",
        )
        val proxy = parse(wg)["proxy"]!!.jsonObject
        assertEquals("udp", proxy["mode"]!!.jsonPrimitive.content)
        assertEquals(false, proxy["bond"]!!.jsonPrimitive.content.toBoolean())

        val tunnel = parse(wg)["tunnel"]!!.jsonObject
        assertEquals("wg", tunnel["mode"]!!.jsonPrimitive.content)
        assertEquals(ClientConfig.WG_MTU, tunnel["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun tcpModeKeepsBondWithoutTunnel() {
        val proxy = parse(base.copy(tcpForward = true, bond = true))["proxy"]!!.jsonObject
        assertEquals("tcp", proxy["mode"]!!.jsonPrimitive.content)
        assertEquals(true, proxy["bond"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun manualDnsWinsOverCarrier() {
        val o = parse(base.copy(customDns = "1.1.1.1, 8.8.8.8"), carrierDns = "192.168.0.1")
        val servers = o["dns"]!!.jsonObject["servers"].toString()
        assertTrue(servers.contains("1.1.1.1"))
        assertTrue(servers.contains("8.8.8.8"))
        assertTrue(!servers.contains("192.168.0.1"))
    }

    @Test
    fun carrierDnsOnlyWhenSwitchOn() {
        val on = parse(base.copy(useCarrierDns = true), carrierDns = "192.168.0.1")
        assertTrue(on["dns"]!!.jsonObject["servers"].toString().contains("192.168.0.1"))

        val off = parse(base.copy(useCarrierDns = false), carrierDns = "192.168.0.1")
        assertEquals("[]", off["dns"]!!.jsonObject["servers"].toString())
    }

    @Test
    fun obfNeedsValidKey() {
        val srv = ServerOpts(obfProfile = ObfProfile.RTPOPUS, obfKey = "short")
        val obf = parse(base, srv)["obf"]!!.jsonObject
        assertEquals(ObfProfile.NONE, obf["profile"]!!.jsonPrimitive.content)
        assertEquals("", obf["key"]!!.jsonPrimitive.content)

        val key = "a".repeat(64)
        val ok = parse(base, ServerOpts(obfProfile = ObfProfile.RTPOPUS, obfKey = key))["obf"]!!.jsonObject
        assertEquals(ObfProfile.RTPOPUS, ok["profile"]!!.jsonPrimitive.content)
        assertEquals(key, ok["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun magicTurnOnlyWithSwitch() {
        val off = parse(base.copy(magicTurn = "turn.example:3478"))["turn"]!!.jsonObject
        assertEquals("", off["host"]!!.jsonPrimitive.content)

        val on = parse(base.copy(magicSwitch = true, magicTurn = "turn.example:3478"))["turn"]!!.jsonObject
        assertEquals("turn.example:3478", on["host"]!!.jsonPrimitive.content)
    }

    @Test
    fun nonPositiveCountsFallBackToDefaults() {
        val o = parse(base.copy(threads = 0, streamsPerCred = 0))
        assertEquals(
            ClientConfig.DEFAULT_THREADS,
            o["turn"]!!.jsonObject["n"]!!.jsonPrimitive.content.toInt()
        )
        assertEquals(
            ClientConfig.DEFAULT_STREAMS_PER_CRED,
            o["vk"]!!.jsonObject["streamsPerCred"]!!.jsonPrimitive.content.toInt()
        )
    }
}
