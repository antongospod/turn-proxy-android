package com.freeturn.app.data

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.annotation.RequiresApi

object HapticUtil {

    enum class Pattern {
        SELECTION,
        CLICK,
        TOGGLE_ON,
        TOGGLE_OFF,
        SUCCESS,
        ERROR,
        LAUNCH,
    }

    @Suppress("DEPRECATION")
    fun perform(context: Context, pattern: Pattern) {
        // С API 33 системный "Виброотклик при касании" применяется к USAGE_TOUCH самой платформой.
        // Ниже - читаем legacy-ключ сами (на этих версиях он ещё авторитетный).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !legacyHapticsEnabled(context)) return
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (pattern) {
                Pattern.SELECTION -> VibrationEffect.createOneShot(20, 120)

                Pattern.CLICK -> VibrationEffect.createOneShot(25, 160)

                Pattern.TOGGLE_ON -> VibrationEffect.createWaveform(
                    longArrayOf(0L, 35L, 50L, 20L),
                    intArrayOf(0, 200, 0, 110),
                    -1
                )

                Pattern.TOGGLE_OFF -> VibrationEffect.createWaveform(
                    longArrayOf(0L, 28L, 45L, 16L),
                    intArrayOf(0, 140, 0, 70),
                    -1
                )

                Pattern.SUCCESS -> VibrationEffect.createWaveform(
                    longArrayOf(0L, 22L, 40L, 16L, 40L, 12L),
                    intArrayOf(0, 140, 0, 100, 0, 65),
                    -1
                )

                Pattern.ERROR -> VibrationEffect.createWaveform(
                    longArrayOf(0L, 45L, 25L, 45L, 25L, 30L),
                    intArrayOf(0, 210, 0, 175, 0, 130),
                    -1
                )

                Pattern.LAUNCH -> VibrationEffect.createWaveform(
                    longArrayOf(
                        0L,
                        18L, 55L,
                        18L, 45L,
                        22L, 35L,
                        30L, 80L,
                        15L, 40L,
                        10L
                    ),
                    intArrayOf(
                        0,
                        80, 0,
                        110, 0,
                        150, 0,
                        210, 0,
                        60, 0,
                        30
                    ),
                    -1
                )
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(effect, touchAttrs())
                } else {
                    vibrator.vibrate(effect, legacyTouchAttrs())
                }
            } catch (_: Exception) {}
        } else {
            val duration = when (pattern) {
                Pattern.SELECTION -> 20L
                Pattern.CLICK -> 25L
                Pattern.TOGGLE_ON, Pattern.TOGGLE_OFF -> 50L
                Pattern.SUCCESS -> 60L
                Pattern.ERROR -> 100L
                Pattern.LAUNCH -> 70L
            }
            try { vibrator.vibrate(duration, legacyTouchAttrs()) } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyHapticsEnabled(context: Context): Boolean = try {
        Settings.System.getInt(
            context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1
        ) == 1
    } catch (_: Exception) { true }

    // Без атрибутов вибра уходит как USAGE_UNKNOWN - часть OEM-прошивок режет её вместе с фоновыми.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun touchAttrs(): VibrationAttributes =
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)

    private fun legacyTouchAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}
