@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.ui.theme.StatusGreen
import com.freeturn.app.ui.theme.StatusOrange
import com.freeturn.app.viewmodel.MainViewModel

@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                actions = {
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("proxy_logs", logs.joinToString("\n")))
                            HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                        },
                        enabled = logs.isNotEmpty()
                    ) {
                        Icon(
                            painterResource(R.drawable.content_copy_24px),
                            contentDescription = stringResource(R.string.copy)
                        )
                    }
                    IconButton(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.clearLogs()
                        },
                        enabled = logs.isNotEmpty()
                    ) {
                        Icon(
                            painterResource(R.drawable.delete_24px),
                            contentDescription = stringResource(R.string.clear)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.no_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                itemsIndexed(logs, key = { index, _ -> index }) { _, line ->
                    LogLine(line = line)
                }
            }
        }
    }
}

@Composable
private fun LogLine(line: String) {
    val lower = line.lowercase()
    val isHeader = line.startsWith("===")
    val isError = lower.contains("ошибка") || lower.contains("error") ||
                  lower.contains("критическая") || lower.contains("failed") ||
                  lower.contains("fatal") || lower.contains("panic")
    val isWarning = lower.contains("watchdog") || lower.contains("перезапуск") ||
                    lower.contains("quota") || lower.contains("warn") ||
                    lower.contains(">>>")
    val isSuccess = lower.contains("запущен") || lower.contains("подключен") ||
                    lower.contains("success") || lower.contains("started") ||
                    lower.contains("ok")

    val textColor = when {
        isError   -> MaterialTheme.colorScheme.error
        isWarning -> StatusOrange
        isSuccess -> StatusGreen
        isHeader  -> MaterialTheme.colorScheme.primary
        else      -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (isHeader || isError || isWarning || isSuccess) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp, end = 6.dp)
                    .size(5.dp)
                    .background(textColor, CircleShape)
            )
        } else {
            Spacer(Modifier.width(11.dp))
        }
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = textColor
        )
    }
}
