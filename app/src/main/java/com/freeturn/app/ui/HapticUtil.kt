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
            Pattern.SELECTION -> VibrationEffect.createOneShot(11, 58)

            Pattern.CLICK -> VibrationEffect.createOneShot(18, 100)

            Pattern.TOGGLE_ON -> VibrationEffect.createWaveform(
                longArrayOf(0L, 24L, 60L, 15L),
                intArrayOf(0, 138, 0, 72),
                -1
            )

            Pattern.TOGGLE_OFF -> VibrationEffect.createWaveform(
                longArrayOf(0L, 19L, 55L, 11L),
                intArrayOf(0, 88, 0, 44),
                -1
            )

            Pattern.SUCCESS -> VibrationEffect.createWaveform(
                longArrayOf(0L, 16L, 48L, 11L, 48L, 8L),
                intArrayOf(0, 88, 0, 58, 0, 36),
                -1
            )

            Pattern.ERROR -> VibrationEffect.createWaveform(
                longArrayOf(0L, 32L, 22L, 32L, 22L, 20L),
                intArrayOf(0, 155, 0, 120, 0, 85),
                -1
            )

            Pattern.LAUNCH -> VibrationEffect.createWaveform(
                longArrayOf(0L, 36L, 88L, 22L),
                intArrayOf(0, 112, 0, 58),
                -1
            )
        }
        vibrator.vibrate(effect)
    }
}
