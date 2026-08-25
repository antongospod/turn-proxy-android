@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.freeturn.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.ui.theme.LocalReducedMotion

/** Запас под FAB в конце скролла: 56dp кнопки + 16dp отступа Scaffold + воздух. */
val FabClearance = 88.dp

/**
 * FAB "Применить" для экранов с apply-моделью. Появляется, как только есть правки
 * ([visible]), но при [blockedReason] остаётся на месте в disabled-виде: исчезнувшая
 * кнопка не объясняет, почему применить нельзя.
 */
@Composable
fun ApplyFab(visible: Boolean, blockedReason: String? = null, onApply: () -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    val enabled = blockedReason == null
    val container = if (enabled) FloatingActionButtonDefaults.containerColor
    else MaterialTheme.colorScheme.surfaceContainerHighest
    val onContainer = if (enabled) contentColorFor(container)
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) EnterTransition.None else {
            scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec<Float>()) +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec<Float>())
        },
        exit = if (reducedMotion) ExitTransition.None else {
            scaleOut(MaterialTheme.motionScheme.fastSpatialSpec<Float>()) +
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec<Float>())
        }
    ) {
        ExtendedFloatingActionButton(
            onClick = { if (enabled) onApply() },
            icon = { Icon(painterResource(R.drawable.check_circle_24px), contentDescription = null) },
            text = { Text(stringResource(R.string.server_apply)) },
            containerColor = container,
            contentColor = onContainer,
            // Тень у неактивной кнопки читалась бы как "нажми меня".
            elevation = if (enabled) FloatingActionButtonDefaults.elevation()
            else FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            modifier = blockedReason?.let { r -> Modifier.semantics { stateDescription = r } } ?: Modifier
        )
    }
}
