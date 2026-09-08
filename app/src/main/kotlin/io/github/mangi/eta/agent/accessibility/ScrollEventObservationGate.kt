package io.github.mangi.eta.agent.accessibility

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 只在 Eta 主动滚动后的短暂验证窗口内接收对应窗口的滚动事件。 */
internal class ScrollEventObservationGate(
    private val uptimeMillis: () -> Long,
) {
    class Observation internal constructor(
        internal val token: Long,
        internal val packageName: String,
        internal val windowId: Int,
        internal val startedAtUptimeMs: Long,
        internal val expiresAtUptimeMs: Long,
    )

    private val nextToken = AtomicLong(0L)
    private val active = AtomicReference<Observation?>(null)

    fun begin(
        packageName: String,
        windowId: Int,
        validForMillis: Long,
    ): Observation {
        val startedAt = uptimeMillis()
        return Observation(
            token = nextToken.incrementAndGet(),
            packageName = packageName,
            windowId = windowId,
            startedAtUptimeMs = startedAt,
            expiresAtUptimeMs = startedAt + validForMillis.coerceAtLeast(1L),
        ).also(active::set)
    }

    fun withMatchingObservation(
        packageName: String,
        windowId: Int,
        eventTimeMillis: Long,
        block: (Observation) -> Unit,
    ): Boolean {
        val observation = active.get() ?: return false
        if (!isActive(observation)) return false
        if (observation.packageName != packageName || observation.windowId != windowId) {
            return false
        }
        if (eventTimeMillis > 0L && eventTimeMillis < observation.startedAtUptimeMs) return false
        block(observation)
        return true
    }

    fun isActive(observation: Observation): Boolean {
        val current = active.get() ?: return false
        if (current.token != observation.token) return false
        if (uptimeMillis() <= current.expiresAtUptimeMs) return true
        active.compareAndSet(current, null)
        return false
    }

    fun end(observation: Observation) {
        val current = active.get() ?: return
        if (current.token == observation.token) active.compareAndSet(current, null)
    }

    fun clear() {
        active.set(null)
    }
}
