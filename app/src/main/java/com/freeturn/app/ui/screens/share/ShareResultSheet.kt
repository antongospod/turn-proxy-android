@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.share

import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.share.ShareInfo
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.components.ProtocolPills
import com.freeturn.app.ui.components.QrCode
import com.freeturn.app.ui.theme.LocalReducedMotion
import com.freeturn.app.ui.util.copyToClipboard
import com.freeturn.app.viewmodel.share.ShareResult
import com.freeturn.app.ui.theme.Spacing

/**
 * Длиннее QR не рисуем (нечитаемые мелкие модули) - остаются share sheet и
 * копирование. Типовая ссылка с WG-conf ~700 символов, запас двукратный.
 */
private const val QR_MAX_CHARS = 1200

/** Result-sheet после создания/повторной выдачи доступа: QR + share + копировать. */
@Composable
fun ShareResultSheet(result: ShareResult, shareInfo: ShareInfo?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val reducedMotion = LocalReducedMotion.current
    // Лист сразу на полную высоту (без peek-промежутка) - QR крупный, кнопки внизу.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xxl)
                .navigationBarsPadding()
                .padding(bottom = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                stringResource(R.string.share_result_title, result.userName),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )

            // Протокол - по факту выданного доступа (result.isWg), обфускация - из share-info.
            val obfOn = shareInfo?.let {
                it.obfProfile.isNotEmpty() && it.obfProfile != ObfProfile.NONE
            } ?: false
            ProtocolPills(wg = result.isWg, obfOn = obfOn)

            Text(
                stringResource(R.string.share_result_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // QR самосайзится: квадрат до 320dp по ширине листа.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm),
                contentAlignment = Alignment.Center
            ) {
                if (result.link.length <= QR_MAX_CHARS) {
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true }
                    val visible = shown || reducedMotion
                    val scale by animateFloatAsState(
                        targetValue = if (visible) 1f else 0.85f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "qr_scale"
                    )
                    val alpha by animateFloatAsState(
                        targetValue = if (visible) 1f else 0f,
                        label = "qr_alpha"
                    )
                    QrCode(
                        content = result.link,
                        contentDescription = stringResource(R.string.share_result_qr),
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                FilledTonalIconButton(
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        // Ссылка несёт WG PrivateKey и obf-ключ - прячем из превью буфера.
                        context.copyToClipboard("freeturn link", result.link, sensitive = true)
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.content_copy_24px),
                        contentDescription = stringResource(R.string.share_result_copy)
                    )
                }
                FilledIconButton(
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, result.link)
                        }
                        context.startActivity(
                            Intent.createChooser(send, null)
                        )
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.share_24px),
                        contentDescription = stringResource(R.string.share_result_share)
                    )
                }
            }
        }
    }
}

