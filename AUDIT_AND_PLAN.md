# Аудит кода и план исправлений — FreeTurn (vk-turn-proxy-android)

**Дата:** 2026-03-30
**Модель:** claude-sonnet-4-6
**Охват:** все `.kt`-файлы в `app/src/main/java/`

---

## Часть 1 — Аудит качества кода (детальный)

### 1.1 Гонки и многопоточность

#### [КРИТИЧНО] `ProxyService`: гонка между `userStopped` и `process`
**Файл:** `ProxyService.kt:43,188`

```kotlin
// Фоновый поток
if (!userStopped) thread { startBinaryProcess() }   // <-- читаем flag

// Главный поток (onDestroy) — может исполниться МЕЖДУ строками выше
userStopped = true
process?.destroy()
// Новый поток уже создан, процесс запустится после destroy()
```

`@Volatile` гарантирует видимость значения, но не атомарность операции «проверить + запустить поток». Нужен `AtomicBoolean` + синхронизированный блок.

#### [КРИТИЧНО] `sessionKillScheduled`: несинхронизированный доступ из двух потоков
**Файл:** `ProxyService.kt:131-140`

Флаг читается в фоновом потоке (`BufferedReader.readLine` loop), а сбрасывается в `handler.postDelayed` — на main looper. Не `@Volatile`, не `AtomicBoolean`. При двух параллельных quota-ошибках флаг может не защитить от двойного `postDelayed`.

#### [КРИТИЧНО] `runBlocking` в фоновом потоке при чтении DataStore
**Файл:** `ProxyService.kt:86`

```kotlin
val cfg = runBlocking { AppPreferences(this@ProxyService).clientConfigFlow.first() }
```

DataStore использует `Dispatchers.IO`. `runBlocking` на фоновом thread занимает один поток пула. При насыщении `Dispatchers.IO` — дедлок. Нужен `CoroutineScope(Dispatchers.IO).launch { ... }` внутри `onStartCommand`.

---

### 1.2 Утечки и lifecycle

#### [ВЫСОКОЕ] `AppPreferences` создаётся с `Service` context вместо `applicationContext`
**Файл:** `ProxyService.kt:86`, `AppPreferences.kt:41`

`EncryptedSharedPreferences` инициализируется lazy — может произойти уже после `onDestroy()`. Правильно: `AppPreferences(this@ProxyService.applicationContext)`.

#### [ВЫСОКОЕ] `companion object MutableStateFlow` — глобальные синглтоны, не привязанные к lifecycle
**Файл:** `ProxyService.kt:32-34`, `MainViewModel.kt:96,390`

`ProxyService.logs`, `isRunning`, `proxyFailed` — глобальные изменяемые состояния. ViewModel напрямую экспортирует и мутирует их:

```kotlin
val logs: StateFlow<List<String>> = ProxyService.logs   // прямая ссылка
ProxyService.logs.value = emptyList()                   // прямая мутация из ViewModel
```

Это делает Unit-тестирование невозможным и нарушает инкапсуляцию сервиса.

---

### 1.3 Логика и корректность

#### [ВЫСОКОЕ] Бесконечный цикл автосохранения в `ClientSetupScreen`
**Файл:** `ClientSetupScreen.kt:80-111`

```kotlin
var serverAddress by rememberSaveable(saved.serverAddress) { mutableStateOf(saved.serverAddress) }

LaunchedEffect(serverAddress, ...) {
    delay(600)
    viewModel.saveClientConfig(ClientConfig(serverAddress = serverAddress.trim(), ...))
}
```

При сохранении `saved` обновляется → если `saved.serverAddress != serverAddress` (например, из-за `.trim()`), `rememberSaveable` сбрасывает локальное состояние → триггерит `LaunchedEffect` снова. На практике стабилизируется, но логика хрупкая.

#### [ВЫСОКОЕ] `delay(2500)` + парсинг строк лога вместо явного сигнала запуска
**Файл:** `MainViewModel.kt:393-415`

Определение успешного старта прокси — эвристика:
```kotlin
delay(2500)
val logText = ProxyService.logs.value.joinToString("\n").lowercase()
when {
    !ProxyService.isRunning.value -> setErrorWithAutoReset("Прокси не запустился")
    logText.contains("panic") || logText.contains("fatal") -> ...
    else -> _proxyState.value = ProxyState.Running
}
```

На медленных устройствах 2500 мс может не хватить. На быстрых — лишняя задержка. Нужен отдельный `StateFlow` «бинарник готов» или парсинг первой non-error строки.

#### [СРЕДНЕЕ] `checkServerState`: не сбрасывает `_serverState` при пустом IP
**Файл:** `MainViewModel.kt:202-204`

```kotlin
val cfg = config ?: activeSshConfig ?: sshConfig.value
if (cfg.ip.isEmpty()) return   // silently returns, _serverState остаётся Checking
```

Если `checkServerState()` вызвана с пустым конфигом после `_serverState.value = ServerState.Checking`, состояние зависнет в `Checking` навсегда.

#### [СРЕДНЕЕ] Фейковый прогресс SSH-подключения
**Файл:** `MainViewModel.kt:157-160`

```kotlin
delay(400)
_sshState.value = SshConnectionState.Connecting("Аутентификация...")
delay(300)
_sshState.value = SshConnectionState.Connecting("Проверка SSH...")
// только ЗДЕСЬ начинается реальное подключение
```

UI показывает "Аутентификация..." за 300 мс до того как JSch вообще открыл сокет. Вводит пользователя в заблуждение.

#### [СРЕДНЕЕ] `SSHManager.disconnect()` — публичный no-op
**Файл:** `SSHManager.kt:88-90`

```kotlin
fun disconnect() {
    // Все сессии в executeSilentCommand временные и закрываются в finally-блоке
}
```

Вызывается из `connectSsh()` и `onCleared()` с ожиданием что-то сделает. При добавлении persistent-сессии в будущем — молчаливый баг.

#### [СРЕДНЕЕ] `proc.waitFor(5, TimeUnit.MINUTES)` — блокировка фонового потока
**Файл:** `ProxyService.kt:145`

Если нативный процесс закрыл stdout, но завис — фоновый поток заблокирован на 5 минут. `isRunning.value` остаётся `true`, UI не реагирует.

---

### 1.4 Дублирование кода

#### [СРЕДНЕЕ] `SimpleDateFormat` создаётся 5 раз идентично
**Файл:** `MainViewModel.kt:162,213,274,310,335,352`

```kotlin
// Эта строка повторяется дословно 5 раз:
val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
```

Нужна `private fun timestamp(): String`.

#### [СРЕДНЕЕ] SSH-операции дублируют паттерн: `ts → appendSshLog → logCommand → executeSilentCommand → forEach → checkServerState`
**Файл:** `MainViewModel.kt:248-342`

`installServer()`, `startServer()`, `stopServer()`, `fetchServerLogs()` — практически идентичная структура. Подходит для обобщения в `private suspend fun runSshOperation(label: String, script: String, cfg: SshConfig): String`.

---

### 1.5 Compose-специфика

#### [СРЕДНЕЕ] `ServerManagementScreen`: SSH-лог рендерится через `Column + forEach` (не lazy)
**Файл:** `ServerManagementScreen.kt:286-302`

```kotlin
Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(...)) {
    sshLog.forEach { line -> Text(line, ...) }  // все 500 строк композируются одновременно
}
```

Нужен `LazyColumn` с `items(sshLog)`.

#### [СРЕДНЕЕ] `LogsPanel` в `HomeScreen` — аналогичная проблема
**Файл:** `HomeScreen.kt:546-551`

```kotlin
logs.forEachIndexed { index, line -> LogLine(line, index % 2 == 0) }
```

При 200 строках — 200 Text composable одновременно.

#### [НИЗКОЕ] `lastSliderInt` в `ClientSetupScreen` — `remember`, не `rememberSaveable`
**Файл:** `ClientSetupScreen.kt:87`

После ротации экрана `lastSliderInt` сбрасывается в `saved.threads`, haptic сработает при первом же движении слайдера.

#### [НИЗКОЕ] `startDestination` запоминается один раз через `remember`
**Файл:** `AppNavigation.kt:86`

```kotlin
val startDestination = remember { if (onboardingDone) Routes.HOME else Routes.ONBOARDING }
```

После сброса настроек (`onboardingDone = false`) NavHost не пересоздастся — пользователь не попадёт на онбординг без перезапуска Activity.

---

### 1.6 Навигация

#### [СРЕДНЕЕ] Строковая конкатенация вместо константы
**Файл:** `AppNavigation.kt:159,166`

```kotlin
navController.navigate(Routes.CLIENT_SETUP + "_onboarding")  // не константа
composable(Routes.CLIENT_SETUP + "_onboarding") { ... }
```

Если `Routes.CLIENT_SETUP` переименовать — этот маршрут сломается без ошибки компилятора.

#### [НИЗКОЕ] `saveState = true` в Bottom Nav может восстанавливать устаревший Compose-стейт
**Файл:** `AppNavigation.kt:104`

При переключении вкладок `rememberSaveable`-состояние (включая VK-ссылку) восстанавливается из Bundle. После долгого отсутствия на вкладке это может быть неожиданным поведением.

---

### 1.7 Прочее

#### [СРЕДНЕЕ] O(n) аллокации на каждую строку лога (2 места)
**Файл:** `ProxyService.kt:37`, `MainViewModel.kt:84`

```kotlin
logs.update { (it + msg).takeLast(200) }          // 2 new List на каждую строку
_sshLog.value = (_sshLog.value + lines).takeLast(500)  // аналогично
```

`ArrayDeque<String>` с максимальным размером — O(1) добавление и удаление.

#### [НИЗКОЕ] `process.destroy()` → SIGTERM вместо `destroyForcibly()` → SIGKILL
**Файл:** `ProxyService.kt:139,207,239`

`minSdk = 26`, `destroyForcibly()` доступен. Нативный бинарник может не обрабатывать SIGTERM.

#### [НИЗКОЕ] SSH-лог доступен через clipboard всем приложениям
**Файл:** `HomeScreen.kt:429-431`

SSH-лог содержит IP-сервера, пути, команды. Копируется в системный clipboard без предупреждения.

---

## Часть 2 — Функция «Сбросить все настройки»

### Описание

Полный сброс всех данных приложения к заводским настройкам:
- Очистка DataStore (`app_prefs`)
- Очистка EncryptedSharedPreferences (`secure_ssh_prefs`) — пароль и SSH-ключ
- Удаление кастомного бинарника (`filesDir/custom_vkturn`)
- Сброс флага онбординга → показ онбординга при следующем запуске
- Остановка прокси-сервиса если запущен
- Сброс всех in-memory состояний в ViewModel
- Перезапуск Activity для корректного сброса NavHost

### Затрагиваемые файлы

| Файл | Изменение |
|---|---|
| `AppPreferences.kt` | `suspend fun resetAll()` — очищает DataStore + EncryptedSharedPreferences |
| `MainViewModel.kt` | `fun resetAllSettings(context: Context)` — оркестрирует сброс, перезапускает Activity |
| `HomeScreen.kt` (InfoBottomSheet) | Кнопка «Сбросить настройки» с диалогом подтверждения |

### Реализация — `AppPreferences.resetAll()`

```kotlin
suspend fun resetAll() {
    // 1. DataStore
    context.dataStore.edit { it.clear() }

    // 2. EncryptedSharedPreferences
    withContext(Dispatchers.IO) {
        encryptedPrefs.edit { clear() }
    }

    // 3. Кастомный бинарник
    withContext(Dispatchers.IO) {
        File(context.filesDir, "custom_vkturn").delete()
    }
}
```

### Реализация — `MainViewModel.resetAllSettings()`

```kotlin
fun resetAllSettings(context: Context) {
    viewModelScope.launch {
        // Стоп прокси
        if (ProxyService.isRunning.value) {
            context.stopService(Intent(context, ProxyService::class.java))
        }

        // Сброс данных
        prefs.resetAll()

        // Сброс in-memory состояния
        activeSshConfig = null
        _sshState.value = SshConnectionState.Disconnected
        _serverState.value = ServerState.Unknown
        _serverVersion.value = null
        _serverLogs.value = null
        _sshLog.value = emptyList()
        _proxyState.value = ProxyState.Idle
        _customKernelExists.value = false
        ProxyService.logs.value = emptyList()

        // Перезапуск Activity — единственный надёжный способ сбросить NavHost
        val intent = (context as? android.app.Activity)?.intent
            ?: Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }
}
```

### Реализация — UI (InfoBottomSheet в `HomeScreen.kt`)

Добавить в `InfoBottomSheet` после секции «Логи»:

```kotlin
// ── Сброс настроек ─────────────────────────────────────────────────────
HorizontalDivider()

var showResetDialog by remember { mutableStateOf(false) }

ListItem(
    headlineContent = {
        Text("Сбросить все настройки", color = MaterialTheme.colorScheme.error)
    },
    supportingContent = { Text("Удалит SSH-данные, конфигурацию клиента и кастомное ядро") },
    trailingContent = {
        IconButton(onClick = { showResetDialog = true }) {
            Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
        }
    },
    modifier = Modifier.clickable { showResetDialog = true }
)

if (showResetDialog) {
    AlertDialog(
        onDismissRequest = { showResetDialog = false },
        title = { Text("Сбросить настройки?") },
        text = { Text("Все SSH-данные, настройки клиента и кастомный бинарник будут удалены. Приложение перезапустится.") },
        confirmButton = {
            TextButton(
                onClick = {
                    showResetDialog = false
                    HapticUtil.perform(context, HapticUtil.Pattern.ERROR)
                    viewModel.resetAllSettings(context)
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Сбросить") }
        },
        dismissButton = {
            TextButton(onClick = { showResetDialog = false }) { Text("Отмена") }
        }
    )
}
```

---

## Часть 3 — Сводная таблица всех задач

### Приоритет 1 — Критичные (блокируют корректность / безопасность)

| # | Файл | Проблема | Действие |
|---|---|---|---|
| ✅ P1-1 | `ProxyService.kt:43,131,188` | Гонка `userStopped`/`process`/`sessionKillScheduled` | `AtomicBoolean` + `AtomicReference<Process>` + synchronized блок |
| ✅ P1-2 | `ProxyService.kt:86` | `runBlocking` в фоновом потоке при чтении DataStore | Переписать `startBinaryProcess()` как suspend-функцию внутри `CoroutineScope(Dispatchers.IO)` |
| ✅ P1-3 | `SSHManager.kt` | SSH key auth в UI нефункциональна (ключ игнорируется) | Реализовать `jsch.addIdentity(...)` или убрать UI-опцию |
| ✅ P1-4 | `MainViewModel.kt:253-271` | Нет верификации SHA-256 скачанного бинарника | Добавить проверку контрольной суммы перед `chmod +x` |
| ✅ P1-5 | `AndroidManifest.xml` | Отсутствует `<property>` для `specialUse` FGS — отклонение Play | Добавить `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` |

### Приоритет 2 — Высокие (баги UX / архитектурные долги)

| # | Файл | Проблема | Действие |
|---|---|---|---|
| ✅ P2-1 | `MainViewModel.kt:393` | `delay(2500)` + эвристика для определения старта | Добавить dedicated `StateFlow<StartupResult>` в `ProxyService` |
| ✅ P2-2 | `MainViewModel.kt:162-335` | Дублирование `SimpleDateFormat` × 5, дублирование SSH-паттерна × 4 | Вынести в `timestamp()` и `runSshOperation()` |
| ✅ P2-3 | `ProxyService.kt:86,41` | `AppPreferences(this@ProxyService)` без `applicationContext` | Заменить на `applicationContext` |
| ✅ P2-4 | `MainViewModel.kt:203` | `_serverState` зависает в `Checking` при пустом IP | Добавить `_serverState.value = ServerState.Unknown` перед `return` |
| ✅ P2-5 | `ProxyService.kt:139,207,239` | `destroy()` → SIGTERM, нативный процесс может зависнуть | Заменить на `destroyForcibly()` |
| ✅ P2-6 | `MainViewModel.kt:157` | Фейковый прогресс подключения | Убрать `delay(400)`/`delay(300)`, начинать SSH сразу |
| ✅ P2-7 | `AppPreferences.kt` | DataStore не исключён из Android Backup | Добавить в `backup_rules.xml` и `data_extraction_rules.xml` |

### Приоритет 3 — Средние (производительность / качество)

| # | Файл | Проблема | Действие |
|---|---|---|---|
| P3-1 | `ServerManagementScreen.kt:286` | `Column + forEach` для 500 строк SSH-лога | Заменить на `LazyColumn` |
| P3-2 | `HomeScreen.kt:546` | `Column + forEachIndexed` для 200 строк прокси-лога | Заменить на `LazyColumn` |
| P3-3 | `ProxyService.kt:37`, `MainViewModel.kt:84` | O(n) аллокации на каждую строку лога | Использовать `ArrayDeque` с maxSize |
| P3-4 | `AppNavigation.kt:159` | `Routes.CLIENT_SETUP + "_onboarding"` — строковая конкатенация | Вынести в `Routes.CLIENT_SETUP_OB` константу |
| P3-5 | `SSHManager.kt:88` | `disconnect()` — no-op с вводящим в заблуждение API | Удалить метод или добавить `@Deprecated` + документацию |
| P3-6 | `AppPreferences.kt:41` | `encryptedPrefs` lazy init может произойти на уничтоженном context | Использовать `applicationContext` |
| P3-7 | `app/build.gradle.kts` | `security-crypto:1.1.0-alpha06` — alpha в продакшне | Понизить до стабильной `1.0.0` |
| P3-8 | `ProxyService.kt:64` | Системный drawable в нотификации | Использовать собственную иконку из `res/drawable` |
| P3-9 | `ClientSetupScreen.kt:87` | `lastSliderInt` через `remember` сбрасывается при ротации | Заменить на `rememberSaveable` |
| P3-10 | `MainViewModel.kt:84` | `appendSshLog` — O(n) на каждые несколько строк | Использовать `ArrayDeque` + `toList()` только при публикации |

### Новая функция — Сброс настроек

| # | Файл | Задача |
|---|---|---|
| F1 | `AppPreferences.kt` | Реализовать `suspend fun resetAll()` — DataStore + EncryptedSharedPreferences + custom binary |
| F2 | `MainViewModel.kt` | Реализовать `fun resetAllSettings(context: Context)` — стоп сервиса + resetAll() + сброс состояния + restart Activity |
| F3 | `HomeScreen.kt` | Добавить пункт «Сбросить настройки» в `InfoBottomSheet` с `AlertDialog` подтверждения |
| F4 | `AppPreferences.kt` | Добавить `Icons.Filled.DeleteForever` в импорты (из `material-icons-extended`) |

---

## Часть 4 — Порядок реализации (рекомендуемый)

```
Спринт 1 (критичная безопасность и корректность): ✅ ГОТОВО
  ✅ P1-3  SSH key auth через jsch.addIdentity()
  ✅ P1-4  SHA-256 верификация бинарника через checksums.txt
  ✅ P1-5  <property> для specialUse FGS
  ✅ (доп.) disconnectSsh() + кнопка «Отключить» в InfoBottomSheet
  ✅ (доп.) SshSetupScreen: нет авто-перехода при уже активном подключении

Спринт 2 (конкурентность и стабильность): ✅ ГОТОВО
  ✅ P1-1  AtomicBoolean/AtomicReference вместо @Volatile
  ✅ P1-2  startBinaryProcess → suspend fun в serviceScope
  ✅ P2-3  AppPreferences(applicationContext) в ProxyService и AppPreferences
  ✅ P2-5  destroyForcibly() везде

Спринт 3 (архитектура и UX): ✅ ГОТОВО
  ✅ P2-1  StartupResult StateFlow + withTimeoutOrNull(5s) вместо delay(2500)
  ✅ P2-2  timestamp() + runSshCommand() — убрано 5× SimpleDateFormat и 6× boilerplate
  ✅ P2-4  serverState → Unknown при пустом IP
  ✅ P2-6  Убраны delay(400)/delay(300) — SSH начинается сразу
  ✅ P2-7  DataStore исключён из backup_rules.xml и data_extraction_rules.xml

Спринт 3 (архитектура и UX):
  P2-1  Явный сигнал запуска прокси
  P2-2  Дедупликация кода (timestamp, runSshOperation)
  P2-4  Фикс зависания serverState
  P2-6  Убрать фейковый прогресс
  P2-7  Backup exclusion для DataStore

Спринт 4 (новая функция):
  F1    AppPreferences.resetAll()
  F2    MainViewModel.resetAllSettings()
  F3    UI — кнопка сброса + AlertDialog

Спринт 5 (производительность и полировка):
  P3-1  LazyColumn для SSH-лога
  P3-2  LazyColumn для прокси-лога
  P3-3  ArrayDeque для логов
  P3-4  Константа CLIENT_SETUP_OB
  P3-7  security-crypto стабильная версия
  P3-8  Собственная иконка нотификации
  Остальные P3-*
```
