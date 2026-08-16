@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.ui.components.SettingsBackButton
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import com.freeturn.app.ui.theme.Spacing

/** Корневой экран настроек (нижнее меню): серверы, приложение, продвинутые, о проекте. */
@Composable
fun SettingsScreen(
    onOpenServers: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scrollBehavior
            )
        },
        // Корневой экран - внутри NavigationSuite, нижний бар сам держит инсет.
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                SettingsGroup {
                    SettingsGroupItem(0, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.database_24px,
                            title = stringResource(R.string.settings_servers),
                            subtitle = stringResource(R.string.settings_servers_desc),
                            onClick = onOpenServers
                        )
                    }
                    SettingsGroupItem(1, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.mobile_24px,
                            title = stringResource(R.string.settings_app),
                            subtitle = stringResource(R.string.settings_app_desc),
                            onClick = onOpenApp
                        )
                    }
                    SettingsGroupItem(2, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.tune_24px,
                            title = stringResource(R.string.settings_advanced),
                            subtitle = stringResource(R.string.settings_advanced_desc),
                            onClick = onOpenAdvanced
                        )
                    }
                    SettingsGroupItem(3, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.info_24px,
                            title = stringResource(R.string.settings_about),
                            subtitle = stringResource(R.string.settings_about_desc),
                            onClick = onOpenAbout
                        )
                    }
                }
            }
        }
    }
}

/** "Продвинутые": переключатель "Режим отладки" ([SettingsViewModel.nerdMode]). */
@Composable
fun AdvancedScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val nerdMode by settingsViewModel.nerdMode.collectAsStateWithLifecycle()
    val restartServerOnSwitch by settingsViewModel.restartServerOnSwitch.collectAsStateWithLifecycle()
    val hotspotProxyEnabled by settingsViewModel.hotspotProxyEnabled.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_advanced)) },
                navigationIcon = { SettingsBackButton(onBack) },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                SettingsCard {
                    SettingsSwitchRow(
                        title = stringResource(R.string.nerd_mode),
                        subtitle = stringResource(R.string.nerd_mode_desc),
                        iconRes = R.drawable.terminal_24px,
                        checked = nerdMode,
                        onCheckedChange = { settingsViewModel.setNerdMode(it) }
                    )
                }

                SettingsCard {
                    SettingsSwitchRow(
                        title = stringResource(R.string.hotspot_proxy),
                        subtitle = stringResource(R.string.hotspot_proxy_desc),
                        iconRes = R.drawable.wifi_tethering_24px,
                        checked = hotspotProxyEnabled,
                        onCheckedChange = { settingsViewModel.setHotspotProxyEnabled(it) }
                    )
                }

                SettingsCard {
                    SettingsSwitchRow(
                        title = stringResource(R.string.restart_server_on_switch),
                        subtitle = stringResource(R.string.restart_server_on_switch_desc),
                        iconRes = R.drawable.restart_alt_24px,
                        checked = restartServerOnSwitch,
                        onCheckedChange = { settingsViewModel.setRestartServerOnSwitch(it) }
                    )
                }
            }
        }
    }
}
