package com.freeturn.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.ui.screens.ClientSetupScreen
import com.freeturn.app.ui.screens.HomeScreen
import com.freeturn.app.ui.screens.LogsScreen
import com.freeturn.app.ui.screens.OnboardingScreen
import com.freeturn.app.ui.screens.ServerManagementScreen
import com.freeturn.app.ui.screens.SshSetupScreen
import com.freeturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

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

// Нижнее меню видно только в основном потоке, не во время онбординга
private val BOTTOM_NAV_ROUTES = setOf(Routes.HOME, Routes.LOGS, Routes.SERVER_MANAGEMENT, Routes.CLIENT_SETUP)

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()

    if (!isInitialized) {
        SplashScreen()
        return
    }

    val startDestination = remember { if (onboardingDone) Routes.HOME else Routes.ONBOARDING }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in BOTTOM_NAV_ROUTES

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                AppNavigationBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(Routes.HOME) { saveState = true; inclusive = false }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (showBottomBar) innerPadding.calculateBottomPadding() else 0.dp)
        ) {
            NavHost(
                navController = navController, 
                startDestination = startDestination,
                modifier = Modifier.statusBarsPadding() // Добавляем отступ для статус-бара сверху
            ) {

                // ── Онбординг-мастер (без нижнего меню) ──────────────────────────

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

                // ── Основной поток (с нижним меню) ───────────────────────────────

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
                                // иначе он остаётся под CLIENT_SETUP и bottom nav ломается
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
                        onNavigateToSshSetup = { navController.navigate(Routes.SSH_SETUP) },
                        onNavigateToClientSetup = { navController.navigate(Routes.CLIENT_SETUP) }
                    )
                }

                composable(Routes.LOGS) {
                    LogsScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    val context = LocalContext.current
    val scale = remember { Animatable(0.72f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        HapticUtil.perform(context, HapticUtil.Pattern.LAUNCH)
        launch {
            scale.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
        alpha.animateTo(1f, animationSpec = tween(380))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.nearby_24px),
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.splash_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.splash_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
            )
        }
    }
}

private data class NavItem(
    val route: String,
    val labelResId: Int,
    val selectedIconRes: Int,
    val unselectedIconRes: Int
)

private val navItems = listOf(
    NavItem(Routes.HOME, R.string.nav_home, R.drawable.home_24px, R.drawable.home_outlined_24px),
    NavItem(Routes.SERVER_MANAGEMENT, R.string.server, R.drawable.database_24px, R.drawable.database_outlined_24px),
    NavItem(Routes.CLIENT_SETUP, R.string.client_title, R.drawable.mobile_24px, R.drawable.mobile_outlined_24px),
    NavItem(Routes.LOGS, R.string.logs_title, R.drawable.terminal_24px, R.drawable.terminal_24px)
)

@Composable
private fun AppNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    NavigationBar {
        navItems.forEach { item ->
            val selected = currentRoute == item.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                    onNavigate(item.route)
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
}
