package io.github.mangi.eta.agent.overlay

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * Agent 前台操作的触感语义。
 *
 * 优先让系统根据线性马达能力渲染预定义 primitive；设备不支持时退回系统 effect，
 * 避免固定时长、默认振幅带来的持续嗡鸣感。
 */
internal object AgentHapticFeedback {
    enum class Type(
        val primitiveId: Int,
        val primitiveScale: Float,
        val fallbackEffectId: Int,
    ) {
        TAP(
            primitiveId = VibrationEffect.Composition.PRIMITIVE_CLICK,
            primitiveScale = 0.45f,
            fallbackEffectId = VibrationEffect.EFFECT_TICK,
        ),
        LONG_PRESS(
            primitiveId = VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            primitiveScale = 0.7f,
            fallbackEffectId = VibrationEffect.EFFECT_HEAVY_CLICK,
        ),
        SWIPE(
            primitiveId = VibrationEffect.Composition.PRIMITIVE_TICK,
            primitiveScale = 0.32f,
            fallbackEffectId = VibrationEffect.EFFECT_TICK,
        ),
        RUN_STARTED(
            primitiveId = VibrationEffect.Composition.PRIMITIVE_CLICK,
            primitiveScale = 0.62f,
            fallbackEffectId = VibrationEffect.EFFECT_CLICK,
        ),
    }

    fun perform(context: Context, type: Type) {
        if (!isSystemHapticEnabled(context)) return
        val vibrator = context.getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?: return
        if (!vibrator.hasVibrator()) return

        runCatching {
            val supportsPrimitive = vibrator
                .arePrimitivesSupported(type.primitiveId)
                .firstOrNull() == true
            val effect = if (supportsPrimitive) {
                VibrationEffect.startComposition()
                    .addPrimitive(type.primitiveId, type.primitiveScale)
                    .compose()
            } else {
                VibrationEffect.createPredefined(type.fallbackEffectId)
            }
            vibrator.vibrate(effect)
        }
    }

    @Suppress("DEPRECATION")
    private fun isSystemHapticEnabled(context: Context): Boolean =
        runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1,
            ) != 0
        }.getOrDefault(true)
}
