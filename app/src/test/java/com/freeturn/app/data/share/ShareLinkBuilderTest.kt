package com.freeturn.app.data.share

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerOpts
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareLinkBuilderTest {

    private val key = "ab".repeat(32)

    private fun server(
        useUdp: Boolean = false,
        opts: ServerOpts = ServerOpts()
    ) = Server(
        name = "Мой сервер",
        client = ClientConfig(
            serverAddress = "1.2.3.4:56000",
            useUdp = useUdp
        ),
        opts = opts
    )

    @Test
    fun `server run args take priority over local opts`() {
        val srv = server(opts = ServerOpts("rtpopus", "ff".repeat(32)))
        val info = ShareInfo(obfProfile = "rtpopus", obfKey = key, wgBackend = true)
        val link = FreeturnLink.parse(ShareLinkBuilder.build(srv, info, "Гость", null)).getOrThrow()
        assertEquals(key, link.obfKey)       // ключ с сервера, не локальный
        assertEquals("Гость", link.name)
    }

    @Test
    fun `fallback to local opts when server never started`() {
        val srv = server(opts = ServerOpts("rtpopus", key))
        val link = FreeturnLink.parse(
            ShareLinkBuilder.build(srv, ShareInfo(), "u", null)
        ).getOrThrow()
        assertEquals("rtpopus", link.obfProfile)
        assertEquals(key, link.obfKey)
    }

    // Сервер стартовал без обфускации - локальные opts всё равно не подмешиваем.
    @Test
    fun `started server without obf wins over local opts`() {
        val srv = server(opts = ServerOpts("rtpopus", key))
        val link = FreeturnLink.parse(
            ShareLinkBuilder.build(srv, ShareInfo(obfProfile = "none"), "u", null)
        ).getOrThrow()
        assertEquals("", link.obfProfile)
        assertEquals("", link.obfKey)
    }

    @Test
    fun `invalid obf key is dropped`() {
        val srv = server(opts = ServerOpts("rtpopus", "короткий"))
        val link = FreeturnLink.parse(ShareLinkBuilder.build(srv, ShareInfo(), "u", null)).getOrThrow()
        assertEquals("", link.obfProfile)
        assertEquals("", link.obfKey)
    }

    @Test
    fun `wg conf is normalized comments and blanks stripped`() {
        val conf = "[Interface]\n# комментарий\n  PrivateKey = abc=  \n\n; ещё\n[Peer]\nPublicKey = def="
        val link = FreeturnLink.parse(
            ShareLinkBuilder.build(server(), ShareInfo(wgBackend = true), "u", conf)
        ).getOrThrow()
        assertEquals("[Interface]\nPrivateKey = abc=\n[Peer]\nPublicKey = def=", link.wgConf)
    }

    @Test
    fun `mtu line stripped from conf`() {
        val conf = "[Interface]\nPrivateKey = abc=\nMTU = 1500\n[Peer]\nPublicKey = def="
        val link = FreeturnLink.parse(
            ShareLinkBuilder.build(server(), ShareInfo(wgBackend = true), "u", conf)
        ).getOrThrow()
        assertEquals("[Interface]\nPrivateKey = abc=\n[Peer]\nPublicKey = def=", link.wgConf)
    }

    @Test
    fun `proxy share has no wg field`() {
        val raw = ShareLinkBuilder.build(server(), ShareInfo(obfProfile = "none"), "u", null)
        assertEquals("", FreeturnLink.parse(raw).getOrThrow().wgConf)
    }

    @Test
    fun `client id carried into cid field`() {
        val cid = "0123456789abcdef0123456789abcdef"
        val link = FreeturnLink.parse(
            ShareLinkBuilder.build(server(), ShareInfo(), "u", null, cid)
        ).getOrThrow()
        assertEquals(cid, link.clientId)
    }

    @Test
    fun `threads and streams-per-cred carried over`() {
        val srv = Server(
            name = "s",
            client = ClientConfig(serverAddress = "1.2.3.4:56000", threads = 6, streamsPerCred = 4)
        )
        val link = FreeturnLink.parse(ShareLinkBuilder.build(srv, ShareInfo(), "u", null)).getOrThrow()
        assertEquals(6, link.n)
        assertEquals(4, link.streamsPerCred)
    }

    @Test
    fun `udp transport flag carried over`() {
        val link = FreeturnLink.parse(
            ShareLinkBuilder.build(server(useUdp = true), ShareInfo(), "u", null)
        ).getOrThrow()
        assertEquals("udp", link.transport)
    }
}
