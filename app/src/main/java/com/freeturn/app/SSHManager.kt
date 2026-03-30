package com.freeturn.app

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     * @param sshKey PEM-строка приватного ключа. Если не пустая — используется key-аутентификация
     *   вместо пароля. Поддерживаются RSA, EC, Ed25519 (JSch).
     */
    suspend fun executeSilentCommand(
        ip: String, port: Int, user: String, pass: String, command: String,
        knownFingerprint: String? = null,
        sshKey: String = ""
    ): String = withContext(Dispatchers.IO) {
        val tofu = TofuHostKeyRepository(knownFingerprint)
        var tempSession: Session? = null
        try {
            val jsch = JSch()
            if (sshKey.isNotBlank()) {
                // Аутентификация по приватному ключу (без парольной фразы)
                jsch.addIdentity("identity", sshKey.toByteArray(Charsets.UTF_8), null, null)
            }
            tempSession = jsch.getSession(user, ip, port)
            if (sshKey.isBlank()) {
                tempSession.setPassword(pass)
            }
            tempSession.hostKeyRepository = tofu

            val config = Properties()
            // StrictHostKeyChecking = "no" нужен чтобы JSch не требовал known_hosts файл;
            // реальную проверку выполняет TofuHostKeyRepository
            config["StrictHostKeyChecking"] = "no"
            tempSession.setConfig(config)
            tempSession.connect(5000)

            lastSeenFingerprint = tofu.capturedFingerprint

            val channel = tempSession.openChannel("exec") as ChannelExec

            // "exec 2>&1" merges stderr into stdout at shell level — no separate thread needed.
            // Also strip \r: Kotlin CRLF source files embed carriage returns into string literals
            // which corrupts variable values on the remote Linux shell.
            val sanitized = command.replace("\r\n", "\n").replace("\r", "\n")
            channel.setCommand("exec 2>&1\n$sanitized")

            // Получаем stdout-поток ДО connect() — после уже нельзя
            val inStream = channel.inputStream
            channel.connect()

            val output = inStream.bufferedReader().use { reader ->
                buildString {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        append(line).append("\n")
                    }
                }
            }
            channel.disconnect()
            output.trim()
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
