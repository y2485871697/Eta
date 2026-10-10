package io.github.mangi.eta.ui.app

import android.view.Choreographer
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastRoundToInt
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

internal const val NAVIGATION_ACTIVITY_STALE_NS = 200_000_000L

/** A draw that stopped updating must not keep the covered chat live. */
internal fun navigationActivityIsCurrent(flag: Boolean, stampNs: Long, nowNs: Long, staleNs: Long): Boolean =
    flag && nowNs - stampNs < staleNs

/**
 * Samples the navigation transition from the draw phase without writing snapshot state there.
 * The draw loop only stores atomics. A frame callback publishes the boolean when it actually changes,
 * and treats a frozen stamp as idle so the last frame cannot leave the chat running.
 */
internal class ChatNavigationActivityTracker(
    private val onChanged: (Boolean) -> Unit,
    private val staleNs: Long = NAVIGATION_ACTIVITY_STALE_NS,
    private val nowNs: () -> Long = System::nanoTime,
    private val poster: (Choreographer.FrameCallback) -> Unit = { callback ->
        Choreographer.getInstance().postFrameCallback(callback)
    },
    private val remover: (Choreographer.FrameCallback) -> Unit = { callback ->
        Choreographer.getInstance().removeFrameCallback(callback)
    },
) {
    private val moving = AtomicBoolean(false)
    private val stampNs = AtomicLong(0L)
    private val ticking = AtomicBoolean(false)
    private var published: Boolean? = null
    private val callback: Choreographer.FrameCallback = Choreographer.FrameCallback {
        val now = nowNs()
        if (moving.get() && !navigationActivityIsCurrent(true, stampNs.get(), now, staleNs)) {
            moving.set(false)
        }
        val active = moving.get()
        if (active != published) {
            published = active
            onChanged(active)
        }
        if (active) {
            poster(callback)
        } else {
            ticking.set(false)
        }
    }

    fun observeFrame(active: Boolean) {
        moving.set(active)
        stampNs.set(nowNs())
        if (active) ensureTicking()
    }

    fun cancel() {
        moving.set(false)
        if (ticking.compareAndSet(true, false)) remover(callback)
    }

    private fun ensureTicking() {
        if (ticking.compareAndSet(false, true)) poster(callback)
    }
}

internal class ChatNavigationActivityTransition(
    val transition: NavTransition,
    private val tracker: ChatNavigationActivityTracker,
) {
    fun cancel() = tracker.cancel()
}

/**
 * Same geometry as MiuixDefault (full-width slide, quarter-width parallax, 10% alpha), plus an
 * activity sample. The sample must not assign Compose state inside this draw block.
 */
internal fun chatNavigationActivityTransition(
    onActivityChanged: (Boolean) -> Unit,
): ChatNavigationActivityTransition {
    val tracker = ChatNavigationActivityTracker(onActivityChanged)
    val transition = navGraphicsTransition(opaqueDepth = 1f) { scope ->
        tracker.observeFrame(scope.gesture != null || scope.settle != null)
        applyDefaultCoveredChatTransform(scope)
    }
    return ChatNavigationActivityTransition(transition, tracker)
}

private fun GraphicsLayerScope.applyDefaultCoveredChatTransform(scope: NavTransitionScope) {
    val width = scope.layoutSize.width.toFloat()
    val depth = scope.relativeDepth
    val rtl = scope.layoutDirection == LayoutDirection.Rtl
    if (depth <= 0f) {
        translationX = ((if (rtl) -1f else 1f) * (-depth).coerceIn(0f, 1f) * width).fastRoundToInt().toFloat()
    } else {
        translationX = (if (rtl) 1f else -1f) * depth.coerceIn(0f, 1f) * width * 0.25f
        alpha = 1f - 0.1f * depth.coerceIn(0f, 1f)
    }
}
