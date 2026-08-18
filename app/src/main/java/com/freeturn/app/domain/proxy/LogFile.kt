package com.freeturn.app.domain.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Лог сессии на диске. Экранный буфер живёт в памяти и чистится на каждом старте, поэтому
 * разобрать инцидент, после которого пользователь перезапустил прокси, было нечем.
 *
 * Пишет отдельным потоком: строки приходят из горутин Go, блокировать их нельзя. Время
 * форматируется там же - SimpleDateFormat не потокобезопасен.
 */
class LogFile(dir: File) {

    private sealed interface Cmd {
        data class Line(val at: Long, val text: String, val level: LogLevel) : Cmd
        data object Clear : Cmd
    }

    private val current = File(dir, "session.log")
    private val previous = File(dir, "session.1.log")
    private val queue = Channel<Cmd>(Channel.UNLIMITED)

    init {
        dir.mkdirs()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { drain() }
    }

    fun append(message: String, level: LogLevel) {
        queue.trySend(Cmd.Line(System.currentTimeMillis(), message, level))
    }

    fun clear() {
        queue.trySend(Cmd.Clear)
    }

    /** Оба файла подряд, старый первым - хронология не должна рваться в середине. */
    fun export(target: File): Boolean = runCatching {
        target.outputStream().buffered().use { out ->
            listOf(previous, current).filter { it.exists() }.forEach { part ->
                part.inputStream().use { it.copyTo(out) }
            }
        }
        target.length() > 0
    }.getOrDefault(false)

    // Пачкой: ядро сыплет строками из нескольких горутин, открывать файл на каждую дорого.
    private suspend fun drain() {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        val batch = StringBuilder()
        for (cmd in queue) {
            apply(cmd, stamp, batch)
            while (true) {
                val next = queue.tryReceive().getOrNull() ?: break
                apply(next, stamp, batch)
            }
            if (batch.isNotEmpty()) {
                write(batch.toString())
                batch.setLength(0)
            }
        }
    }

    private fun apply(cmd: Cmd, stamp: SimpleDateFormat, batch: StringBuilder) {
        when (cmd) {
            is Cmd.Clear -> {
                batch.setLength(0)
                runCatching { current.delete(); previous.delete() }
            }
            is Cmd.Line -> batch
                .append(stamp.format(Date(cmd.at))).append(' ')
                .append(cmd.level.tag()).append(' ')
                .append(cmd.text).append('\n')
        }
    }

    private fun write(text: String) {
        runCatching {
            if (current.length() > MAX_BYTES) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText(text)
        }
    }

    private fun LogLevel.tag(): String = when (this) {
        LogLevel.Error -> "E"
        LogLevel.Warning -> "W"
        LogLevel.Success -> "S"
        LogLevel.Event -> "*"
        LogLevel.Plain -> " "
    }

    private companion object {
        const val MAX_BYTES = 1_000_000L
    }
}
