@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.ui.components.EmptyState
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.domain.proxy.LogEntry
import com.freeturn.app.domain.proxy.LogLevel
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.ui.theme.extendedColorScheme
import com.freeturn.app.ui.util.copyToClipboard
import com.freeturn.app.viewmodel.proxy.ProxyViewModel

/** Вкладка логов: терминальная панель с подсветкой по уровню. */
@Composable
fun LogsScreen(proxyViewModel: ProxyViewModel) {
    val context = LocalContext.current
    val logs by proxyViewModel.logs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var autoScroll by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (!scrolling) autoScroll = listState.isAtBottom() }
    }

    // Ключ на id последней строки, а не на размер: буфер упирается в потолок и size
    // перестаёт меняться.
    LaunchedEffect(logs.lastOrNull()?.id) {
        if (autoScroll && logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            LogsActionsFab(
                hasLogs = logs.isNotEmpty(),
                onCopy = {
                    context.copyToClipboard("proxy_logs", logs.joinToString("\n") { it.text })
                    HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                },
                onClear = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    proxyViewModel.clearLogs()
                }
            )
        },
        // Вкладка живёт в NavigationSuite - нижний бар сам держит инсет.
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            if (logs.isEmpty()) {
                EmptyState(
                    iconRes = R.drawable.terminal_24px,
                    desc = stringResource(R.string.no_logs),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .widthIn(max = SettingsContentMaxWidth)
                        .fillMaxSize()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = Spacing.md)
                    ) {
                        items(logs, key = { it.id }) { entry ->
                            LogLine(entry = entry)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListState.isAtBottom(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 2
}

/**
 * Expressive FAB-меню действий над логами: по тапу раскрывает "Копировать" и "Очистить".
 * Пункты no-op при пустом логе.
 */
@Composable
private fun LogsActionsFab(
    hasLogs: Boolean,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it }
            ) {
                // ToggleFloatingActionButton не задаёт контентный цвет - тинтуем сами
                // под контейнер (primaryContainer в покое -> primary при раскрытии).
                Icon(
                    painterResource(R.drawable.more_vert_24px),
                    contentDescription = stringResource(R.string.logs_actions),
                    tint = if (expanded) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) {
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; if (hasLogs) onCopy() },
            icon = { Icon(painterResource(R.drawable.content_copy_24px), contentDescription = null) },
            text = { Text(stringResource(R.string.copy)) }
        )
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; if (hasLogs) onClear() },
            icon = { Icon(painterResource(R.drawable.delete_24px), contentDescription = null) },
            text = { Text(stringResource(R.string.clear)) }
        )
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    val level = entry.level
    val textColor = when (level) {
        LogLevel.Error   -> MaterialTheme.colorScheme.error
        LogLevel.Warning -> MaterialTheme.extendedColorScheme.warning
        LogLevel.Success -> MaterialTheme.extendedColorScheme.success
        LogLevel.Event   -> MaterialTheme.colorScheme.primary
        LogLevel.Plain   -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.Top
    ) {
        if (level != LogLevel.Plain) {
            Box(
                modifier = Modifier
                    .padding(top = Spacing.xs, end = Spacing.sm)
                    .size(5.dp)
                    .background(textColor, CircleShape)
            )
        } else {
            Spacer(Modifier.width(11.dp))
        }
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = if (level == LogLevel.Event) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = textColor
        )
    }
}
