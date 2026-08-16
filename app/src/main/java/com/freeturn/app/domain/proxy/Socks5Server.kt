package com.freeturn.app.domain.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/**
 * SOCKS5 (RFC 1928) для раздачи туннеля наружу - через точку доступа или по локальной
 * сети. Только CONNECT: UDP ASSOCIATE не реализован, поэтому у клиентов нет QUIC и
 * UDP-DNS - им нужен remote DNS через сам прокси.
 *
 * Имеет смысл только в туннельном режиме. Сокеты к цели намеренно НЕ выводятся из
 * VPN - именно они и должны уйти в tun; наружу выводится обратный канал к клиенту
 * ([protect]), иначе ответы в локальную сеть уехали бы в туннель.
 *
 * Слушает 0.0.0.0 без авторизации: открыт всей локальной сети, не только клиентам
 * точки доступа.
 */
class Socks5Server(
    private val protect: (Socket) -> Boolean,
    private val port: Int = DEFAULT_PORT,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Слушающий сокет - под монитором: bind идёт на потоке вызывающего, чтобы
    // BindException был виден ему, а не утонул в корутине.
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val socket = try {
            ServerSocket(port, BACKLOG, InetAddress.getByName(BIND_ADDRESS))
        } catch (e: Exception) {
            ProxyStore.log("SOCKS5: не поднялся на $BIND_ADDRESS:$port - ${e.message}", LogLevel.Error)
            return
        }
        serverSocket = socket
        acceptJob = scope.launch { acceptLoop(socket) }
        ProxyStore.log("SOCKS5: раздача туннеля на $BIND_ADDRESS:$port (только TCP)")
    }

    @Synchronized
    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        // Сокет закрываем до отмены: accept() блокирующий и на отмену корутины не смотрит.
        try { socket.close() } catch (_: Exception) {}
        acceptJob = null
        scope.coroutineContext.cancelChildren()
        ProxyStore.log("SOCKS5: раздача остановлена")
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (coroutineContext.isActive) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                // Закрытый из stop() сокет - штатный выход, о нём молчим.
                if (serverSocket != null) {
                    ProxyStore.log("SOCKS5: приём прерван - ${e.message}", LogLevel.Warning)
                }
                return
            }
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        var target: Socket? = null
        try {
            // Обратный канал - мимо туннеля: приложение теперь внутри tun, и ответы
            // клиенту в локальную сеть без этого ушли бы в туннель.
            protect(client)

            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!negotiate(input, output)) return@withContext

            if (input.readByte() != VERSION) return@withContext
            val command = input.readByte()
            input.readByte() // RSV
            val addressType = input.readByte()

            if (command != CMD_CONNECT) {
                sendReply(output, REPLY_COMMAND_NOT_SUPPORTED)
                return@withContext
            }
            // Длину неизвестного типа адреса не угадать - дочитать до порта нечем,
            // поэтому соединение после ответа закрывается.
            val host = readHost(input, addressType) ?: run {
                sendReply(output, REPLY_ADDRESS_TYPE_NOT_SUPPORTED)
                return@withContext
            }
            val targetPort = readPort(input)

            val socket = Socket()
            target = socket
            try {
                socket.connect(InetSocketAddress(host, targetPort), CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                sendReply(output, replyFor(e))
                return@withContext
            }
            sendReply(output, REPLY_SUCCESS)

            val upstream = launch { pipe(input, socket.getOutputStream(), socket) }
            val downstream = launch { pipe(socket.getInputStream(), output, client) }
            upstream.join()
            downstream.join()
        } catch (_: EOFException) {
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { target?.close() } catch (_: Exception) {}
        }
    }

    /** false - клиент не предложил "без авторизации" либо поздоровался не по протоколу. */
    private fun negotiate(input: InputStream, output: OutputStream): Boolean {
        if (input.readByte() != VERSION) return false
        val methodCount = input.readByte()
        if (methodCount <= 0) return false
        val methods = input.readExactly(methodCount)
        if (methods.none { it.toInt() and 0xFF == METHOD_NO_AUTH }) {
            output.writeBytes(VERSION, METHOD_NONE_ACCEPTABLE)
            return false
        }
        output.writeBytes(VERSION, METHOD_NO_AUTH)
        return true
    }

    private fun readHost(input: InputStream, addressType: Int): String? = when (addressType) {
        ATYP_IPV4 -> InetAddress.getByAddress(input.readExactly(4)).hostAddress
        ATYP_IPV6 -> InetAddress.getByAddress(input.readExactly(16)).hostAddress
        ATYP_DOMAIN -> {
            val length = input.readByte()
            // Имя хоста в SOCKS5 - ASCII; платформенная кодировка ломала бы IDN-punycode.
            if (length <= 0) null else String(input.readExactly(length), StandardCharsets.US_ASCII)
        }
        else -> null
    }

    private fun readPort(input: InputStream): Int {
        val bytes = input.readExactly(2)
        return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    }

    private fun sendReply(output: OutputStream, reply: Int) {
        // VER REP RSV ATYP=IPv4 BND.ADDR(4) BND.PORT(2). Привязку не сообщаем -
        // для CONNECT клиенты её не используют.
        output.writeBytes(VERSION, reply, 0, ATYP_IPV4, 0, 0, 0, 0, 0, 0)
    }

    private fun replyFor(e: Exception): Int = when (e) {
        is ConnectException -> REPLY_CONNECTION_REFUSED
        is NoRouteToHostException -> REPLY_HOST_UNREACHABLE
        is UnknownHostException -> REPLY_HOST_UNREACHABLE
        is SocketTimeoutException -> REPLY_HOST_UNREACHABLE
        else -> REPLY_GENERAL_FAILURE
    }

    /**
     * Половина дуплекса. По концу источника закрывает [sink] на запись: без FIN
     * встречная сторона держала бы соединение открытым, а обе `join` не возвращались бы
     * до таймаута где-то в сети.
     */
    private suspend fun pipe(
        source: InputStream,
        destination: OutputStream,
        sink: Socket,
    ) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                destination.write(buffer, 0, read)
                destination.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { sink.shutdownOutput() } catch (_: Exception) {}
        }
    }

    companion object {
        const val DEFAULT_PORT = 1080

        private const val BIND_ADDRESS = "0.0.0.0"
        private const val BACKLOG = 50
        private const val BUFFER_SIZE = 8192
        private const val CONNECT_TIMEOUT_MS = 10_000

        private const val VERSION = 5
        private const val METHOD_NO_AUTH = 0x00
        private const val METHOD_NONE_ACCEPTABLE = 0xFF
        private const val CMD_CONNECT = 0x01
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04

        private const val REPLY_SUCCESS = 0x00
        private const val REPLY_GENERAL_FAILURE = 0x01
        private const val REPLY_HOST_UNREACHABLE = 0x04
        private const val REPLY_CONNECTION_REFUSED = 0x05
        private const val REPLY_COMMAND_NOT_SUPPORTED = 0x07
        private const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
    }
}

private fun InputStream.readByte(): Int = read().also { if (it == -1) throw EOFException() }

private fun InputStream.readExactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        if (read == -1) throw EOFException()
        offset += read
    }
    return bytes
}

private fun OutputStream.writeBytes(vararg values: Int) {
    write(ByteArray(values.size) { values[it].toByte() })
    flush()
}
