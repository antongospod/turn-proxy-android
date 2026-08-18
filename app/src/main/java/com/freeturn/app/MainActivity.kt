package com.freeturn.app

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.freeturn.app.domain.share.LinkImportBus
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.navigation.AppNavigation
import com.freeturn.app.ui.theme.FreeTurnTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import com.freeturn.app.viewmodel.proxy.ProxyViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModel()
    private val proxyViewModel: ProxyViewModel by viewModel()
    private val linkImportBus: LinkImportBus by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !settingsViewModel.isInitialized.value }

        // При recreation (поворот, смена темы) интент уже обработан в первом onCreate.
        if (savedInstanceState == null) handleLinkIntent(intent)

        // POST_NOTIFICATIONS и исключение из оптимизации батареи запрашивает
        // RequestStartupPermissions одной цепочкой - дубль отсюда система отклоняла
        // мгновенно и сбивал следующий за ним диалог батареи.
        HapticUtil.perform(this, HapticUtil.Pattern.LAUNCH)
        enableEdgeToEdge()
        setContent {
            val dynamicTheme by settingsViewModel.dynamicTheme.collectAsStateWithLifecycle()
            FreeTurnTheme(dynamicColor = dynamicTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }

    // Открытие приложения - момент, когда убитую сессию можно поднять без фоновых
    // ограничений: активити на экране, право на FGS есть.
    override fun onResume() {
        super.onResume()
        proxyViewModel.onForeground(vpnConsent = VpnService.prepare(this) == null)
    }

    // Опрос метрик живёт ровно столько, сколько видно окно.
    override fun onStart() {
        super.onStart()
        proxyViewModel.setMetricsVisible(true)
    }

    override fun onStop() {
        super.onStop()
        proxyViewModel.setMetricsVisible(false)
    }

    // singleTask: freeturn://-ссылка при живой задаче приходит сюда, не в onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Иначе getIntent() после recreation вернёт исходный интент запуска.
        setIntent(intent)
        handleLinkIntent(intent)
    }

    private fun handleLinkIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.toString()?.let(linkImportBus::offer)
    }
}
