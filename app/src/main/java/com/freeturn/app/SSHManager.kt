package com.freeturn.app

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

class SSHManager {

    suspend fun executeSilentCommand(
        ip: String, port: Int, user: String, pass: String, command: String
    ): String = withContext(Dispatchers.IO) {
        var tempSession: Session? = null
        try {
            val jsch = JSch()
            tempSession = jsch.getSession(user, ip, port)
            tempSession.setPassword(pass)

            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            tempSession.setConfig(config)
            tempSession.connect(5000)

            val channel = tempSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.inputStream = null  // не подключать stdin
            channel.setErrStream(null)

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
            "ERROR: ${e.message}"
        } finally {
            tempSession?.disconnect()
        }
    }

    fun disconnect() {
        // Все сессии в executeSilentCommand временные и закрываются в finally-блоке
    }
}
