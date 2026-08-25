@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.share

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.components.EmptyState
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.theme.LocalReducedMotion
import com.freeturn.app.viewmodel.share.ShareViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import com.freeturn.app.ui.theme.Spacing

private const val TAB_CONNECTION = 0
private const val TAB_USERS = 1

/** Вкладка "Поделиться": выдача доступа ("Соединение") и управление ("Пользователи"). */
@Composable
fun ShareScreen(
    onAddServer: () -> Unit,
    // true, когда enter-переход завершён (загрузка не тормозит анимацию).
    screenSettled: Boolean,
    viewModel: ShareViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val reducedMotion = LocalReducedMotion.current
    var tab by rememberSaveable { mutableIntStateOf(TAB_CONNECTION) }

    // Ручной сервер без SSH не имеет списка пользователей - держим вкладку "Соединение".
    LaunchedEffect(state.canManageUsers) {
        if (!state.canManageUsers && tab != TAB_CONNECTION) tab = TAB_CONNECTION
    }

    // Тихий рефреш (сразу), индикатор - после enter-перехода.
    LaunchedEffect(Unit) { viewModel.revalidateInfo() }
    LaunchedEffect(screenSettled, state.selectedServerId) {
        if (screenSettled) viewModel.ensureInfoLoaded()
    }

    // Список пользователей тянем лениво - только когда открыта их вкладка.
    LaunchedEffect(tab, state.selectedServerId) {
        if (tab == TAB_USERS) viewModel.refreshPeers()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.share_title)) },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            // FAB "Создать" (только на вкладке "Соединение", если готово или в процессе).
            AnimatedVisibility(
                visible = tab == TAB_CONNECTION && (state.canCreate || state.creating),
                enter = if (reducedMotion) EnterTransition.None else scaleIn() + fadeIn(),
                exit = if (reducedMotion) ExitTransition.None else scaleOut() + fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        viewModel.createShare()
                    },
                    icon = {
                        if (state.creating) {
                            LoadingIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(painterResource(R.drawable.share_24px), contentDescription = null)
                        }
                    },
                    text = { Text(stringResource(R.string.share_create_button)) }
                )
            }
        },
        // Экран всегда внутри NavigationSuite - нижний бар сам держит навбар-инсет.
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        if (state.servers.isEmpty()) {
            // Делиться нечем: нет ни одного сервера с SSH-доступом или адресом.
            EmptyState(
                iconRes = R.drawable.share_outlined_24px,
                title = stringResource(R.string.share_empty_title),
                desc = stringResource(R.string.share_empty_desc),
                actionLabel = stringResource(R.string.share_empty_add),
                onAction = onAddServer,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
            ) {
                // Переключатель "Соединение / Пользователи" - только для SSH-серверов:
                // у ручных нет серверного списка выданных доступов.
                if (state.canManageUsers) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    ) {
                        SegmentedButton(
                            selected = tab == TAB_CONNECTION,
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                tab = TAB_CONNECTION
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text(stringResource(R.string.share_tab_connection)) }
                        SegmentedButton(
                            selected = tab == TAB_USERS,
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                tab = TAB_USERS
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text(stringResource(R.string.share_tab_users)) }
                    }
                }
                AnimatedContent(
                    targetState = tab,
                    // Shared-axis X: вперёд (к "Пользователям") - въезд справа, назад - слева.
                    transitionSpec = {
                        val forward = targetState > initialState
                        val w = { full: Int -> if (forward) full / 5 else -full / 5 }
                        (slideInHorizontally(tween(260)) { w(it) } + fadeIn(tween(260)))
                            .togetherWith(
                                slideOutHorizontally(tween(200)) { -w(it) } + fadeOut(tween(120))
                            )
                    },
                    label = "share_tab"
                ) { current ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.lg, vertical = Spacing.lg)
                    ) {
                        when (current) {
                            TAB_CONNECTION -> ShareConnectionTab(
                                state = state,
                                onSelectServer = viewModel::selectServer,
                                onUserNameChange = viewModel::setUserName,
                                onClientIdChange = viewModel::setManualClientId,
                                onSetMode = viewModel::setShareMode,
                                onSetShareVkLink = viewModel::setShareVkLink,
                                onVkLinkChange = viewModel::setVkLinkToShare,
                                onRetryInfo = viewModel::retryInfo
                            )
                            TAB_USERS -> ShareUsersTab(
                                state = state,
                                onSelectServer = viewModel::selectServer,
                                onRefresh = { viewModel.refreshPeers(force = true) },
                                onReshare = viewModel::resharePeer,
                                onRevoke = viewModel::askRevoke,
                                onReshareClient = viewModel::reshareClient,
                                onRevokeClient = viewModel::askRevokeClient
                            )
                        }
                    }
                }
            }
        }
    }

    state.result?.let { result ->
        ShareResultSheet(
            result = result,
            shareInfo = state.shareInfo,
            onDismiss = viewModel::dismissResult
        )
    }
    state.revokeTarget?.let { target ->
        RevokeDialog(
            userName = target.name,
            revoking = state.revoking,
            onConfirm = viewModel::confirmRevoke,
            onDismiss = viewModel::dismissRevoke
        )
    }
}

