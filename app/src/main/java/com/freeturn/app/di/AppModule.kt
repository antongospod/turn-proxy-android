package com.freeturn.app.di

import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.backup.BackupManager
import com.freeturn.app.domain.update.AppUpdater
import com.freeturn.app.domain.share.LinkImportBus
import com.freeturn.app.domain.proxy.ProxyEngine
import com.freeturn.app.domain.proxy.ProxyOrchestrator
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import com.freeturn.app.service.AndroidProxyServiceLauncher
import com.freeturn.app.domain.ssh.SSHManager
import com.freeturn.app.domain.server.ServerSetupRepository
import com.freeturn.app.domain.share.ShareRepository
import com.freeturn.app.domain.ssh.SshRepository
import com.freeturn.app.viewmodel.share.ImportViewModel
import com.freeturn.app.viewmodel.proxy.ProxyViewModel
import com.freeturn.app.viewmodel.server.ServerSetupViewModel
import com.freeturn.app.viewmodel.server.ServerViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import com.freeturn.app.viewmodel.share.ShareViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single { AppPreferences(androidContext()) }
    single<ProxyServiceLauncher> { AndroidProxyServiceLauncher(androidContext(), get()) }
    // Ядро одно на процесс: сессия переживает пересоздание сервиса.
    // noBackupFilesDir: состояние ядра приватное и не должно уезжать в облачный бэкап.
    single { ProxyEngine(androidContext().noBackupFilesDir.absolutePath) }
    // factory: каждому потребителю свой SSHManager - lastSeenFingerprint (TOFU) не должен
    // делиться между живой сессией и мастером/шарингом.
    factory { SSHManager() }
    single { SshRepository(androidContext(), get()) }
    single { AppUpdater(androidContext()) }
    single { BackupManager(get()) }
    single { ProxyOrchestrator(get(), get(), get()) }
    // factory: своя SSH-сессия на каждый прогон мастера, живой SshRepository не трогаем.
    factory { ServerSetupRepository(androidContext(), get()) }
    // factory по той же причине: SSH-операции шаринга не делят сессию с активным сервером.
    factory { ShareRepository(androidContext(), get()) }
    single { LinkImportBus() }

    viewModelOf(::ProxyViewModel)
    viewModelOf(::ServerViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::ServerSetupViewModel)
    viewModelOf(::ShareViewModel)
    viewModelOf(::ImportViewModel)
}
