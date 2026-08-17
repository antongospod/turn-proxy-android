package com.freeturn.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !settingsViewModel.isInitialized.value }

        // При recreation (поворот, смена темы) интент уже обработан в первом onCreate.
        if (savedInstanceState == null) handleLinkIntent(intent)

        // Запрашиваем POST_NOTIFICATIONS (Android 13+) для показа нотификации сервиса.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

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
