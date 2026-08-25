@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.freeturn.app.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.data.HapticUtil
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Сезонное оформление героя: вращение фигуры кнопки пальцем, тёплый ореол и разлёт
 * листвы по нажатию. Сезон кончится - ставим false, и весь код ниже перестаёт попадать
 * в composition. Форму и палитру кнопки не трогаем: на Material You цвета приходят
 * с обоев, и своя пара выбивалась бы из темы пользователя.
 */
internal const val AUTUMN_VIBE = true

private const val LEAF_COUNT = 7
private const val BURST_MS = 950

/** Грань Cookie12Sided: на отпускании фигура доводится до кратного угла. */
private const val SPIN_NOTCH_DEG = 30f

/** Сектор разлёта: вверх и в стороны. Вниз лист не выстреливает, он туда падает сам. */
private const val SPREAD_FROM_DEG = -170f
private const val SPREAD_TO_DEG = -10f

private val autumnLeavesLight = listOf(
    Color(0xFFB4530A),
    Color(0xFFD97706),
    Color(0xFF8B3A0F),
    Color(0xFFC98A12),
    Color(0xFF9A6212),
)

private val autumnLeavesDark = listOf(
    Color(0xFFFFB787),
    Color(0xFFFFC46B),
    Color(0xFFE98A4F),
    Color(0xFFD9A441),
    Color(0xFFFFD9A0),
)

// Тема не отдаёт darkTheme наружу, а Material You подменяет всю схему - светлота фона
// единственный признак, верный и для статичных схем, и для динамических.
@Composable
private fun autumnLeafColors(): List<Color> =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) autumnLeavesDark else autumnLeavesLight

@Immutable
private class Leaf(
    val angleRad: Float,
    val speed: Float,
    val spin: Float,
    val scale: Float,
    val drift: Float,
    val delay: Float,
    val colorIndex: Int,
)

// Угол не случайный, а свой сектор на лист с джиттером внутри: на семи чисто случайных
// углах листья сбиваются в кучу и наезжают друг на друга.
private fun leafBatch(seed: Int, colorCount: Int): List<Leaf> {
    val rnd = Random(seed)
    val sector = (SPREAD_TO_DEG - SPREAD_FROM_DEG) / LEAF_COUNT
    return List(LEAF_COUNT) { i ->
        val deg = SPREAD_FROM_DEG + sector * (i + 0.15f + rnd.nextFloat() * 0.7f)
        Leaf(
            angleRad = Math.toRadians(deg.toDouble()).toFloat(),
            speed = 0.85f + rnd.nextFloat() * 0.75f,
            spin = (if (rnd.nextBoolean()) 1f else -1f) * (0.5f + rnd.nextFloat()),
            // Не больше 1: вектор растеризуется под leafPx, апскейл дал бы мыло.
            scale = 0.55f + rnd.nextFloat() * 0.45f,
            drift = (rnd.nextFloat() - 0.5f) * 0.7f,
            // Небольшой разбег по времени: одновременный старт читается как одно пятно.
            delay = rnd.nextFloat() * 0.18f,
            colorIndex = rnd.nextInt(colorCount),
        )
    }
}

/**
 * Тёплый ореол под кнопкой в её же цвете. Кладётся ПОД героя, поэтому цвет берёт
 * снаружи - анимация контейнера уже посчитана вызывающим.
 */
@Composable
internal fun AutumnHeroGlow(
    color: Color,
    strong: Boolean,
    buttonSize: Dp,
    modifier: Modifier = Modifier
) {
    val alpha by animateFloatAsState(
        targetValue = if (strong) 0.45f else 0.18f,
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "hero_glow"
    )
    // Ореол шире слоя и рисуется за его границами - радиус задаём явно, иначе градиент
    // растянулся бы по границам Canvas и обрезался ровно по кругу кнопки.
    val radiusPx = with(LocalDensity.current) { buttonSize.toPx() * 0.85f }
    val brush = remember(color, radiusPx) {
        Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), radius = radiusPx)
    }

    Canvas(modifier.size(buttonSize)) { drawCircle(brush = brush, radius = radiusPx, alpha = alpha) }
}

/**
 * Разлёт листвы на каждую смену [burstKey]. Кладётся ПОВЕРХ героя: под кнопкой листья
 * прятались за её силуэтом и выглядели обрезанными. Кадры идут только секунду после
 * нажатия - в покое слой ничего не считает. Рисует за своими границами, поэтому
 * родитель не должен клипать содержимое.
 */
@Composable
internal fun AutumnLeafBurst(
    burstKey: Int,
    buttonSize: Dp,
    modifier: Modifier = Modifier
) {
    val colors = autumnLeafColors()
    val painter = painterResource(R.drawable.eco_24px)
    // 1 - разлёта нет: пока прогресс на единице, слой не рисует и не считает.
    val progress = remember { Animatable(1f) }
    var batch by remember { mutableStateOf(emptyList<Leaf>()) }

    LaunchedEffect(burstKey) {
        if (burstKey == 0) return@LaunchedEffect
        batch = leafBatch(burstKey, colors.size)
        progress.snapTo(0f)
        progress.animateTo(1f, tween(BURST_MS, easing = LinearEasing))
    }

    Canvas(modifier.size(buttonSize)) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val radius = buttonSize.toPx() / 2f
        val leafPx = 34.dp.toPx()
        val leafSize = Size(leafPx, leafPx)
        val leafPivot = Offset(leafPx / 2f, leafPx / 2f)

        batch.forEach { leaf ->
            val lt = ((t - leaf.delay) / (1f - leaf.delay)).coerceIn(0f, 1f)
            if (lt <= 0f) return@forEach
            // Вылет с торможением плюс равноускоренное падение - дуга подброшенного листа.
            val ease = 1f - (1f - lt) * (1f - lt)
            // Старт от кромки кнопки, а не из центра: иначе первые кадры - куча в одной точке.
            val dist = radius * (0.75f + 1.15f * leaf.speed * ease)
            val x = center.x + cos(leaf.angleRad) * dist + leaf.drift * radius * lt
            val y = center.y + sin(leaf.angleRad) * dist + radius * 1.1f * lt * lt
            val fade = if (lt < 0.5f) 1f else 1f - (lt - 0.5f) * 2f
            // Размер вектора всегда один, разница в масштабе - матрицей: вектор растеризуется
            // под запрошенный размер, и семь меняющихся сторон перестраивали кэш каждый кадр.
            val k = leaf.scale * (1f - 0.25f * lt)
            withTransform({
                translate(x - leafSize.width / 2f, y - leafSize.height / 2f)
                rotate(leaf.spin * 360f * lt, pivot = leafPivot)
                scale(k, k, pivot = leafPivot)
            }) {
                with(painter) {
                    draw(
                        size = leafSize,
                        alpha = fade.coerceIn(0f, 1f) * 0.9f,
                        colorFilter = ColorFilter.tint(colors[leaf.colorIndex % colors.size])
                    )
                }
            }
        }
    }
}

/**
 * Вращение фигуры пальцем вокруг центра кнопки: на отпускании инерция доводит до
 * ближайшего лепестка и щёлкает хаптиком. [enabled] снимает жест целиком - пока прокси
 * стартует, фигура крутится своим спином, и два источника угла дрались бы.
 */
@Composable
internal fun Modifier.leafSpin(
    spin: Animatable<Float, AnimationVector1D>,
    enabled: Boolean
): Modifier {
    // Всё composable - до выхода: под условием эти вызовы разъезжались бы по слотам.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settleSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    if (!enabled) return this

    return pointerInput(Unit) {
        val center = Offset(size.width / 2f, size.height / 2f)
        var last = 0f
        // Градусы в миллисекунду по последнему событию: velocity tracker углов не считает.
        var speed = 0f

        fun settle() {
            scope.launch {
                val v = (speed * 1000f).coerceIn(-2400f, 2400f)
                if (abs(v) > 90f) spin.animateDecay(v, exponentialDecay(frictionMultiplier = 1.6f))
                val target = (spin.value / SPIN_NOTCH_DEG).roundToInt() * SPIN_NOTCH_DEG
                spin.animateTo(target, settleSpec)
                // Иначе угол растёт без предела и теряет точность на долгой крутке.
                spin.snapTo(target.mod(360f))
                HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
            }
        }

        detectDragGestures(
            onDragStart = { pos ->
                last = angleDeg(pos - center)
                speed = 0f
                scope.launch { spin.stop() }
            },
            onDragEnd = ::settle,
            onDragCancel = ::settle
        ) { change, _ ->
            val now = angleDeg(change.position - center)
            // Переход через 180 иначе читается как оборот в обратную сторону.
            val delta = ((now - last + 540f) % 360f) - 180f
            last = now
            val dt = (change.uptimeMillis - change.previousUptimeMillis).coerceAtLeast(1L)
            speed = delta / dt
            scope.launch { spin.snapTo(spin.value + delta) }
            change.consume()
        }
    }
}

private fun angleDeg(v: Offset): Float =
    Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()
