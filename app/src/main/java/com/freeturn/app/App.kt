package com.freeturn.app

import android.app.Application
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.di.appModule
import com.freeturn.app.domain.proxy.LogLevel
import com.freeturn.app.domain.proxy.ProxyEngine
import com.freeturn.app.domain.proxy.ProxyStore
import com.freeturn.app.service.ProxyWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class App : Application() {

    private val appPreferences: AppPreferences by inject()
    private val engine: ProxyEngine by inject()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // ed25519/curve25519 работает через Bouncy Castle в classpath. jsch 2.x подхватывает его сам.
        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(appModule)
        }
        // Строка в середине лога сессии = процесс убивали и подняли заново; без неё
        // sticky-рестарт неотличим от обычной работы.
        ProxyStore.log("Процесс запущен")
        warmUpCore()
        observeWidgetState()
    }

    // Первое обращение к ядру грузит нативную библиотеку и поднимает Go-runtime -
    // без прогрева эта задержка достаётся первому нажатию "Запустить".
    private fun warmUpCore() {
        scope.launch(Dispatchers.IO) {
            runCatching { engine.version }
                // Обычно это провал загрузки нативной библиотеки - запуск всё равно
                // упадёт, но уже без внятной причины в логе.
                .onFailure { ProxyStore.log("Ядро не загрузилось: ${it.message}", LogLevel.Error) }
        }
    }

    // Перерисовывает виджет при смене статуса прокси или активного сервера
    // (RemoteViews не реактивны - их надо толкать вручную).
    private fun observeWidgetState() {
        combine(
            ProxyStore.status,
            appPreferences.serversSnapshot
        ) { status, snap ->
            listOf(status.busy, status.phase, status.active, status.total, snap.active?.name)
        }
            .distinctUntilChanged()
            .onEach { ProxyWidgetProvider.refresh(this) }
            .launchIn(scope)
    }
}
