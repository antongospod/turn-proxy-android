package com.freeturn.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.freeturn.app.R
import com.freeturn.app.domain.ProxyState
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.screens.captcha.CaptchaWebViewDialog
import com.freeturn.app.ui.screens.share.ImportSheet
import com.freeturn.app.ui.theme.LocalReducedMotion
import com.freeturn.app.viewmodel.proxy.ProxyViewModel
import com.freeturn.app.viewmodel.server.ServerViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import org.koin.androidx.compose.koinViewModel

private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal const val NAV_SLIDE_MS = 300
private const val NAV_FADE_IN_MS = 180
private const val NAV_FADE_OUT_MS = 120

@Composable
fun AppNavigation(
    settingsViewModel: SettingsViewModel = koinViewModel(),
    proxyViewModel: ProxyViewModel = koinViewModel(),
    serverViewModel: ServerViewModel = koinViewModel()
) {
    val isInitialized by settingsViewModel.isInitialized.collectAsStateWithLifecycle()

    // Не строим UI пока DataStore не загружен - иначе showTgDialog захватит
    // дефолтный tgSubscribeShown=false и диалог мигнёт у тех, кто его уже закрыл.
    if (!isInitialized) return

    val proxyState by proxyViewModel.proxyState.collectAsStateWithLifecycle()
    val initialTgSubscribeShown by settingsViewModel.initialTgSubscribeShown.collectAsStateWithLifecycle()
    val initialSuppressTgPrompt by settingsViewModel.initialSuppressTgPrompt.collectAsStateWithLifecycle()
    val nerdMode by settingsViewModel.nerdMode.collectAsStateWithLifecycle()
    val clientConfig by settingsViewModel.clientConfig.collectAsStateWithLifecycle()
    val logsTabVisible = nerdMode && clientConfig.logsEnabled
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    LaunchedEffect(logsTabVisible) {
        if (!logsTabVisible && destination?.hierarchy?.any { it.hasRoute(LogsGraph::class) } == true) {
            navController.navigateToTab(HomeGraph)
        }
    }

    // Смена активного профиля делает сохранённый стек вкладки "Настройки" устаревшим
    // (там мог остаться хаб другого сервера) - сбрасываем его к корню. Если стек
    // настроек сейчас активен (не сохранён), clearBackStack - no-op.
    LaunchedEffect(navController) {
        settingsViewModel.serversSnapshot
            .map { it.activeId }
            .distinctUntilChanged()
            .drop(1) // первая эмиссия - текущее значение, не смена
            .collect { navController.clearBackStack<SettingsGraph>() }
    }

    var showTgDialog by rememberSaveable {
        mutableStateOf(!initialTgSubscribeShown && !initialSuppressTgPrompt)
    }

    val suiteType = NavigationSuiteScaffoldDefaults
        .navigationSuiteType(currentWindowAdaptiveInfo())

    val context = LocalContext.current

    NavigationSuiteScaffold(
        navigationSuiteType = suiteType,
        navigationItems = {
            val items = remember(logsTabVisible) {
                if (logsTabVisible) navItems.toMutableList().apply { add(1, logsNavItem) }
                else navItems
            }
            items.forEach { item ->
                val selected = destination?.hierarchy?.any { it.hasRoute(item.graphRoute::class) } == true
                NavigationSuiteItem(
                    selected = selected,
                    onClick = {
                        if (selected) {
                            navController.popBackStack(item.startRoute, inclusive = false)
                        } else {
                            HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                            navController.navigateToTab(item.graphRoute)
                        }
                    },
                    icon = {
                        Crossfade(targetState = selected, label = "nav_icon") { isSelected ->
                            Icon(
                                painter = painterResource(
                                    if (isSelected) item.selectedIconRes else item.unselectedIconRes
                                ),
                                contentDescription = stringResource(item.labelResId)
                            )
                        }
                    },
                    label = null
                )
            }
        }
    ) {
        AppNavHost(
            navController = navController,
            settingsViewModel = settingsViewModel,
            proxyViewModel = proxyViewModel,
            serverViewModel = serverViewModel
        )
    }

    ImportSheet(
        onImported = { navController.navigateToTab(HomeGraph) }
    )

    // Диалог капчи поверх любого экрана. Оборачиваем в key(sessionId), чтобы для
    // каждой новой капча-сессии Compose пересоздавал диалог и WebView грузил URL заново
    // (бинарник цикличит креды и для каждой выдаёт новую капчу с тем же localhost-URL).
    val captchaState = proxyState as? ProxyState.CaptchaRequired
    if (captchaState != null) {
        androidx.compose.runtime.key(captchaState.sessionId) {
            CaptchaWebViewDialog(
                captchaUrl = captchaState.url,
                onDismiss = { proxyViewModel.dismissCaptcha() }
            )
        }
    }

    if (showTgDialog) {
        val uriHandler = LocalUriHandler.current
        TelegramSubscribeDialog(
            onSubscribe = {
                uriHandler.openUri("https://t.me/+53nh4UNiSv5lNTgy")
                settingsViewModel.setTgSubscribeShown()
                showTgDialog = false
            },
            onDismiss = {
                settingsViewModel.setTgSubscribeShown()
                showTgDialog = false
            }
        )
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
    proxyViewModel: ProxyViewModel,
    serverViewModel: ServerViewModel
) {
    // Системная настройка reduced motion отключает shared-axis переходы.
    val reducedMotion = LocalReducedMotion.current
    NavHost(
        navController = navController,
        startDestination = HomeGraph,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        enterTransition = {
            if (reducedMotion) EnterTransition.None
            else {
                val back = !isForwardNav()
                fadeIn(tween(NAV_FADE_IN_MS, easing = EmphasizedEasing)) +
                    slideInHorizontally(tween(NAV_SLIDE_MS, easing = EmphasizedEasing)) {
                        if (back) -it / 5 else it / 5
                    }
            }
        },
        exitTransition = {
            if (reducedMotion) ExitTransition.None
            else {
                val back = !isForwardNav()
                fadeOut(tween(NAV_FADE_OUT_MS, easing = EmphasizedEasing)) +
                    slideOutHorizontally(tween(NAV_SLIDE_MS, easing = EmphasizedEasing)) {
                        if (back) it / 12 else -it / 12
                    }
            }
        },
        popEnterTransition = {
            if (reducedMotion) EnterTransition.None
            else fadeIn(tween(NAV_FADE_IN_MS, easing = EmphasizedEasing)) +
                slideInHorizontally(tween(NAV_SLIDE_MS, easing = EmphasizedEasing)) { -it / 5 }
        },
        popExitTransition = {
            if (reducedMotion) ExitTransition.None
            else fadeOut(tween(NAV_FADE_OUT_MS, easing = EmphasizedEasing)) +
                slideOutHorizontally(tween(NAV_SLIDE_MS, easing = EmphasizedEasing)) { it / 12 }
        }
    ) {
        homeGraph(navController, settingsViewModel, proxyViewModel)
        logsGraph(proxyViewModel)
        shareGraph(navController)
        addGraph(navController, settingsViewModel)
        settingsGraph(navController, settingsViewModel, proxyViewModel, serverViewModel)
    }
}

private val tabOrder = listOf(
    HomeGraph::class, LogsGraph::class, ShareGraph::class, SettingsGraph::class, AddGraph::class
)

private fun NavDestination.tabRank(): Int? =
    hierarchy.firstNotNullOfOrNull { d ->
        tabOrder.indexOfFirst { d.hasRoute(it) }.takeIf { it >= 0 }
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isForwardNav(): Boolean {
    val from = initialState.destination.tabRank()
    val to = targetState.destination.tabRank()
    return if (from != null && to != null && from != to) to > from else true
}

private data class NavItem(
    val graphRoute: Any,   // граф-вкладка (цель навигации, проверка selected)
    val startRoute: Any,   // корневой экран вкладки (для re-tap в корень)
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

private val logsNavItem =
    NavItem(LogsGraph, Logs, R.string.nav_logs, R.drawable.terminal_24px, R.drawable.terminal_24px)

private val navItems = listOf(
    NavItem(HomeGraph, Home, R.string.nav_home, R.drawable.home_24px, R.drawable.home_outlined_24px),
    NavItem(ShareGraph, Share, R.string.nav_share, R.drawable.share_24px, R.drawable.share_outlined_24px),
    NavItem(SettingsGraph, Settings, R.string.nav_settings, R.drawable.settings_24px, R.drawable.settings_outlined_24px),
    NavItem(AddGraph, AddServer, R.string.nav_add, R.drawable.add_24px, R.drawable.add_24px)
)
