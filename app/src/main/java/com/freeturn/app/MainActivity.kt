package com.freeturn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.freeturn.app.ui.navigation.AppNavigation
import com.freeturn.app.ui.theme.FreeTurnTheme
import com.freeturn.app.viewmodel.MainViewModel

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Удерживаем системный splash пока ViewModel не инициализируется
        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        enableEdgeToEdge()
        setContent {
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            FreeTurnTheme(dynamicColor = dynamicTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(viewModel = viewModel)
                }
            }
        }
    }
}
