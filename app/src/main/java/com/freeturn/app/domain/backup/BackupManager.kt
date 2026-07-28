package com.freeturn.app.domain.backup

import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.backup.BackupCrypto
import com.freeturn.app.data.backup.BackupData
import com.freeturn.app.data.backup.SettingsBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Экспорт/импорт настроек в зашифрованный паролем файл.
 * Крипто (PBKDF2 210k) тяжёлая - гоним на Dispatchers.Default.
 */
class BackupManager(private val prefs: AppPreferences) {

    /** Сериализует и шифрует все настройки. Возвращает байты файла. */
    suspend fun export(password: String): ByteArray = withContext(Dispatchers.Default) {
        val payload = SettingsBackup.encode(prefs.exportData())
        BackupCrypto.encrypt(payload.toByteArray(Charsets.UTF_8), password)
    }

    /**
     * Расшифровка отделена от применения: [restore] затирает профиль, и до неё вызывающий
     * должен погасить рантайм. Бросает [BackupCrypto.BadPasswordException] /
     * [BackupCrypto.FormatException].
     */
    suspend fun decode(bytes: ByteArray, password: String): BackupData =
        withContext(Dispatchers.Default) {
            val payload = BackupCrypto.decrypt(bytes, password)
            SettingsBackup.decode(String(payload, Charsets.UTF_8))
        }

    /** Заменяет профиль содержимым бэкапа. Возвращает число восстановленных серверов. */
    suspend fun restore(data: BackupData): Int = prefs.restoreBackup(data)
}
