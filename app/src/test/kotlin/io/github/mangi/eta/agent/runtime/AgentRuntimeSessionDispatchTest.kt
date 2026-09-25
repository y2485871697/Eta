package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeSessionDispatchTest {
    @Test fun childCompactionFromLiveCallbackRejectsWithoutDispatch() =
        assertReentrantChildRejected(Callback.LIVE)

    @Test fun childCompactionFromReplayCallbackRejectsWithoutDispatch() =
        assertReentrantChildRejected(Callback.REPLAY)

    @Test fun childCompactionFromReplayBoundaryRejectsWithoutDispatch() =
        assertReentrantChildRejected(Callback.BOUNDARY)

    @Test fun ordinaryChildCompactionPreservesTargetAndSynchronousResultOutsideLock() {
        val session = AgentRuntimeSession(RUN_ID)
        val targets = mutableListOf<Pair<String, Int?>>()
        val readsFinished = mutableListOf<Boolean>()
        val readerFailure = AtomicReference<Throwable?>(null)
        session.childCompactor = { id, keep, _ ->
            targets += id to keep
            readsFinished += readTerminalOnOtherThread(session, readerFailure)
            id == "running-child"
        }

        assertTrue(session.requestCompact(keepRecentMessages = 7, childTaskId = "running-child"))
        assertFalse(session.requestCompact(keepRecentMessages = 3, childTaskId = "ended-child"))
        assertEquals(listOf("running-child" to 7, "ended-child" to 3), targets)
        assertEquals(listOf(true, true), readsFinished)
        assertNull(readerFailure.get())
        assertFalse(session.controller.hasPendingCompact)
        assertTrue(session.cancel("done"))
        assertFalse(session.requestCompact(childTaskId = "running-child"))
        assertEquals(2, targets.size)
    }

    @Test fun cancellationWaitsForAdmittedChildAndRejectsLaterDispatch() =
        assertTerminalRace(complete = false)

    @Test fun completionWaitsForAdmittedChildAndPersistsBeforePublishing() =
        assertTerminalRace(complete = true)

    @Test fun childMayCompleteReentrantlyWithoutWaitingOnItself() {
        val order = mutableListOf<String>()
        val session = AgentRuntimeSession(RUN_ID, resultSink = { order += "result" })
        var accepted: Boolean? = null
        var terminalDuringChild: Boolean? = null
        var laterRequest: Boolean? = null
        session.childCompactor = { _, _, _ ->
            order += "child"
            accepted = session.complete(success()) { order += "persist" }
            terminalDuringChild = session.isTerminal
            laterRequest = session.requestCompact(childTaskId = "too-late")
            order += "child-return"
            true
        }

        assertTrue(session.requestCompact(childTaskId = "child"))
        assertEquals(true, accepted)
        assertEquals(false, terminalDuringChild)
        assertEquals(false, laterRequest)
        assertEquals(listOf("child", "child-return", "persist", "result"), order)
        assertTrue(session.isTerminal)
    }

    @Test fun throwingChildReleasesAdmissionAndStillPublishesClaimedTerminal() {
        val failure = IllegalStateException("compactor failed")
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession(RUN_ID, resultSink = results::add)
        var cancelled: Boolean? = null
        session.childCompactor = { _, _, _ ->
            cancelled = session.cancel("replaced")
            throw failure
        }

        val attempted = runCatching { session.requestCompact(childTaskId = "child") }

        assertSame(failure, attempted.exceptionOrNull())
        assertEquals(true, cancelled)
        assertTrue(session.isTerminal)
        assertEquals("replaced", results.single().error)
        assertFalse(session.requestCompact(childTaskId = "child"))
    }

    @Test fun replaySteeringReachesAttachingSubscriberOnlyAfterReplayBoundary() {
        val existing = mutableListOf<AgentEvent>()
        val attached = mutableListOf<AgentEvent>()
        val order = mutableListOf<String>()
        val returns = mutableListOf<AgentEvent?>()
        val supplement = AgentEvent.UserSupplementReceived(1, "during replay")
        val session = AgentRuntimeSession(RUN_ID, eventSink = existing::add)
        assertTrue(session.emit(round(1)))
        assertTrue(session.emit(round(2)))

        assertTrue(session.attach({ event ->
            attached += event
            order += if (event == supplement) "live" else "replay"
            if (event == round(1)) {
                returns += session.steer("during replay") { supplement }
            }
        }, {}, { order += "boundary" }))

        assertEquals(listOf(supplement), returns)
        assertEquals(listOf(round(1), round(2), supplement), existing)
        assertEquals(existing, attached)
        assertEquals(listOf("replay", "replay", "boundary", "live"), order)
    }

    @Test fun boundarySteeringAndNonReplayableLiveEventsAreNotLost() {
        val existing = mutableListOf<AgentEvent>()
        val attached = mutableListOf<AgentEvent>()
        val order = mutableListOf<String>()
        val accepted = mutableListOf<Boolean>()
        val session = AgentRuntimeSession(RUN_ID, eventSink = existing::add)
        val privateDelta = AgentEvent.AssistantBlockDelta(
            round = 1, kind = AgentEvent.AssistantBlockKind.TOOL_CALL,
            index = 0, deltaChars = 6, delta = "secret",
        )
        val supplement = AgentEvent.UserSupplementReceived(1, "at boundary")
        assertTrue(session.emit(round(1)))
        var returned: AgentEvent? = null

        assertTrue(session.attach({ event ->
            attached += event
            order += if (event == round(1)) "replay" else "live"
            if (event == round(1)) accepted += session.emit(privateDelta)
        }, {}, {
            order += "boundary-start"
            returned = session.steer("at boundary") { supplement }
            order += "boundary-end"
        }))

        assertEquals(listOf(true), accepted)
        assertEquals(supplement, returned)
        assertEquals(listOf(round(1), privateDelta, supplement), existing)
        assertEquals(existing, attached)
        assertEquals(listOf("replay", "boundary-start", "boundary-end", "live", "live"), order)
        val recovered = mutableListOf<AgentEvent>()
        assertTrue(session.attach(recovered::add, {}))
        assertEquals(listOf(round(1), supplement), recovered)
    }

    @Test fun nestedSteeringAndEmitFinishOriginalBroadcastBeforeNextEvent() {
        val first = mutableListOf<AgentEvent>()
        val second = mutableListOf<AgentEvent>()
        val order = mutableListOf<String>()
        val supplement = AgentEvent.UserSupplementReceived(1, "nested")
        var returned: AgentEvent? = null
        var nestedEmit: Boolean? = null
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession(RUN_ID, eventSink = { event ->
            first += event
            order += "first-$event"
            if (event == round(1)) {
                returned = session.steer("nested") { supplement }
                nestedEmit = session.emit(round(2))
                order += "first-return"
            }
        })
        assertTrue(session.attach({ second += it; order += "second-$it" }, {}))

        assertTrue(session.emit(round(1)))

        assertEquals(supplement, returned)
        assertEquals(true, nestedEmit)
        assertEquals(listOf(round(1), supplement, round(2)), first)
        assertEquals(first, second)
        assertEquals(listOf(
            "first-${round(1)}", "first-return", "second-${round(1)}",
            "first-$supplement", "second-$supplement",
            "first-${round(2)}", "second-${round(2)}",
        ), order)
    }

    @Test fun attachDuringQueuedBroadcastDoesNotDuplicateCoalescedReplay() {
        val first = mutableListOf<AgentEvent>()
        val second = mutableListOf<AgentEvent>()
        val third = mutableListOf<AgentEvent>()
        val order = mutableListOf<String>()
        val a = textDelta("a")
        val b = textDelta("b")
        val supplement = AgentEvent.UserSupplementReceived(1, "from nested attach")
        var attached: Boolean? = null
        var returned: AgentEvent? = null
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession(RUN_ID, eventSink = { event ->
            first += event
            if (event == round(1)) {
                session.emit(a)
                session.emit(b)
                attached = session.attach({ replay ->
                    third += replay
                    order += if (replay == supplement) "live" else "replay"
                    if (replay == round(1)) {
                        returned = session.steer("from nested attach") { supplement }
                    }
                }, {}, { order += "boundary" })
            }
        })
        assertTrue(session.attach(second::add, {}))

        assertTrue(session.emit(round(1)))

        assertEquals(true, attached)
        assertEquals(supplement, returned)
        assertEquals(listOf(round(1), a, b, supplement), first)
        assertEquals(first, second)
        assertEquals(listOf(round(1), textDelta("ab"), supplement), third)
        assertEquals(listOf("replay", "replay", "boundary", "live"), order)
    }

    @Test fun cancellationDuringReplaySuppressesRemainingReplayAndQueuedLiveDelivery() {
        val existing = mutableListOf<AgentEvent>()
        val replay = mutableListOf<AgentEvent>()
        val existingResults = mutableListOf<AgentRuntimeWire.RunResult>()
        val attachedResults = mutableListOf<AgentRuntimeWire.RunResult>()
        var boundaryCalls = 0
        var cancelAccepted: Boolean? = null
        var returned: AgentEvent? = null
        var postTerminalEvents = 0
        val session = AgentRuntimeSession(RUN_ID, eventSink = existing::add, resultSink = existingResults::add)
        assertTrue(session.emit(round(1)))
        assertTrue(session.emit(round(2)))
        val supplement = AgentEvent.UserSupplementReceived(1, "before cancel")

        assertFalse(session.attach({ event ->
            if (session.isTerminal) postTerminalEvents++
            replay += event
            returned = session.steer("before cancel") { supplement }
            cancelAccepted = session.cancel("replaced")
        }, attachedResults::add, { boundaryCalls++ }))

        assertEquals(supplement, returned)
        assertEquals(true, cancelAccepted)
        assertEquals(listOf(round(1)), replay)
        assertEquals(listOf(round(1), round(2)), existing)
        assertEquals(0, boundaryCalls)
        assertEquals(0, postTerminalEvents)
        assertEquals(1, existingResults.size)
        assertEquals(existingResults, attachedResults)
        assertTrue(session.isTerminal)
        assertFalse(session.attach({ postTerminalEvents++ }, {}, { boundaryCalls++ }))
        assertEquals(0, postTerminalEvents)
        assertEquals(0, boundaryCalls)
    }

    @Test fun failedReplayDoesNotStrandAcceptedLiveEventForHealthySubscribers() {
        val existing = mutableListOf<AgentEvent>()
        val session = AgentRuntimeSession(RUN_ID, eventSink = existing::add)
        assertTrue(session.emit(round(1)))
        var replayCalls = 0
        var boundaryCalls = 0
        val supplement = AgentEvent.UserSupplementReceived(1, "accepted before failure")
        var returned: AgentEvent? = null
        val attached = runCatching {
            session.attach({
                replayCalls++
                returned = session.steer("accepted before failure") { supplement }
                error("failed replay callback")
            }, {}, { boundaryCalls++ })
        }

        assertNull(attached.exceptionOrNull())
        assertEquals(false, attached.getOrNull())
        assertEquals(supplement, returned)
        assertEquals(listOf(round(1), supplement), existing)
        assertTrue(session.emit(round(2)))
        assertEquals(listOf(round(1), supplement, round(2)), existing)
        assertEquals(1, replayCalls)
        assertEquals(0, boundaryCalls)
    }

    private fun assertReentrantChildRejected(callback: Callback) {
        val returned = mutableListOf<Boolean>()
        val readsFinished = mutableListOf<Boolean>()
        val readerFailure = AtomicReference<Throwable?>(null)
        var calls = 0
        lateinit var session: AgentRuntimeSession
        fun request() {
            returned += session.requestCompact(childTaskId = "child")
        }
        session = AgentRuntimeSession(RUN_ID, eventSink = {
            if (callback == Callback.LIVE) request()
        })
        session.childCompactor = { _, _, _ ->
            calls++
            // Detect the original lock inversion without blocking the test forever.
            readsFinished += readTerminalOnOtherThread(session, readerFailure)
            true
        }
        assertTrue(session.emit(round(1)))
        if (callback != Callback.LIVE) {
            assertTrue(session.attach({
                if (callback == Callback.REPLAY) request()
            }, {}, {
                if (callback == Callback.BOUNDARY) request()
            }))
        }

        assertEquals(listOf(false), returned)
        assertEquals("No child work may be deferred after a synchronous rejection", 0, calls)
        assertTrue(readsFinished.isEmpty())
        assertNull(readerFailure.get())
        assertFalse(session.controller.hasPendingCompact)
        assertTrue(session.cancel("finished"))
        assertEquals(0, calls)
    }

    private fun assertTerminalRace(complete: Boolean) {
        val enteredChild = CountDownLatch(1)
        val releaseChild = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)
        val childReturned = AtomicReference<Boolean?>(null)
        val results = Collections.synchronizedList(mutableListOf<AgentRuntimeWire.RunResult>())
        val order = Collections.synchronizedList(mutableListOf<String>())
        var claimed: Boolean? = null
        var terminalAtEntry: Boolean? = null
        var calls = 0
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession(RUN_ID, eventSink = {
            claimed = if (complete) session.complete(success()) { order += "persist" }
            else session.cancel("replaced")
            order += "claim"
        }, resultSink = { results += it; order += "result" })
        session.childCompactor = { _, _, _ ->
            calls++
            terminalAtEntry = session.isTerminal
            order += "child-enter"
            enteredChild.countDown()
            check(releaseChild.await(3, TimeUnit.SECONDS)) { "child was not released" }
            order += "child-return"
            true
        }
        val child = Thread {
            try {
                childReturned.set(session.requestCompact(childTaskId = "child"))
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }.apply { isDaemon = true; name = "admitted-child-compaction" }

        child.start()
        try {
            assertTrue(enteredChild.await(2, TimeUnit.SECONDS))
            // A callback cannot wait for the child: its coordinator may hold a monitor
            // needed by that child. It claims terminal now and seals on child unwind.
            assertTrue(session.emit(round(1)))
            assertEquals(true, claimed)
            assertFalse(session.isTerminal)
            assertTrue(results.isEmpty())
            assertFalse(session.requestCompact(childTaskId = "too-late"))
            assertFalse(session.requestCompact())
            assertFalse(session.cancel("loser"))
            assertFalse(session.complete(success()))
        } finally {
            releaseChild.countDown()
            child.join(3_000)
        }

        assertFalse("Child/terminal coordination deadlocked", child.isAlive)
        assertNull(workerFailure.get())
        assertEquals(true, childReturned.get())
        assertEquals(false, terminalAtEntry)
        assertEquals(1, calls)
        assertTrue(session.isTerminal)
        assertEquals(1, results.size)
        assertEquals(complete, results.single().ok)
        assertEquals(
            if (complete) listOf("child-enter", "claim", "child-return", "persist", "result")
            else listOf("child-enter", "claim", "child-return", "result"),
            order,
        )
        assertFalse(session.requestCompact(childTaskId = "after-terminal"))
        assertEquals(1, calls)
    }

    private fun readTerminalOnOtherThread(
        session: AgentRuntimeSession,
        failure: AtomicReference<Throwable?>,
    ): Boolean {
        val readFinished = CountDownLatch(1)
        val reader = Thread {
            try {
                session.isTerminal
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                readFinished.countDown()
            }
        }.apply { isDaemon = true; name = "compaction-session-lock-probe"; start() }
        val finished = readFinished.await(1_000, TimeUnit.MILLISECONDS)
        if (finished) reader.join(1_000)
        return finished
    }

    private fun round(number: Int) = AgentEvent.RoundStarted(number, number)
    private fun textDelta(text: String) = AgentEvent.AssistantBlockDelta(
        round = 1, kind = AgentEvent.AssistantBlockKind.TEXT,
        index = 0, deltaChars = text.length, delta = text,
    )
    private fun success() = AgentRuntimeWire.RunResult(RUN_ID, true, "done")
    private enum class Callback { LIVE, REPLAY, BOUNDARY }
    private companion object { const val RUN_ID = "dispatch-regression" }
}
