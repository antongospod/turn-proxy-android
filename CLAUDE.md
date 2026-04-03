# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android-приложение, которое проксирует WireGuard/Hysteria-трафик через TURN-серверы (VK Calls или Yandex Telemost). Клиентская часть проекта [vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy).

- Package: `com.vkturn.proxy`
- Min SDK: 23 (Android 6.0), Target SDK: 28, Compile SDK: 36
- Language: Kotlin, Java 11

## Build Commands

Я всегда билжу сам!
## Architecture

Приложение состоит из четырёх основных компонентов:

### `MainActivity.kt`
Главный экран: управление прокси-клиентом. Два режима ввода конфигурации — GUI (поля: peer IP:port, VK/Yandex ссылка, потоки, порт) и Raw (сырая строка аргументов). Запускает `ProxyService` при нажатии кнопки старта. Настройки сохраняются в `SharedPreferences("ProxyPrefs")`.

### `ProxyService.kt`
Foreground-сервис, который запускает нативный бинарник. Логика выбора бинарника: сначала проверяется кастомный файл `filesDir/custom_vkturn`, затем встроенная библиотека `libvkturn.so`. В GUI-режиме строит команду из полей, в Raw-режиме использует строку напрямую. Хранит последние 200 строк лога (FIFO), держит partial wake lock.

### `SettingsActivity.kt`
SSH-управление удалённым сервером: подключение (IP, порт, пользователь, пароль через `SharedPreferences("SshPrefs")`), установка бинарника с GitHub Releases в `/opt/vk-turn/`, запуск/остановка сервера через `nohup`/`pkill`, интерактивный терминал с поддержкой CTRL+C.

### `SSHManager.kt`
Обёртка над JSch. Два режима работы: интерактивный shell (PTY, стриминг вывода через callback) и тихое выполнение команд (`executeSilentCommand` — возвращает полный вывод строкой). Используется для проверки состояния сервера и управления им.

## Key Implementation Details

- **Нативный бинарник:** `app/src/main/jniLibs/arm64-v8a/libvkturn.so` — реальный исполняемый файл прокси-клиента, встроенный в APK.
- **Поддерживаемые ссылки:** VK Call (`-vk-link`) и Yandex Telemost (`-yandex-link`) — тип определяется автоматически по содержимому URL.
- **Пользовательский бинарник:** можно загрузить через file picker, сохраняется в `filesDir/custom_vkturn`, имеет приоритет над встроенным.
- **Lint:** отключён для release из-за `ExpiredTargetSdkVersion` (target SDK 28 — намеренно, не баг).
- **ProGuard:** минимальные правила, обфускация фактически отключена.
