package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether this Home/Chat route is the current destination.
 * NavDisplay keeps covered entries composed for swipe-back; those entries must
 * not apply streaming message updates, or they share the frame with Settings.
 * Default true so standalone previews and the voice panel keep live content.
 */
val LocalChatUiActive = staticCompositionLocalOf { true }

/**
 * 聊天还在组合里，但已经不是栈顶。半遮住时仍要显示实时消息；
 * 整列离屏裁剪只留给完全打开的聊天，避免被盖住时每帧重录。
 */
// This value changes on navigation: invalidate readers, not the entire routed subtree.
val LocalChatRouteCovered = compositionLocalOf { false }

/**
 * 推入、弹出或横滑返回还在动。只在开始和结束变化，不随每一帧翻转。
 * 停稳且被盖住时聊天不再跟着流式正文排版；动画期间露出的部分仍要实时。
 */
val LocalChatTransitionActive = compositionLocalOf { false }
/** 完成且不再逐帧变化的内容复用一张离屏纹理。层始终挂着，只切换合成策略，避免插入时重挂。 */
@Composable
internal fun completedContentDrawLayer(modifier: Modifier, enabled: Boolean): Modifier {
    var heightPx by remember { mutableIntStateOf(0) }
    val retain = enabled && heightPx in 1..8192
    return modifier
        .onSizeChanged { heightPx = it.height }
        .graphicsLayer(
            compositingStrategy = if (retain) CompositingStrategy.Offscreen else CompositingStrategy.Auto,
        )
}
