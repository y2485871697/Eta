package io.github.mangi.eta.ui.haptics

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Main-thread, frame-synchronous feedback. No network-event queue or delayed replay. */
internal object StreamingHaptics {
    private class Gate(
        val view: View,
        val lifecycle: Lifecycle,
        val conversationId: String?,
        val enabled: () -> Boolean,
        val visibleReveal: () -> Boolean,
    )

    /**
     * Several chat hosts observe concurrently (home and chat screens, conversation key changes).
     * A single slot would be cleared by whichever instance disposes last even though another live
     * instance still owns the visible view, silencing feedback for the rest of the process.
     */
    private val gates = mutableListOf<Gate>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingBackgroundTicks = 0
    private var backgroundTickScheduled = false
    private var backgroundView: View? = null
    private var queuedReasoning = false
    private var foregroundConversationId: String? = null
    private var foregroundView: View? = null

    /** Test-only counter of allowed advances; never consulted by production paths. */
    @Volatile internal var allowedAdvances: Long = 0
        private set

    private val backgroundTick = object : Runnable {
        override fun run() {
            backgroundTickScheduled = false
            val view = backgroundView
            if (view == null || pendingBackgroundTicks <= 0 || !backgroundGate(view) ||
                !backgroundAllowed(queuedReasoning)
            ) {
                pendingBackgroundTicks = 0
                backgroundView = null
                queuedReasoning = false
                return
            }
            pendingBackgroundTicks--
            TouchHaptics.generationTick(view)
            if (pendingBackgroundTicks > 0) scheduleBackgroundTick()
        }
    }

    fun onVisibleAdvance(view: View) {
        synchronized(gates) { rememberForeground() }
        if (!foregroundGate(view)) return
        allowedAdvances++
        TouchHaptics.generationTick(view)
    }

    /**
     * Text and tool steps keep arriving after the activity stops, but the reveal clock does not.
     * Pulse the same generation tick directly so leaving the app does not cut the vibration.
     */
    /** 前台也走这条：工具标签只出现一次，不能等界面刚好在 32ms 的打字间隔里把这次丢掉。 */
    fun noteToolAppeared(toolId: String, conversationId: String? = null) {
        if (toolId.isBlank() || !backgroundAllowed()) return
        val view = currentConversationView(conversationId) ?: return
        TouchHaptics.onLiveToolActivity(view, toolId)
    }

    /** 推理块从「正在推理」变成「推理已完成」时轻触一次，和工具标签共用去重与排队。 */
    fun noteReasoningCompleted(thinkingId: String, conversationId: String? = null) {
        if (thinkingId.isBlank()) return
        noteToolAppeared("$thinkingId-completed", conversationId)
    }

    fun noteBackgroundOutput(graphemes: Int, conversationId: String? = null, reasoning: Boolean = false) {
        if (graphemes <= 0 || !backgroundAllowed(reasoning)) return
        // 应用内切到其它页面要马上停，不能把已经排上的补震放完。
        // 退到后台时页面不再 RESUMED，仍按后台震动设置补震。
        if (coveredWhileResumed(conversationId)) {
            cancelBackgroundTicks()
            return
        }
        val view = currentConversationView(conversationId) ?: return
        if (foregroundGate(view)) return
        allowedAdvances++
        pendingBackgroundTicks = (pendingBackgroundTicks + backgroundPulseCount(graphemes))
            .coerceAtMost(MAX_BACKGROUND_TICKS)
        backgroundView = view
        queuedReasoning = queuedReasoning || reasoning
        scheduleBackgroundTick()
    }

    /** 应用在前台一律放行；退到后台后按「后台震动」和「推理过程震动」决定。 */
    private fun backgroundAllowed(reasoning: Boolean = false): Boolean = when {
        AppForeground.isForeground -> true
        reasoning -> TouchHaptics.isBackgroundReasoningEnabled()
        else -> TouchHaptics.isBackgroundEnabled()
    }

    private fun claimForeground(conversationId: String?, view: View?) {
        if (conversationId.isNullOrBlank()) return
        if (conversationId != foregroundConversationId) cancelBackgroundTicks()
        foregroundConversationId = conversationId
        if (view != null) foregroundView = view
    }

    private fun rememberForeground() {
        // 暂停的会话也算当前页，否则切过去时原来还在跑的会话会继续震。
        val resumed = gates.firstOrNull { gate ->
            !gate.conversationId.isNullOrBlank() &&
                gate.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } ?: return
        claimForeground(resumed.conversationId, resumed.view)
    }

    private fun currentConversationView(conversationId: String?): View? {
        if (conversationId.isNullOrBlank()) return null
        return synchronized(gates) {
            rememberForeground()
            if (conversationId != foregroundConversationId) return@synchronized null
            val gate = gates.firstOrNull { it.conversationId == conversationId && it.enabled() }
            gate?.view ?: if (gates.none { it.conversationId == conversationId }) foregroundView else null
        }
    }

    private fun scheduleBackgroundTick() {
        if (backgroundTickScheduled) return
        backgroundTickScheduled = true
        mainHandler.postDelayed(backgroundTick, BACKGROUND_TICK_INTERVAL_MS)
    }

    /** 聊天还在前台组合里，但这个会话的震动已经关掉。用于设置页、管理页等盖住聊天的情况。 */
    private fun coveredWhileResumed(conversationId: String?): Boolean = synchronized(gates) {
        if (conversationId.isNullOrBlank()) return false
        gates.any { gate ->
            gate.conversationId == conversationId && !gate.enabled() &&
                gate.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }

    private fun foregroundGate(view: View): Boolean = synchronized(gates) {
        // 当前这条划出屏幕后，打字机节点会卸掉，可见推进也就不再进来。
        // 会话还在这个聊天页时仍算前台，后面的字走后台补震，不能把振动停掉。
        gates.any { gate ->
            gate.view === view && gate.enabled() &&
                gate.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }

    private fun backgroundGate(view: View): Boolean = synchronized(gates) {
        val id = foregroundConversationId ?: return false
        val ownsView = view === foregroundView || view === backgroundView ||
            gates.any { it.view === view && it.conversationId == id }
        if (!ownsView) return false
        val pageOpen = gates.any { gate ->
            gate.conversationId == id && gate.enabled() && gate.visibleReveal() &&
                gate.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        !pageOpen
    }

    private fun cancelBackgroundTicks() {
        pendingBackgroundTicks = 0
        backgroundView = null
        queuedReasoning = false
        backgroundTickScheduled = false
        mainHandler.removeCallbacks(backgroundTick)
    }

    @Composable
    fun Observe(
        enabled: Boolean,
        conversationId: String? = null,
        visibleReveal: Boolean = enabled,
    ) {
        val view = LocalView.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val active by rememberUpdatedState(enabled)
        val revealing by rememberUpdatedState(visibleReveal)
        SideEffect {
            if (!active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                synchronized(gates) {
                    if (conversationId == null || conversationId == foregroundConversationId) {
                        cancelBackgroundTicks()
                    }
                }
            }
        }
        DisposableEffect(view, lifecycle, conversationId) {
            val gate = Gate(view, lifecycle, conversationId, { active }, { revealing })
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    synchronized(gates) { claimForeground(conversationId, view) }
                }
            }
            synchronized(gates) {
                gates += gate
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    claimForeground(conversationId, view)
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                synchronized(gates) { gates.remove(gate) }
            }
        }
    }

    private const val BACKGROUND_TICK_INTERVAL_MS = 32L
    private const val MAX_BACKGROUND_TICKS = 36
}

/** One light tick per grapheme of hidden output, capped so a large chunk cannot buzz for long. */
internal fun backgroundPulseCount(graphemes: Int): Int = graphemes.coerceIn(1, 36)
