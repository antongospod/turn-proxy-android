package com.freeturn.app.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.freeturn.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.ui.screens.CaptchaWebViewDialog
import com.freeturn.app.ui.screens.ClientSetupScreen
import com.freeturn.app.ui.screens.HomeScreen
import com.freeturn.app.ui.screens.LogsScreen
import com.freeturn.app.ui.screens.OnboardingScreen
import com.freeturn.app.ui.screens.ServerManagementScreen
import com.freeturn.app.ui.screens.SshSetupScreen
import com.freeturn.app.viewmodel.ProxyState
import com.freeturn.app.viewmodel.MainViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val SSH_SETUP = "ssh_setup"              // из настроек/инфо-модалки
    const val SSH_SETUP_OB = "ssh_setup_ob"        // только в мастере онбординга
    const val SERVER_MANAGEMENT = "server_management"
    const val SERVER_MANAGEMENT_OB = "server_management_ob" // только в мастере онбординга
    const val CLIENT_SETUP = "client_setup"
    const val CLIENT_SETUP_OB = "client_setup_onboarding"
    const val HOME = "home"
    const val LOGS = "logs"
}

// Нижнее меню / рельс видно только в основном потоке, не во время онбординга
private val BOTTOM_NAV_ROUTES = setOf(Routes.HOME, Routes.LOGS, Routes.SERVER_MANAGEMENT, Routes.CLIENT_SETUP)

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()

    // Не строим NavHost пока DataStore не загружен — иначе startDestination
    // захватит дефолтный onboardingDone=false и всегда покажет онбординг
    if (!isInitialized) return

    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val initialOnboardingDone by viewModel.initialOnboardingDone.collectAsStateWithLifecycle()
    val initialTgSubscribeShown by viewModel.initialTgSubscribeShown.collectAsStateWithLifecycle()
    val startDestination = remember { if (initialOnboardingDone) Routes.HOME else Routes.ONBOARDING }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showNavSuite = currentRoute in BOTTOM_NAV_ROUTES

    var showTgDialog by rememberSaveable { mutableStateOf(!initialTgSubscribeShown && initialOnboardingDone) }

    val adaptiveType = NavigationSuiteScaffoldDefaults
        .calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    val layoutType = if (showNavSuite) adaptiveType else NavigationSuiteType.None

    val context = LocalContext.current

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            navItems.forEach { item ->
                val selected = currentRoute == item.route
                item(
                    selected = selected,
                    onClick = {
                        if (!selected) HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                        navController.navigate(item.route) {
                            popUpTo(Routes.HOME) { saveState = true; inclusive = false }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        Crossfade(targetState = selected, label = "nav_icon_${item.route}") { isSelected ->
                            Icon(
                                painter = painterResource(
                                    if (isSelected) item.selectedIconRes else item.unselectedIconRes
                                ),
                                contentDescription = stringResource(item.labelResId)
                            )
                        }
                    },
                    label = { Text(stringResource(item.labelResId)) }
                )
            }
        }
    ) {
        AppNavHost(
            navController = navController,
            viewModel = viewModel,
            startDestination = startDestination
        )
    }

    // Диалог капчи поверх любого экрана. Оборачиваем в key(sessionId), чтобы для
    // каждой новой капча-сессии Compose пересоздавал диалог и WebView грузил URL заново
    // (бинарник цикличит креды и для каждой выдаёт новую капчу с тем же localhost-URL).
    val captchaState = proxyState as? ProxyState.CaptchaRequired
    if (captchaState != null) {
        androidx.compose.runtime.key(captchaState.sessionId) {
            CaptchaWebViewDialog(
                captchaUrl = captchaState.url,
                onDismiss = { viewModel.dismissCaptcha() }
            )
        }
    }

    if (showTgDialog) {
        val uriHandler = LocalUriHandler.current
        TelegramSubscribeDialog(
            onSubscribe = {
                uriHandler.openUri("https://t.me/+53nh4UNiSv5lNTgy")
                viewModel.setTgSubscribeShown()
                showTgDialog = false
            },
            onDismiss = {
                viewModel.setTgSubscribeShown()
                showTgDialog = false
            }
        )
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    viewModel: MainViewModel,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Онбординг-мастер (без навигации)
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onSetupServer = { navController.navigate(Routes.SSH_SETUP_OB) },
                onSkip = {
                    viewModel.setOnboardingDone()
                    navController.navigate(Routes.HOME) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.SSH_SETUP_OB) {
            SshSetupScreen(
                viewModel = viewModel,
                onConnected = {
                    navController.navigate(Routes.SERVER_MANAGEMENT_OB) {
                        popUpTo(Routes.SSH_SETUP_OB) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SERVER_MANAGEMENT_OB) {
            ServerManagementScreen(
                viewModel = viewModel,
                onContinue = {
                    navController.navigate(Routes.CLIENT_SETUP_OB) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.CLIENT_SETUP_OB) {
            ClientSetupScreen(
                viewModel = viewModel,
                showFinishButton = true,
                onFinish = {
                    viewModel.setOnboardingDone()
                    navController.navigate(Routes.HOME) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        // Основной поток (с навигацией)
        composable(Routes.SSH_SETUP) {
            SshSetupScreen(
                viewModel = viewModel,
                onConnected = {
                    navController.navigate(Routes.SERVER_MANAGEMENT) {
                        popUpTo(Routes.SSH_SETUP) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SERVER_MANAGEMENT) {
            ServerManagementScreen(
                viewModel = viewModel,
                onContinue = {
                    navController.navigate(Routes.CLIENT_SETUP) {
                        // Убираем SERVER_MANAGEMENT из back stack,
                        // иначе он остаётся под CLIENT_SETUP и навигация ломается
                        popUpTo(Routes.HOME) { inclusive = false; saveState = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.CLIENT_SETUP) {
            ClientSetupScreen(
                viewModel = viewModel,
                showFinishButton = false
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToSshSetup = { navController.navigate(Routes.SSH_SETUP) }
            )
        }

        composable(Routes.LOGS) {
            LogsScreen(viewModel = viewModel)
        }
    }
}

private data class NavItem(
    val route: String,
    val labelResId: Int,
    val selectedIconRes: Int,
    val unselectedIconRes: Int
)

@Composable
private fun TelegramSubscribeDialog(onSubscribe: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tg_subscribe_title)) },
        text = { Text(stringResource(R.string.tg_subscribe_desc)) },
        confirmButton = {
            TextButton(
                onClick = onSubscribe,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) { Text(stringResource(R.string.tg_subscribe_btn)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tg_not_now)) }
        }
    )
}

private val navItems = listOf(
    NavItem(Routes.HOME, R.string.nav_home, R.drawable.home_24px, R.drawable.home_outlined_24px),
    NavItem(Routes.SERVER_MANAGEMENT, R.string.server, R.drawable.database_24px, R.drawable.database_outlined_24px),
    NavItem(Routes.CLIENT_SETUP, R.string.client_title, R.drawable.mobile_24px, R.drawable.mobile_outlined_24px),
    NavItem(Routes.LOGS, R.string.logs_title, R.drawable.terminal_24px, R.drawable.terminal_24px)
)
