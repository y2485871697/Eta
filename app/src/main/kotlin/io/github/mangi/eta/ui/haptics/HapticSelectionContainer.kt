package io.github.mangi.eta.ui.haptics

import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * 会话文本选区。Compose 长按选中有时只发 [HapticFeedbackType.TextHandleMove]，
 * 在 HyperOS 上几乎无感，这里统一转成 [TouchHaptics.longPress]。
 */
@Composable
internal fun HapticSelectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val parent = LocalHapticFeedback.current
    val haptic = remember(view, parent) { SelectionHapticFeedback(view, parent) }
    CompositionLocalProvider(LocalHapticFeedback provides haptic) {
        SelectionContainer(modifier = modifier, content = content)
    }
}

private class SelectionHapticFeedback(
    private val view: View,
    private val parent: HapticFeedback,
) : HapticFeedback {
    private var lastAt = 0L

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        when (hapticFeedbackType) {
            HapticFeedbackType.LongPress,
            HapticFeedbackType.TextHandleMove,
            -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastAt < 360L) return
                lastAt = now
                TouchHaptics.longPress(view)
            }
            else -> parent.performHapticFeedback(hapticFeedbackType)
        }
    }
}
