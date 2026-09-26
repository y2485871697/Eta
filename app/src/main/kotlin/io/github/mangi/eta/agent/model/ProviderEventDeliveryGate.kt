package io.github.mangi.eta.agent.model

import java.util.concurrent.locks.ReentrantLock

/**
 * 单次 [AgentProviderClient.complete] 尝试独占的事件投递闸门。
 *
 * [AgentSseClient.SseStream.finish] 先 `countDown` 再 `cancel`，因此收集线程可能在 OkHttp 读线程
 * 仍处于 provider 回调中时就从 `complete` 返回。若没有闸门，被取代的旧尝试（steering/暂停、
 * transport 重试）的迟到回调仍会进入 `onProviderEvent`，覆盖下一请求记录的 usage。
 *
 * 关闭判定与消费者调用在同一把锁内完成，因此不存在 check-then-act 竞态：已经进入的回调持有锁，
 * [close] 会等它结束；[close] 之后到达的回调直接被丢弃。[close] 只等待在途回调，不等待网络收尾。
 */
internal class ProviderEventDeliveryGate {
    private val lock = ReentrantLock()

    @Volatile
    private var closed = false

    /** [close] 之后为 true。 */
    val isClosed: Boolean
        get() = closed

    /** 在锁内同步执行 [deliver]；闸门已封时丢弃该事件。 */
    fun deliver(deliver: () -> Unit) {
        lock.lock()
        try {
            if (closed) return
            deliver()
        } finally {
            lock.unlock()
        }
    }

    /** 封闸；若已有回调在途，先等其结束。 */
    fun close() {
        lock.lock()
        try {
            closed = true
        } finally {
            lock.unlock()
        }
    }
}
