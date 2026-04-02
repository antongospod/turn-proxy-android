package com.freeturn.app.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Apple-like haptic patterns for a tactile, musical feel.
 * Each pattern mimics iPhone Taptic Engine characteristics:
 * short snappy pulses, harmonic cascades, ascending/descending amplitudes.
 */
object HapticUtil {

    enum class Pattern {
        /** Мягкое касание — смена выбора, тап по вкладке */
        SELECTION,
        /** Обычный клик — кнопки, действия */
        CLICK,
        /** Включение — тяжёлый удар + послезвук */
        TOGGLE_ON,
        /** Выключение — средний удар */
        TOGGLE_OFF,
        /** Успех — тройной каскад (восходящий) */
        SUCCESS,
        /** Ошибка — двойной тяжёлый buzz */
        ERROR,
        /** Старт приложения — двойной мягкий удар */
        LAUNCH,
    }

    fun perform(context: Context, pattern: Pattern) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
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

            // Музыкальная вибрация
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
        vibrator.vibrate(effect)
    }
}
