package com.vkturn.proxy.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vkturn.proxy.ui.screens.ClientSetupScreen
import com.vkturn.proxy.ui.screens.HomeScreen
import com.vkturn.proxy.ui.screens.OnboardingScreen
import com.vkturn.proxy.ui.screens.ServerManagementScreen
import com.vkturn.proxy.ui.screens.SshSetupScreen
import com.vkturn.proxy.viewmodel.MainViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val SSH_SETUP = "ssh_setup"
    const val SERVER_MANAGEMENT = "server_management"
    const val CLIENT_SETUP = "client_setup"
    const val HOME = "home"
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()

    if (!isInitialized) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val navController = rememberNavController()
    val startDestination = if (onboardingDone) Routes.HOME else Routes.ONBOARDING

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onSetupServer = { navController.navigate(Routes.SSH_SETUP) },
                onSkip = {
                    viewModel.setOnboardingDone()
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.SSH_SETUP) {
            SshSetupScreen(
                viewModel = viewModel,
                onConnected = { navController.navigate(Routes.SERVER_MANAGEMENT) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SERVER_MANAGEMENT) {
            ServerManagementScreen(
                viewModel = viewModel,
                onContinue = { navController.navigate(Routes.CLIENT_SETUP) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.CLIENT_SETUP) {
            ClientSetupScreen(
                viewModel = viewModel,
                onFinish = {
                    viewModel.setOnboardingDone()
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToSshSetup = { navController.navigate(Routes.SSH_SETUP) },
                onNavigateToClientSetup = { navController.navigate(Routes.CLIENT_SETUP) }
            )
        }
    }
}
