@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.freeturn.app.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.freeturn.app.R
import com.freeturn.app.ui.theme.LocalReducedMotion
import com.freeturn.app.ui.theme.extendedColorScheme
import com.freeturn.app.domain.ProxyState
import com.freeturn.app.ui.theme.Spacing
import kotlin.math.ceil

/** Герой главного экрана: кнопка-тоггл, строка статуса и пилюля счётчика/uptime. */
@Composable
internal fun ConnectionHero(
    state: ProxyState,
    uptimeText: String?,
    tunnelActive: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val kind = state.heroKind()
    val reducedMotion = LocalReducedMotion.current

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeroToggleButton(
            kind = kind,
            tunnelActive = tunnelActive,
            reducedMotion = reducedMotion,
            onClick = onToggle
        )

        Spacer(Modifier.height(20.dp))

        StatusLabel(state = state, tunnelActive = tunnelActive, reducedMotion = reducedMotion)

        Spacer(Modifier.height(10.dp))

        StatsPill(state = state, kind = kind, uptimeText = uptimeText)
    }
}

// CaptchaRequired относится к Busy (прокси под капчей работает, текст в строке статуса).
private enum class HeroKind { Idle, Busy, Running, Error }

private fun ProxyState.heroKind(): HeroKind = when (this) {
    is ProxyState.Running -> HeroKind.Running
    is ProxyState.Starting, is ProxyState.Connecting,
    is ProxyState.CaptchaRequired -> HeroKind.Busy
    is ProxyState.Error -> HeroKind.Error
    is ProxyState.Idle -> HeroKind.Idle
}

@Composable
private fun HeroToggleButton(
    kind: HeroKind,
    tunnelActive: Boolean,
    reducedMotion: Boolean,
    onClick: () -> Unit
) {
    val extended = MaterialTheme.extendedColorScheme
    val buttonLabel = when (kind) {
        HeroKind.Busy -> stringResource(R.string.proxy_connecting)
        HeroKind.Running -> stringResource(
            if (tunnelActive) R.string.tunnel_active_stop else R.string.proxy_active_stop
        )
        HeroKind.Error -> stringResource(R.string.proxy_error_restart)
        HeroKind.Idle -> stringResource(R.string.start_proxy)
    }
    // Форма, размер и цвет тянутся одними и теми же спеками - иначе переход распадается на слои.
    val colorSpec = MaterialTheme.motionScheme.slowEffectsSpec<Color>()
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fastEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    val containerColor by animateColorAsState(
        targetValue = when (kind) {
            HeroKind.Running -> extended.successContainer
            HeroKind.Error -> MaterialTheme.colorScheme.errorContainer
            HeroKind.Busy -> MaterialTheme.colorScheme.secondaryContainer
            HeroKind.Idle -> MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = colorSpec,
        label = "btn_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = when (kind) {
            HeroKind.Running -> extended.onSuccessContainer
            HeroKind.Error -> MaterialTheme.colorScheme.onErrorContainer
            HeroKind.Busy -> MaterialTheme.colorScheme.onSecondaryContainer
            HeroKind.Idle -> MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = colorSpec,
        label = "btn_fg"
    )
    // scale/rotation читаются внутри graphicsLayer (фаза отрисовки) - иначе каждый кадр рекомпозирует кнопку.
    val scale = animateFloatAsState(
        targetValue = if (kind == HeroKind.Busy) 0.94f else 1f,
        animationSpec = spatialSpec,
        label = "btn_scale"
    )

    val heroShape = rememberMorphingShape(
        target = when (kind) {
            HeroKind.Idle -> MaterialShapes.Cookie12Sided
            HeroKind.Busy -> MaterialShapes.Sunny
            HeroKind.Running -> MaterialShapes.Circle
            HeroKind.Error -> MaterialShapes.SoftBurst
        },
        reducedMotion = reducedMotion
    )
    val rotation = rememberHeroSpin(spinning = kind == HeroKind.Busy && !reducedMotion)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(148.dp)
            // Один слой на scale+rotation: два graphicsLayer поверх generic-outline дают лишний рендер-нод.
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = rotation.value
            }
            .semantics { contentDescription = buttonLabel },
        shape = heroShape,
        color = containerColor,
        tonalElevation = if (kind == HeroKind.Running) 3.dp else 1.dp
    ) {
        Box(
            // Контр-вращение: крутится только фигура.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = -rotation.value },
            contentAlignment = Alignment.Center
        ) {
            if (reducedMotion) {
                HeroIcon(kind = kind, tint = contentColor)
            } else {
                AnimatedContent(
                    targetState = kind,
                    transitionSpec = {
                        (fadeIn(effectsSpec) + scaleIn(spatialSpec, initialScale = 0.85f))
                            .togetherWith(fadeOut(fastEffectsSpec) + scaleOut(fastEffectsSpec, targetScale = 0.85f))
                    },
                    label = "hero_icon"
                ) { k ->
                    HeroIcon(kind = k, tint = contentColor)
                }
            }
        }
    }
}

/** Непрерывное вращение фигуры с доводом до полного оборота на выходе. */
@Composable
private fun rememberHeroSpin(spinning: Boolean): State<Float> {
    val angle = remember { Animatable(0f) }
    val settleSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(spinning) {
        if (spinning) {
            // Один длинный проход вместо infiniteRepeatable: перезапуск tween даёт микро-рывок на стыке.
            angle.animateTo(angle.value + 360f * 240, tween(240 * 8_000, easing = LinearEasing))
        } else if (angle.value != 0f) {
            // Пружина подхватывает текущую скорость - вместо мгновенного сброса на 0.
            angle.animateTo(ceil(angle.value / 360f) * 360f, settleSpec)
            angle.snapTo(0f)
        }
    }
    return angle.asState()
}

@Composable
private fun HeroIcon(kind: HeroKind, tint: Color) {
    // Фиксированный слот: смена иконки не меняет размер контента (иначе AnimatedContent тянет ещё и размер).
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        when (kind) {
            HeroKind.Busy -> LoadingIndicator(color = tint, modifier = Modifier.size(64.dp))
            HeroKind.Running -> Icon(
                painterResource(R.drawable.check_circle_24px), null,
                Modifier.size(52.dp), tint = tint
            )
            HeroKind.Error -> Icon(
                painterResource(R.drawable.error_24px), null,
                Modifier.size(52.dp), tint = tint
            )
            HeroKind.Idle -> Icon(
                painterResource(R.drawable.play_arrow_24px), null,
                Modifier.size(52.dp), tint = tint
            )
        }
    }
}

@Composable
private fun StatusLabel(state: ProxyState, tunnelActive: Boolean, reducedMotion: Boolean) {
    val label = when (state) {
        is ProxyState.Running -> stringResource(
            if (tunnelActive) R.string.tunnel_active else R.string.proxy_active
        )
        is ProxyState.Starting, is ProxyState.Connecting -> stringResource(R.string.proxy_connecting)
        is ProxyState.Error -> state.message
        is ProxyState.CaptchaRequired -> stringResource(R.string.proxy_captcha_required)
        else -> stringResource(R.string.proxy_press_to_start)
    }
    val color by animateColorAsState(
        targetValue = when (state) {
            is ProxyState.Running -> MaterialTheme.extendedColorScheme.success
            is ProxyState.Error, is ProxyState.CaptchaRequired -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "status_color"
    )
    val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val text: @Composable (String) -> Unit = { value ->
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.xxxl)
        )
    }
    if (reducedMotion) {
        text(label)
    } else {
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                (fadeIn(enterSpec) + slideInVertically(slideSpec) { it / 3 })
                    .togetherWith(fadeOut(exitSpec) + slideOutVertically(slideSpec) { -it / 3 })
            },
            label = "status_label"
        ) { value -> text(value) }
    }
}

@Composable
private fun StatsPill(state: ProxyState, kind: HeroKind, uptimeText: String?) {
    val counts = when (state) {
        is ProxyState.Running ->
            if (state.total > 0) "${state.active}/${state.total}" else "${state.active}"
        is ProxyState.Connecting ->
            if (state.total > 0) "${state.active}/${state.total}" else null
        else -> null
    }
    val pillText = listOfNotNull(counts, uptimeText)
        .joinToString(" · ")
        .takeIf { it.isNotEmpty() && (kind == HeroKind.Running || kind == HeroKind.Busy) }

    // Слот фиксированной высоты: появление пилюли не сдвигает кнопку и статус.
    Box(modifier = Modifier.height(36.dp), contentAlignment = Alignment.Center) {
        // Последний непустой текст для exit-анимации.
        var lastText by remember { mutableStateOf("") }
        if (pillText != null) lastText = pillText
        AnimatedVisibility(
            visible = pillText != null,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), initialScale = 0.8f),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                scaleOut(MaterialTheme.motionScheme.fastEffectsSpec(), targetScale = 0.8f)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = lastText,
                    // tnum: моноширинные цифры (таймер не прыгает).
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
            }
        }
    }
}

/** Анимированный переход Shape в [target]. */
@Composable
private fun rememberMorphingShape(target: RoundedPolygon, reducedMotion: Boolean): Shape {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val morph = remember(from, to) { Morph(from, to) }
    val progress = remember { Animatable(1f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val catchUpSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()

    LaunchedEffect(target) {
        if (target === to) return@LaunchedEffect
        if (reducedMotion) {
            from = target
            to = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        // Новый Morph стартует от `to`, поэтому недокрученный старый сначала доводим до конца:
        // иначе форма прыгает с текущего кадра на `to` (главный источник рывка при быстрой смене состояний).
        if (progress.value < 1f) progress.animateTo(1f, catchUpSpec)
        from = to
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }

    val buffer = remember { android.graphics.Path() }
    val matrix = remember { Matrix() }
    return MorphShape(morph, progress.value, buffer, matrix)
}

private class MorphShape(
    private val morph: Morph,
    private val progress: Float,
    private val buffer: android.graphics.Path,
    // Общая с буфером пути: createOutline зовут каждый кадр морфа.
    private val matrix: Matrix
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress.coerceIn(0f, 1f), buffer).asComposePath()
        matrix.reset()
        matrix.scale(size.width, size.height)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}
