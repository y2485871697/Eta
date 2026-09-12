package io.github.mangi.eta.ui.haptics

import androidx.compose.foundation.text.selection.Selection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * 会话文本选区。刚选中时震一次；滑动手柄扩展或取消选择都不震。
 * Compose 拖动手柄会发 [HapticFeedbackType.TextHandleMove]，这里吞掉以免连震。
 */
@Composable
internal fun HapticSelectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val parent = LocalHapticFeedback.current
    val haptic = remember(parent) { SelectionHapticFeedback(parent) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    CompositionLocalProvider(LocalHapticFeedback provides haptic) {
        SelectionContainer(
            modifier = modifier,
            selection = selection,
            onSelectionChange = { next ->
                if (selection == null && next != null) {
                    TouchHaptics.longPress(view)
                }
                selection = next
            },
        ) {
            content()
        }
    }
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
