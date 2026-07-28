package com.freeturn.app.ui.screens.clientsetup

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.freeturn.app.R
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsRowDivider
import com.freeturn.app.ui.components.SettingsSliderRow
import kotlin.math.roundToInt

/** Производительность: потоки и потоки-на-аккаунт. */
@Composable
internal fun PerformanceCard(
    threads: Float,
    onThreads: (Float) -> Unit,
    streamsPerCred: Float,
    onStreamsPerCred: (Float) -> Unit,
    onTick: () -> Unit
) {
    SectionLabel(stringResource(R.string.client_section_performance))
    SettingsCard {
        SettingsFieldSlot {
            SettingsSliderRow(
                valueLabel = stringResource(R.string.threads_format, threads.roundToInt()),
                hint = stringResource(R.string.threads_recommendation),
                value = threads,
                valueRange = 1f..128f,
                onValueChange = onThreads,
                onTick = onTick
            )
        }
        SettingsRowDivider()
        SettingsFieldSlot {
            SettingsSliderRow(
                valueLabel = stringResource(R.string.streams_per_cred_format, streamsPerCred.roundToInt()),
                hint = stringResource(R.string.streams_per_cred_recommendation),
                value = streamsPerCred,
                valueRange = 1f..50f,
                onValueChange = onStreamsPerCred,
                onTick = onTick
            )
        }
    }
}
