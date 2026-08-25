package com.freeturn.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.freeturn.app.R
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.theme.Spacing

/**
 * Выбор проброса UDP/TCP. [label] обязателен - без него сегменты озвучиваются как "1 из 2"
 * без предмета выбора; [tcpDisabledReason] уходит и под группу, и в stateDescription.
 */
@Composable
fun UdpTcpSegmented(
    tcp: Boolean,
    onTcp: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    tcpDisabledReason: String? = null
) {
    val context = LocalContext.current
    fun pick(value: Boolean) {
        if (value == tcp) return
        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
        onTcp(value)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SettingsControlLabel(label)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !tcp,
                onClick = { pick(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text(stringResource(R.string.udp)) }
            SegmentedButton(
                selected = tcp,
                onClick = { pick(true) },
                enabled = tcpDisabledReason == null,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = if (tcpDisabledReason == null) Modifier
                else Modifier.semantics { stateDescription = tcpDisabledReason }
            ) { Text(stringResource(R.string.tcp)) }
        }
        if (tcpDisabledReason != null) {
            Text(
                tcpDisabledReason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
