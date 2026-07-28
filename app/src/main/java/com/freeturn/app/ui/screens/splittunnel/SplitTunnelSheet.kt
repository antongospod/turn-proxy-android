@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.splittunnel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.data.config.SplitTunnelMode
import com.freeturn.app.data.config.splitTunnelSelection
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.data.AppChoice
import com.freeturn.app.data.installedInternetApps
import com.freeturn.app.ui.components.EmptyState
import com.freeturn.app.ui.theme.Spacing

@Composable
fun SplitTunnelModal(
    mode: String,
    apps: String,
    locked: Boolean,
    onModeChange: (String) -> Unit,
    onAppsChange: (String) -> Unit,
    onDismiss: () -> Unit,
    containerColor: Color = BottomSheetDefaults.ContainerColor
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = containerColor,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        SplitTunnelSheetContent(
            mode = mode,
            apps = apps,
            locked = locked,
            onModeChange = onModeChange,
            onAppsChange = onAppsChange
        )
    }
}

@Composable
fun SplitTunnelSheetContent(
    mode: String,
    apps: String,
    locked: Boolean,
    onModeChange: (String) -> Unit,
    onAppsChange: (String) -> Unit
) {
    val context = LocalContext.current
    val splitOn = mode != SplitTunnelMode.ALL
    val controlsEnabled = splitOn && !locked
    // Последний выбранный "рабочий" режим, чтобы свитч вкл возвращал его, а не дефолт.
    var modeChoice by remember {
        mutableStateOf(if (mode != SplitTunnelMode.ALL) mode else SplitTunnelMode.EXCLUDE)
    }
    var query by remember { mutableStateOf("") }
    // Пустой exclude-список показывает рос-сервисы отмеченными (тот же дефолт, что и в WG).
    val selected = remember(apps, modeChoice) { splitTunnelSelection(modeChoice, apps) }
    val installed by produceState<List<AppChoice>?>(initialValue = null, splitOn) {
        value = if (splitOn) context.installedInternetApps() else null
    }

    // Высота контента стабильна (список фиксированной высоты), поэтому sheet
    // открывается за одну анимацию и не "дёргается" при загрузке/поиске. Лист
    // оставляем content-sized (без fillMaxSize) - так нет оверштута до пика.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = Spacing.sm, bottom = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.split_tunnel_title),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Switch(
                checked = splitOn,
                enabled = !locked,
                onCheckedChange = { on ->
                    HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                    onModeChange(if (on) modeChoice else SplitTunnelMode.ALL)
                }
            )
        }

        if (locked) {
            LockedBanner(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HorizontalPadding)
            )
        }

        ModeDropdown(
            mode = modeChoice,
            enabled = controlsEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HorizontalPadding),
            onSelect = { value ->
                modeChoice = value
                onModeChange(value)
            }
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            enabled = controlsEnabled,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HorizontalPadding),
            label = { Text(stringResource(R.string.split_tunnel_search)) },
            leadingIcon = {
                Icon(painterResource(R.drawable.search_24px), null)
            }
        )

        if (splitOn) {
            AppList(
                modifier = Modifier
                    .height(ListHeight)
                    .fillMaxWidth(),
                installed = installed,
                selected = selected,
                query = query,
                enabled = controlsEnabled,
                onToggle = { pkg ->
                    HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                    val next = if (pkg in selected) selected - pkg else selected + pkg
                    onAppsChange(next.sorted().joinToString("\n"))
                }
            )
        } else {
            EmptyState(
                iconRes = R.drawable.apps_24px,
                desc = stringResource(R.string.split_tunnel_off_hint),
                modifier = Modifier
                    .height(ListHeight)
                    .fillMaxWidth()
            )
        }
    }
}

private val HorizontalPadding = 16.dp

private val ListHeight = 360.dp

@Composable
private fun LockedBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(R.drawable.info_24px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                stringResource(R.string.split_tunnel_locked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
