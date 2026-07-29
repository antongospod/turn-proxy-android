package com.freeturn.app.domain.proxy

enum class LogLevel { Plain, Event, Success, Warning, Error }

data class LogEntry(val id: Long, val text: String, val level: LogLevel)

/** Уровень строки по ключевым словам ядра и наших сообщений (ru + en). */
fun classifyLogLine(line: String): LogLevel {
    val lower = line.lowercase()
    return when {
        lower.contains("ошибка") || lower.contains("error") ||
            lower.contains("критическая") || lower.contains("failed") ||
            lower.contains("fatal") || lower.contains("panic") ||
            lower.contains("не удалось") -> LogLevel.Error

        lower.contains("watchdog") || lower.contains("перезапуск") ||
            lower.contains("переподключение") || lower.contains("квота") ||
            lower.contains("quota") || lower.contains("warn") ||
            lower.contains("недоступна") -> LogLevel.Warning

        lower.contains("запущен") || lower.contains("подключен") ||
            lower.contains("success") || lower.contains("started") ||
            lower.contains("established") -> LogLevel.Success

        lower.contains("запуск прокси") || lower.contains("остановка") ||
            lower.contains("процесс остановлен") || lower.contains("сессия завершена") ||
            lower.contains("быстрый выход") || lower.startsWith("сеть:") -> LogLevel.Event

        else -> LogLevel.Plain
    }
}
