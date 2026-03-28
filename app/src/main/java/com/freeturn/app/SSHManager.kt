package com.freeturn.app

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

class SSHManager {

    /** Отпечаток, полученный при последнем вызове executeSilentCommand */
    @Volatile var lastSeenFingerprint: String? = null
        private set

    /**
     * Выполняет команду по SSH и возвращает вывод.
     *
     * @param knownFingerprint SHA-256 отпечаток хоста из сохранённых настроек.
     *   - null  → первое подключение, принимаем любой ключ и сохраняем отпечаток
     *   - строка → проверяем совпадение; при расхождении возвращаем ошибку MITM
     */
    suspend fun executeSilentCommand(
        ip: String, port: Int, user: String, pass: String, command: String,
        knownFingerprint: String? = null
    ): String = withContext(Dispatchers.IO) {
        val tofu = TofuHostKeyRepository(knownFingerprint)
        var tempSession: Session? = null
        try {
            val jsch = JSch()
            tempSession = jsch.getSession(user, ip, port)
            tempSession.setPassword(pass)
            tempSession.hostKeyRepository = tofu

            val config = Properties()
            // StrictHostKeyChecking = "no" нужен чтобы JSch не требовал known_hosts файл;
            // реальную проверку выполняет TofuHostKeyRepository
            config["StrictHostKeyChecking"] = "no"
            tempSession.setConfig(config)
            tempSession.connect(5000)

            lastSeenFingerprint = tofu.capturedFingerprint

            val channel = tempSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.inputStream = null  // не подключать stdin

            val inStream = channel.inputStream
            val errStream = channel.errStream
            channel.connect()

            // Читаем stderr в отдельном потоке чтобы не было дедлока
            val errBuilder = StringBuilder()
            val errThread = thread {
                errStream.bufferedReader().forEachLine { errBuilder.appendLine(it) }
            }

            val stdout = inStream.bufferedReader().use { reader ->
                buildString {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        append(line).append("\n")
                    }
                }
            }
            errThread.join(3_000)
            channel.disconnect()

            val stderr = errBuilder.toString().trim()
            buildString {
                append(stdout.trim())
                if (stderr.isNotEmpty()) {
                    if (stdout.isNotBlank()) append("\n")
                    append(stderr)
                }
            }
        } catch (e: Exception) {
            // Если отпечаток изменился — конкретное сообщение об угрозе
            val isMitm = tofu.capturedFingerprint != null
                && knownFingerprint != null
                && tofu.capturedFingerprint != knownFingerprint
            if (isMitm) {
                "ERROR: Отпечаток сервера изменился — возможна MITM-атака\n" +
                "Ожидался: $knownFingerprint\n" +
                "Получен:  ${tofu.capturedFingerprint}"
            } else {
                "ERROR: ${e.message}"
            }
        } finally {
            tempSession?.disconnect()
        }
    }

    fun disconnect() {
        // Все сессии в executeSilentCommand временные и закрываются в finally-блоке
    }
}

/**
 * TOFU (Trust On First Use) репозиторий ключей хостов для JSch.
 *
 * Логика:
 * - knownFingerprint == null → первое подключение, принимаем любой ключ
 * - knownFingerprint совпадает → ОК
 * - knownFingerprint не совпадает → CHANGED, JSch бросает JSchException
 */
private class TofuHostKeyRepository(
    private val knownFingerprint: String?
) : HostKeyRepository {

    var capturedFingerprint: String? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        capturedFingerprint = sha256Fingerprint(key)
        return when {
            knownFingerprint == null               -> HostKeyRepository.OK
            knownFingerprint == capturedFingerprint -> HostKeyRepository.OK
            else                                   -> HostKeyRepository.CHANGED
        }
    }

    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    override fun getKnownHostsRepositoryID(): String = "TOFU"
    override fun add(hostkey: HostKey, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}

    private fun sha256Fingerprint(key: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash)
    }
}
