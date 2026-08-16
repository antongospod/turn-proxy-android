package com.freeturn.app.domain.proxy

/** Уровень ядра приходит в `onLog`; [Event] - для наших собственных строк. */
enum class LogLevel { Plain, Event, Success, Warning, Error }

data class LogEntry(val id: Long, val text: String, val level: LogLevel)
