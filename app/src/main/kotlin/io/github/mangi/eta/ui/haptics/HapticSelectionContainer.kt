package io.github.mangi.eta.ui.haptics

import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * 会话文本选区。按住开始选中时震一次；滑动扩展、拖动手柄或取消选择都不震。
 *
 * Compose 选区变化会连发 [HapticFeedbackType.TextHandleMove]，在 HyperOS 上若转成长按
 * 就会跟着滑。这里吞掉系统选区震动，改由长按超时自己触发一次。
 */
@Composable
internal fun HapticSelectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val parent = LocalHapticFeedback.current
    val haptic = remember(parent) { SelectionHapticFeedback(parent) }
    CompositionLocalProvider(LocalHapticFeedback provides haptic) {
        SelectionContainer(
            modifier = modifier.pointerInput(view) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) return@withTimeout
                                if ((change.position - down.position).getDistance() > slop) {
                                    return@withTimeout
                                }
                            }
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        fireSelectionStartHaptic(view)
                    }
                }
            },
            content = content,
        )
    }
}

private var lastSelectionStartAt = 0L

private fun fireSelectionStartHaptic(view: View) {
    val now = SystemClock.uptimeMillis()
    if (now - lastSelectionStartAt < 80L) return
    lastSelectionStartAt = now
    TouchHaptics.longPress(view)
}

private class SelectionHapticFeedback(
    private val parent: HapticFeedback,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        when (hapticFeedbackType) {
            HapticFeedbackType.LongPress,
            HapticFeedbackType.TextHandleMove,
            -> Unit
            else -> parent.performHapticFeedback(hapticFeedbackType)
        }
    }
}
