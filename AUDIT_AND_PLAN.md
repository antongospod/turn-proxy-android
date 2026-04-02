# Комплексный аудит FreeTurn v2.0.0

**Дата:** 2026-04-03
**Модель:** claude-opus-4-6
**Охват:** все `.kt`-файлы, ресурсы, конфигурация сборки
**Последний коммит:** `b5d4f93` feat: logs screen, bottom sheet cleanup, wavy indicators, dynamic notification

---

## 1. Сборка и зависимости

### Версии инструментов

| Компонент | Версия | Статус |
|---|---|---|
| Gradle | 9.3.1 | Актуальная |
| AGP | 9.1.0 | Актуальная |
| Kotlin | 2.3.20 | Актуальная |
| Compose BOM | 2026.03.01 | Актуальная |
| compileSdk / targetSdk | 36 | Актуальная |
| minSdk | 26 | OK (Android 8.0) |

Инструменты сборки и AndroidX-зависимости свежие, обновлены до последних стабильных версий.

### Проблемы зависимостей

| Зависимость | Версия | Проблема | Severity |
|---|---|---|---|
| `security-crypto` | **1.1.0-alpha06** | Alpha в продакшне. Единственная стабильная — 1.0.0, но у неё другой API | СРЕДНЕЕ |
| `jsch` (mwiede fork) | 2.27.9 | OK, но не мейнстрим; следить за обновлениями | НИЗКОЕ |

### Конфигурация сборки

- **ProGuard/R8:** правила корректные — JSch, Tink, DataStore защищены от обфускации. Stacktrace-атрибуты сохранены.
- **Lint:** `checkReleaseBuilds = false`, `ExpiredTargetSdkVersion` отключен — осознанно.
- **Manifest:** все разрешения обоснованы; `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` на месте.
- **Packaging:** `jniLibs.useLegacyPackaging = true` — необходимо для нативного бинарника.

---

## 2. Архитектура

### Общая структура

```
MainActivity (Compose Activity)
  └── MainViewModel (AndroidViewModel)
        ├── AppPreferences (DataStore + EncryptedSharedPreferences)
        ├── SSHManager (JSch wrapper)
        └── ProxyService (Foreground Service + нативный процесс)
```

### Сильные стороны

- Чистое разделение: UI (Compose screens) → ViewModel → Data/Service
- Reactive state: `StateFlow` + `collectAsStateWithLifecycle()` повсюду
- TOFU SSH: собственный `HostKeyRepository` с MITM-детекцией
- Миграция секретов: пароль/ключ мигрируются из DataStore в EncryptedSharedPreferences
- Watchdog: экспоненциальный backoff + jitter для перезапуска нативного процесса
- Startup signal: `StartupResult` StateFlow вместо `delay(2500)`
- Динамические уведомления: текст нотификации обновляется при смене состояния (подключение, активен, ошибка, переподключение, смена сети)
- Логи вынесены в отдельный экран `LogsScreen` с отдельной вкладкой навигации

### Проблемы

#### [СРЕДНЕЕ] Глобальное состояние в `ProxyService.companion`
**Файл:** `ProxyService.kt:42-45`

```kotlin
companion object {
    val isRunning = MutableStateFlow(false)
    val logs = MutableStateFlow<List<String>>(emptyList())
    val proxyFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val startupResult = MutableStateFlow<StartupResult?>(null)
}
```

ViewModel напрямую читает и мутирует эти поля. Unit-тестирование ViewModel без реального `ProxyService` невозможно. Решение — DI (Hilt/Koin) + репозиторий-посредник.

#### [СРЕДНЕЕ] `AppPreferences` создаётся как `new` при каждом обращении
**Файлы:** `MainViewModel.kt:63`, `ProxyService.kt:127`

Два экземпляра `AppPreferences` → два разных `encryptedPrefs` lazy-инициализации. Работает корректно (оба идут к одному файлу через `applicationContext`), но расточительно. Должен быть синглтон.

#### [НИЗКОЕ] `MainViewModel` — god object (510 строк)

SSH-подключение, управление сервером, локальный прокси, настройки, кастомное ядро, сброс — всё в одном ViewModel. При росте функциональности станет проблемой.

---

## 3. Многопоточность и concurrency

### Корректно реализовано

- `AtomicBoolean` для `userStopped`, `sessionKillScheduled`
- `AtomicReference<Process>` для `process`
- `synchronized(logBuffer)` для логов ProxyService
- `serviceScope` с `SupervisorJob` вместо `runBlocking`
- `destroyForcibly()` вместо `destroy()`

### Проблемы

#### [СРЕДНЕЕ] WakeLock без timeout
**Файл:** `ProxyService.kt:110-111`

```kotlin
@Suppress("WakelockTimeout")
wakeLock?.acquire()
```

Бессрочный wake lock. Если `onDestroy()` не вызовется (OOM kill), устройство не заснёт. Рекомендация: `acquire(TimeUnit.HOURS.toMillis(8))` с периодическим продлением.

#### [СРЕДНЕЕ] `@Volatile var networkInitialized` — стилистическая непоследовательность
**Файл:** `ProxyService.kt:79`

Рядом — `AtomicBoolean` для всех остальных флагов. `@Volatile` достаточно (single-writer, multi-reader), но смешение стилей затрудняет ревью.

#### [НИЗКОЕ] `sshLogBuffer` не синхронизирован
**Файл:** `MainViewModel.kt:91-94`

```kotlin
private fun appendSshLog(vararg lines: String) {
    lines.forEach { sshLogBuffer.addLast(it) }
    while (sshLogBuffer.size > 500) sshLogBuffer.removeFirst()
    _sshLog.value = sshLogBuffer.toList()
}
```

`ArrayDeque` не thread-safe. Вызывается из `viewModelScope.launch` — потенциально из нескольких корутин одновременно.

---

## 4. Безопасность

### Сильные стороны

- SSH-пароль и ключ в `EncryptedSharedPreferences` (AES-256-GCM + Android Keystore)
- TOFU-модель для SSH host keys с MITM-детекцией
- SHA-256 верификация скачанного бинарника через `checksums.txt`
- Кастомный бинарник в приватной директории (`filesDir`)
- `ProxyService` не экспортируется (`exported="false"`)

### Проблемы

#### [ВЫСОКОЕ] Shell injection в SSH-командах
**Файл:** `MainViewModel.kt:335`

```kotlin
nohup ./${'$'}BIN -listen $l -connect $c > server.log 2>&1 &
```

`l` и `c` берутся из пользовательского ввода (`proxyListen.value`, `proxyConnect.value`). Нужна валидация формата `host:port` перед подстановкой. Пользователь вводит данные на своём сервере, но это всё равно плохая практика.

#### [СРЕДНЕЕ] TOFU: первое подключение принимает любой ключ
**Файл:** `SSHManager.kt:117`

Стандартный TOFU — допустимо, но пользователь не предупреждается.

#### [СРЕДНЕЕ] Логи копируются в clipboard без предупреждения
**Файл:** `LogsScreen.kt:60-62`

Логи могут содержать IP-адреса серверов, VK-ссылки, команды. На Android < 13 clipboard доступен всем приложениям.

#### [НИЗКОЕ] Кастомный бинарник не верифицируется
**Файл:** `MainViewModel.kt:465-468`

Любой файл, выбранный пользователем, становится executable. By design (power user feature), но стоит показывать предупреждение.

---

## 5. UI / Compose

### Сильные стороны

- **Material Design 3:** `lightColorScheme`/`darkColorScheme`, dynamic color (Android 12+), M3-компоненты
- **M3 Expressive:** `CircularWavyProgressIndicator` для состояния загрузки (Experimental M3 Expressive API)
- **Тёмная тема:** полноценная поддержка, status bar адаптируется
- **Типография:** правильная M3-шкала (36sp → 11sp) с корректными line heights
- **Анимации:** `spring()`, `tween()`, `Crossfade`, `animateColorAsState`
- **Window insets:** `navigationBarsPadding()`, `imePadding()`
- **Haptic feedback:** продуманные Apple-like паттерны
- **LazyColumn** для логов (отдельный экран `LogsScreen`)
- **`rememberSaveable`** для сохранения состояния при ротации
- **Lifecycle-aware collection:** `collectAsStateWithLifecycle()` повсюду
- **Bottom sheet:** упрощён — логи вынесены в отдельную вкладку, sheet теперь только для настроек и ссылок
- **Условный показ:** карточка настроек и SSH-статус скрыты, если SSH не настроен (`isConfigured`)
- **Навигация:** popUpTo с `inclusive = true` + `launchSingleTop` при завершении онбординга — предотвращает дублирование в back stack

### Проблемы

#### [ВЫСОКОЕ] Все строки захардкожены — нет `strings.xml`

`strings.xml` содержит только `<string name="app_name">FreeTurn</string>`.

Все UI-тексты (~100+ строк) захардкожены в Kotlin. Нарушает:
- Android guidelines: строки должны быть в ресурсах
- Локализацию: невозможно перевести без переписывания кода
- Accessibility: TalkBack может некорректно работать с некоторыми элементами

#### [СРЕДНЕЕ] `contentDescription = null` на иконках ProxyToggleButton
**Файл:** `HomeScreen.kt:347-356`

Главная кнопка приложения (148dp) недоступна для TalkBack. Нужно описание состояния.

#### [СРЕДНЕЕ] Дублирование `LogLine` composable
**Файлы:** `LogsScreen.kt:116-161`, ранее была в `HomeScreen.kt`

`LogLine` composable дублируется — если она используется только в `LogsScreen`, нужно убедиться, что в `HomeScreen` старая копия удалена. Если нужна в обоих местах — вынести в общий файл.

#### [НИЗКОЕ] `LazyColumn` без `key` в LogsScreen
**Файл:** `LogsScreen.kt:107`

```kotlin
itemsIndexed(logs) { _, line -> LogLine(line = line) }
```

Без `key` Compose не может корректно анимировать изменения списка.

#### [НИЗКОЕ] `Color(0xFFE67E22)` — magic color в LogLine
**Файл:** `LogsScreen.kt:131`

Единственный цвет вне `Color.kt`. Стоит вынести в `StatusOrange`.

#### [НИЗКОЕ] Вкладка «Логи» — одинаковые filled/outlined иконки
**Файл:** `AppNavigation.kt:297`

```kotlin
NavItem(Routes.LOGS, "Логи", R.drawable.terminal_24px, R.drawable.terminal_24px)
```

Для остальных вкладок есть отдельные filled и outlined варианты иконок. Для логов используется одна и та же иконка — нет визуальной разницы между активным и неактивным состоянием.

---

## 6. Навигация

### Сильные стороны

- `Routes` — константы вместо строковых литералов
- `saveState` / `restoreState` для сохранения стека
- `CLIENT_SETUP_OB` — константа для онбординг-маршрута
- Slide-анимации для переходов
- 4 вкладки: Главная, Сервер, Клиент, Логи
- `popUpTo(startDestinationId) { inclusive = true }` при завершении онбординга — корректная очистка стека

### Проблемы

#### [НИЗКОЕ] `startDestination` через `remember`
**Файл:** `AppNavigation.kt:86`

Не реагирует на сброс настроек. Компенсируется перезапуском Activity в `resetAllSettings()`, но хрупко.

---

## 7. Качество кода

### Сильные стороны

- `timestamp()` — единый форматтер
- `runSshCommand()` — обобщённая SSH-операция
- `ArrayDeque` для логов (O(1) вместо O(n))
- Data classes с defaults (`SshConfig`, `ClientConfig`)
- `sealed class` для состояний
- Корректный `finally`-блок в `startBinaryProcess()`
- `updateNotification()` — вынесено в отдельный метод, не дублируется

### Проблемы

#### [СРЕДНЕЕ] `SimpleDateFormat` создаётся при каждом вызове `timestamp()`
**Файл:** `MainViewModel.kt:104-106`

`SimpleDateFormat` не thread-safe, поэтому не стоит хранить в companion. Но можно использовать `DateTimeFormatter` (thread-safe):
```kotlin
private val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
private fun timestamp(): String = java.time.LocalTime.now().format(timeFormatter)
```

#### [СРЕДНЕЕ] Пустой `onCleared()`
**Файл:** `MainViewModel.kt:507-509`

Не несёт функции. Удалить.

#### [НИЗКОЕ] Дублирование `PendingIntent` в `ProxyService`
**Файл:** `ProxyService.kt:97-99, 288-290`

```kotlin
val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
    PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
}
```

Создаётся в `onStartCommand()` и повторно в `updateNotification()`. Можно сохранить как поле класса.

#### [НИЗКОЕ] `Regex` компилируется при каждом вызове
**Файл:** `ProxyService.kt:141`

```kotlin
val parts = cfg.rawCommand.trim().split("\\s+".toRegex())
```

`"\\s+".toRegex()` компилирует паттерн каждый раз. Вынести в `companion object`.

---

## 8. Тесты

Тесты полностью отсутствуют. В зависимостях есть JUnit 4, AndroidX Test, Espresso — но не используются.

Приоритетные кандидаты:
1. `AppPreferences` — сохранение/чтение/миграция/сброс
2. `TofuHostKeyRepository` — TOFU-логика, MITM-детекция
3. `isQuotaError()` — парсинг ошибок
4. `MainViewModel` — состояния и переходы (потребует мокирования `ProxyService`)

---

## 9. Сводная оценка

| Категория | Оценка | Комментарий |
|---|---|---|
| Зависимости | 9/10 | Все свежие; `security-crypto` alpha |
| Архитектура | 7/10 | Чёткое разделение; global state, god ViewModel |
| Concurrency | 8/10 | Atomics, synchronized, coroutines; WakeLock timeout |
| Безопасность | 7/10 | EncryptedPrefs, TOFU, SHA-256; shell injection |
| UI/Compose | 8/10 | M3 + Expressive, dark theme, animations, отдельный LogsScreen; строки захардкожены |
| Качество кода | 8/10 | Sealed classes, no duplication; мелкие проблемы |
| Тесты | 1/10 | Полностью отсутствуют |
| **Общее** | **7/10** | Хорошая база с активным улучшением |

---

## 10. Приоритетный план действий

### P1 — Критичные

| # | Проблема | Файл | Статус |
|---|---|---|---|
| 1 | Валидация `host:port` перед подстановкой в shell-скрипты | `MainViewModel.kt:335` | |
| 2 | `contentDescription` для ProxyToggleButton | `HomeScreen.kt:347-356` | |

### P2 — Высокие

| # | Проблема | Файл | Статус |
|---|---|---|---|
| 3 | Вынести строки в `strings.xml` (~100+ строк) | Все UI-файлы | |
| 4 | WakeLock с timeout | `ProxyService.kt:110-111` | |
| 5 | Синхронизация `sshLogBuffer` | `MainViewModel.kt:91-94` | |

### P3 — Средние

| # | Проблема | Файл | Статус |
|---|---|---|---|
| 6 | `AppPreferences` → синглтон | `AppPreferences.kt`, `ProxyService.kt:127` | |
| 7 | `DateTimeFormatter` вместо `SimpleDateFormat` | `MainViewModel.kt:104-106` | |
| 8 | Удалить пустой `onCleared()` | `MainViewModel.kt:507-509` | |
| 9 | Вынести `StatusOrange` в `Color.kt` | `LogsScreen.kt:131` | |
| 10 | `key` в `LazyColumn` для логов | `LogsScreen.kt:107` | |
| 11 | Outlined-вариант иконки для вкладки «Логи» | `AppNavigation.kt:297` | |
| 12 | Дедупликация `PendingIntent` в ProxyService | `ProxyService.kt:97,288` | |
| 13 | Написать базовые unit-тесты | Новые файлы | |

---

## Changelog

- **2026-04-03:** Актуализировано после `b5d4f93`. Добавлены: LogsScreen как отдельная вкладка, динамические уведомления в ProxyService, CircularWavyProgressIndicator (M3 Expressive), упрощение InfoBottomSheet (логи вынесены), условный показ карточки настроек, popUpTo fix в навигации. Обновлены номера строк. Новые проблемы: дублирование PendingIntent, одинаковые иконки вкладки «Логи». UI оценка повышена 7→8.
- **2026-04-02:** Первичный аудит (claude-opus-4-6).
