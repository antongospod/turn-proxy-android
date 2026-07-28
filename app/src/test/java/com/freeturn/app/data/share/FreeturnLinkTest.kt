package com.freeturn.app.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class FreeturnLinkTest {

    /** Ссылка из docs/uri.md Go-репо: {"v":1,"provider":"vk","peer":"1.2.3.4:56000"}. */
    private val goldenMinimal = "freeturn://eyJ2IjoxLCJwcm92aWRlciI6InZrIiwicGVlciI6IjEuMi4zLjQ6NTYwMDAifQ"

    private val wgConf = """
        [Interface]
        PrivateKey = aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa=
        Address = 10.13.13.3/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb=
        AllowedIPs = 0.0.0.0/0
        Endpoint = 127.0.0.1:9000
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun `golden minimal - parse and re-encode byte for byte`() {
        val link = FreeturnLink.parse(goldenMinimal).getOrThrow()
        assertEquals("vk", link.provider)
        assertEquals("1.2.3.4:56000", link.peer)
        assertEquals("", link.wgConf)
        assertEquals(goldenMinimal, link.encode())
    }

    @Test
    fun `golden typical - encode matches Go field order and omitempty`() {
        // Эталон: байт-в-байт вывод Go json.Marshal(wire) для типового конфига.
        val goldenJson = """{"v":1,"provider":"vk","peer":"1.2.3.4:56000","mode":"udp",""" +
            """"obf":"rtpopus","key":"d823fa01cb3e0609b67322f7cf984c4ee2e4ce2e294936fc24ef38c9e59f4799","name":"Papa"}"""
        val golden = "freeturn://" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(goldenJson.toByteArray(Charsets.UTF_8))

        val link = FreeturnLink(
            provider = "vk",
            peer = "1.2.3.4:56000",
            mode = "udp",
            obfProfile = "rtpopus",
            obfKey = "d823fa01cb3e0609b67322f7cf984c4ee2e4ce2e294936fc24ef38c9e59f4799",
            name = "Papa"
        )
        assertEquals(golden, link.encode())
    }

    @Test
    fun `round trip all fields with multiline wg conf`() {
        val original = FreeturnLink(
            provider = "vk",
            peer = "example.com:56000",
            transport = "udp",
            mode = "tcp",
            bond = true,
            obfProfile = "rtpopus",
            obfKey = "00".repeat(32),
            n = 10,
            streamsPerCred = 6,
            clientId = "cid-1",
            listen = "127.0.0.1:9000",
            dnsMode = "doh",
            dnsServers = "8.8.8.8,8.8.4.4",
            manualCaptcha = true,
            name = "Тест Юзер",
            wgConf = wgConf
        )
        assertEquals(original, FreeturnLink.parse(original.encode()).getOrThrow())
    }

    @Test
    fun `obf none is omitted entirely`() {
        val link = FreeturnLink(provider = "vk", peer = "1.2.3.4:56000", obfProfile = "none", obfKey = "ff".repeat(32))
        val parsed = FreeturnLink.parse(link.encode()).getOrThrow()
        assertEquals("", parsed.obfProfile)
        assertEquals("", parsed.obfKey)
    }

    @Test
    fun `unknown json fields are ignored`() {
        val json = """{"v":1,"provider":"vk","peer":"1.2.3.4:56000","future_field":"x"}"""
        val raw = "freeturn://" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        assertEquals("1.2.3.4:56000", FreeturnLink.parse(raw).getOrThrow().peer)
    }

    @Test
    fun `parse errors`() {
        assertTrue(FreeturnLink.parse("http://x").isFailure)            // схема
        assertTrue(FreeturnLink.parse("freeturn://").isFailure)         // пустой payload
        assertTrue(FreeturnLink.parse("freeturn://!!!").isFailure)      // битый base64
        assertTrue(FreeturnLink.parse(b64Link("not json")).isFailure)   // битый json
        assertTrue(FreeturnLink.parse(b64Link("""{"v":2,"provider":"vk","peer":"x"}""")).isFailure)
        assertTrue(FreeturnLink.parse(b64Link("""{"v":1,"peer":"x"}""")).isFailure)
        assertTrue(FreeturnLink.parse(b64Link("""{"v":1,"provider":"vk"}""")).isFailure)
    }

    @Test
    fun `looksLikeLink`() {
        assertTrue(FreeturnLink.looksLikeLink("  FREETURN://abc "))
        assertFalse(FreeturnLink.looksLikeLink("https://example.com"))
    }

    private fun b64Link(json: String): String =
        "freeturn://" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
}
