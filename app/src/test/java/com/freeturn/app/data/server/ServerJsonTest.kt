package com.freeturn.app.data.server

import com.freeturn.app.data.config.KcpProfile
import com.freeturn.app.data.config.ProxyMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerJsonTest {

    @Test
    fun `opts round trip keeps mode and arq`() {
        val srv = Server(
            name = "s",
            opts = ServerOpts(
                proxyMode = ProxyMode.TCP,
                kcp = KcpProfile.MOBILE
            )
        )
        val decoded = ServerJson.decodeList(ServerJson.encodeList(listOf(srv))).single()
        assertEquals(ProxyMode.TCP, decoded.opts.proxyMode)
        assertEquals(KcpProfile.MOBILE, decoded.opts.kcp)
    }

    @Test
    fun `snapshot without mode and kcp falls back to defaults`() {
        val raw = """[{"id":"1","name":"s","opts":{"obfProfile":"none","obfKey":""}}]"""
        val decoded = ServerJson.decodeList(raw).single()
        assertEquals(ProxyMode.UDP, decoded.opts.proxyMode)
        assertEquals(KcpProfile.DEFAULT, decoded.opts.kcp)
    }

    @Test
    fun `unknown mode falls back to udp`() {
        val raw = """[{"id":"1","name":"s","opts":{"proxyMode":"quic"}}]"""
        assertEquals(ProxyMode.UDP, ServerJson.decodeList(raw).single().opts.proxyMode)
    }
}
