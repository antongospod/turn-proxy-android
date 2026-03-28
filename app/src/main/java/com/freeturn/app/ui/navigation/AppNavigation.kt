package com.freeturn.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.ui.screens.ClientSetupScreen
import com.freeturn.app.ui.screens.HomeScreen
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
    const val HOME = "home"
}

// Нижнее меню видно только в основном потоке, не во время онбординга
private val BOTTOM_NAV_ROUTES = setOf(Routes.HOME, Routes.SERVER_MANAGEMENT, Routes.CLIENT_SETUP)

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
                FloatingNavBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(Routes.HOME) { saveState = true; inclusive = false }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0) // Мы обрабатываем отступы вручную или через контент
    ) { innerPadding ->
        // innerPadding содержит отступы для BottomBar. 
        // Если BottomBar "парящий", мы можем игнорировать или использовать часть отступа.
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
                            navController.navigate(Routes.HOME)
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
                            navController.navigate(Routes.CLIENT_SETUP + "_onboarding") {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(Routes.CLIENT_SETUP + "_onboarding") {
                    ClientSetupScreen(
                        viewModel = viewModel,
                        showFinishButton = true,
                        onFinish = {
                            viewModel.setOnboardingDone()
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
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
            .background(Color(0xFF00416A)),
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
                    .background(Color.White.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = Color.White
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "FreeTurn",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "VK TURN Proxy",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun FloatingNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingNavItem(
                route = Routes.HOME,
                icon = Icons.Filled.Home,
                label = "Главная",
                currentRoute = currentRoute,
                onNavigate = onNavigate
            )
            FloatingNavItem(
                route = Routes.SERVER_MANAGEMENT,
                icon = Icons.Filled.Storage,
                label = "Сервер",
                currentRoute = currentRoute,
                onNavigate = onNavigate
            )
            FloatingNavItem(
                route = Routes.CLIENT_SETUP,
                icon = Icons.Filled.PhoneAndroid,
                label = "Клиент",
                currentRoute = currentRoute,
                onNavigate = onNavigate
            )
        }
    }
}

@Composable
private fun FloatingNavItem(
    route: String,
    icon: ImageVector,
    label: String,
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    val selected = currentRoute == route
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable {
                if (!selected) HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                onNavigate(route)
            }
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent,
            modifier = Modifier
                .width(56.dp)
                .height(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(20.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}
