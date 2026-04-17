package com.freeturn.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Дополнительная цветовая схема для ролей, которых нет в MaterialTheme.colorScheme:
 * success / warning / info. Значения сгенерированы через Material Theme Builder
 * (seed: success #4CAF50, warning #E67E22, info #2196F3) и держат полный набор
 * тональных пар, чтобы корректно пройти контраст 4.5:1 в light/dark.
 */
@Immutable
data class ExtendedColorScheme(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

private val extendedLight = ExtendedColorScheme(
    success = Color(0xFF006E1C),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFF7BFB88),
    onSuccessContainer = Color(0xFF002204),
    warning = Color(0xFF8B5000),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDCBE),
    onWarningContainer = Color(0xFF2D1600),
    info = Color(0xFF00639C),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFCEE5FF),
    onInfoContainer = Color(0xFF001D33),
)

private val extendedDark = ExtendedColorScheme(
    success = Color(0xFF5FDB72),
    onSuccess = Color(0xFF00390B),
    successContainer = Color(0xFF005313),
    onSuccessContainer = Color(0xFF7BFB88),
    warning = Color(0xFFFFB787),
    onWarning = Color(0xFF4B2800),
    warningContainer = Color(0xFF6B3C00),
    onWarningContainer = Color(0xFFFFDCBE),
    info = Color(0xFF97CCFF),
    onInfo = Color(0xFF003354),
    infoContainer = Color(0xFF004B77),
    onInfoContainer = Color(0xFFCEE5FF),
)

internal fun extendedColorSchemeFor(darkTheme: Boolean): ExtendedColorScheme =
    if (darkTheme) extendedDark else extendedLight

internal val LocalExtendedColorScheme = staticCompositionLocalOf { extendedLight }

val MaterialTheme.extendedColorScheme: ExtendedColorScheme
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColorScheme.current
