package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.SubAgentCoordinator
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Module A only. No Android service: fixtures install groups with no binding/foreground lease. */
class ChildGroupLifecycleTest {
    private val registry = AgentChildTaskGroups
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "original", systemPrompt = "original configuration")
    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("fixture", name, args.toString())
    private fun start(c: SubAgentCoordinator, text: String = "inspect") = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", text))).content).getString("task_id")
    private fun read(c: SubAgentCoordinator, id: String) = JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id))).content)
    private fun awaitCondition(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("condition did not become true", check())
    }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS))

    @Suppress("UNCHECKED_CAST")
    private fun groups() = registry.javaClass.getDeclaredField("groups").apply { isAccessible = true }.get(registry) as MutableMap<String, Any>
    private fun set(group: Any, name: String, value: Any?) = group.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(group, value)
    private fun field(group: Any, name: String): Any? = group.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(group)
    private inner class Fixture(
        val owner: String = UUID.randomUUID().toString(),
        val run: String = UUID.randomUUID().toString(),
        val generation: String = UUID.randomUUID().toString(),
        val coordinator: SubAgentCoordinator,
    ) : AutoCloseable {
        val released = CountDownLatch(1)
        val group: Any
        init {
            val type = registry.javaClass.declaredClasses.single { it.simpleName == "Group" }
            val ctor = type.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
            group = ctor.newInstance(owner, run, generation, "fixture-lease", coordinator, { released.countDown() }, emptyList<Any>(), null)
            set(group, "leaseHeld", false)
            synchronized(registry) { groups()[generation] = group }
        }
        fun target() = registry.captureRunStopTargets(run).single()
        fun get(id: String, field: String? = null) = JSONObject(registry.execute(owner, null, call("get_task_result", JSONObject().put("task_id", id).apply { field?.let { put("text_field", it) } })).content)
        override fun close() {
            coordinator.close()
            awaitCondition { !coordinator.hasActiveTasks() }
            synchronized(registry) { groups().remove(generation) }
        }
    }

    @Test fun queuedAndRunningPauseAreDistinctAndResumeOriginalExecution() {
        val started = CountDownLatch(1)
        val boundary = CountDownLatch(1)
        val calls = AtomicInteger()
        val controllers = mutableListOf<AgentRunController>()
        SubAgentCoordinator(listOf(model), modelParallelLimits = listOf(1)) { config, _, controller ->
            assertSame(model, config)
            synchronized(controllers) { controllers.add(controller) }
            if (calls.incrementAndGet() == 1) { started.countDown(); boundary.await() }
            controller.throwIfCancelled()
            "done"
        }.use { c ->
            val first = start(c)
            await(started)
            val second = start(c)
            c.pauseGroup()
            assertEquals("awaiting_decision", read(c, first).getString("status"))
            assertFalse(read(c, first).getBoolean("pause_confirmed"))
            assertTrue(read(c, second).getBoolean("pause_confirmed"))
            assertEquals(1, calls.get())
            boundary.countDown()
            awaitCondition { read(c, first).getBoolean("pause_confirmed") }
            c.resumeGroup()
            awaitCondition { read(c, second).getString("status") == "completed" }
            assertEquals("completed", read(c, first).getString("status"))
            assertEquals(2, calls.get())
            assertEquals(2, controllers.size)
            assertEquals(1, read(c, first).getInt("continuation_count"))
        }
    }

    @Test fun pendingPauseRequestFencesQueueBeforeLifecycleWorkerRuns() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val calls = AtomicInteger()
        SubAgentCoordinator(listOf(model)) { _, _, _ ->
            if (calls.incrementAndGet() == 1) { firstStarted.countDown(); releaseFirst.await() }
            "done"
        }.use { c ->
            start(c)
            await(firstStarted)
            val queued = start(c)
            c.reserveGroupPause()
            try {
                releaseFirst.countDown()
                Thread.sleep(100)
                assertEquals(1, calls.get())
                assertTrue(read(c, queued).getBoolean("pause_requested"))
                c.pauseGroup()
            } finally { c.finishGroupPauseRequest() }
            assertEquals("awaiting_decision", read(c, queued).getString("status"))
            c.resumeGroup()
            awaitCondition { read(c, queued).getString("status") == "completed" }
            assertEquals(2, calls.get())
        }
    }

    @Test fun groupResumeDoesNotResumeManualPauseAndCanPauseAgainAfterExplicitContinue() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        SubAgentCoordinator(listOf(model)) { _, _, controller ->
            entered.countDown(); release.await(); controller.throwIfCancelled(); "done"
        }.use { c ->
            val id = start(c)
            await(entered)
            c.execute(call("supervise_task", JSONObject().put("task_id", id).put("action", "pause")))
            c.pauseGroup(); c.resumeGroup()
            assertEquals("manual", read(c, id).getString("pause_source"))
            c.execute(call("continue_task", JSONObject().put("task_id", id)))
            c.pauseGroup()
            assertEquals("group", read(c, id).getString("pause_source"))
            c.execute(call("continue_task", JSONObject().put("task_id", id)))
            c.pauseGroup() // A later explicit group pause still freezes a continued historical task.
            assertEquals("awaiting_decision", read(c, id).getString("status"))
            c.resumeGroup(); release.countDown()
            awaitCondition { read(c, id).getString("status") == "completed" }
        }
    }

    @Test fun successfulParentEndAndDetachLetsRunningAndQueuedChildrenComplete() {
        val generation = UUID.randomUUID().toString()
        val run = UUID.randomUUID().toString()
        val session = AgentRuntimeSession(run)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val c = SubAgentCoordinator(listOf(model), modelParallelLimits = listOf(1),
            onTaskChanged = { registry.onTaskChanged(generation) }) { config, _, controller ->
            assertSame(model, config)
            if (calls.incrementAndGet() == 1) { entered.countDown(); release.await() }
            controller.throwIfCancelled()
            "same task result"
        }
        AgentChildRunControl.begin(session)
        Fixture(run = run, generation = generation, coordinator = c).use { f ->
            try {
                val running = start(c)
                await(entered)
                val queued = start(c)
                AgentChildRunControl.terminate(session, AgentChildControlPolicy.Reason.SUCCESS)
                registry.detach(generation)
                assertTrue(session.complete(AgentRuntimeWire.RunResult(runId = run, ok = true, content = "parent done")))
                assertTrue(session.isTerminal)
                assertTrue(session.terminalResult?.ok == true)
                assertTrue(session.controller.isCancelled)
                AgentChildRunControl.finish(session)
                assertEquals("running", f.get(running).getString("status"))
                assertFalse(f.get(running).getBoolean("pause_requested"))
                assertFalse(f.get(queued).getBoolean("pause_requested"))
                assertEquals(1, calls.get())
                assertTrue(registry.hasActive(f.owner))
                assertSame(c, field(f.group, "coordinator"))
                assertEquals(1L, f.released.count)
                assertTrue(AgentChildRunControl.pendingSelections.value.none { it.runId == run })
                release.countDown()
                awaitCondition { f.get(running).optBoolean("archived") && f.get(queued).optBoolean("archived") }
                for (id in listOf(running, queued)) {
                    val result = f.get(id)
                    assertEquals(id, result.getString("task_id"))
                    assertEquals("completed", result.getString("status"))
                    assertEquals("original", result.getString("model"))
                    assertEquals("same task result", result.getString("result"))
                    assertEquals(0, result.getInt("continuation_count"))
                }
                assertEquals(2, calls.get())
                await(f.released)
                assertNull(field(f.group, "coordinator"))
            } finally {
                release.countDown()
                AgentChildRunControl.finish(session)
            }
        }
    }

    @Test fun successfulParentEndDoesNotResumePreviouslyManuallyPausedChild() {
        val generation = UUID.randomUUID().toString()
        val run = UUID.randomUUID().toString()
        val session = AgentRuntimeSession(run)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val c = SubAgentCoordinator(listOf(model), onTaskChanged = { registry.onTaskChanged(generation) }) { _, _, controller ->
            entered.countDown(); release.await(); controller.throwIfCancelled(); "done"
        }
        AgentChildRunControl.begin(session)
        Fixture(run = run, generation = generation, coordinator = c).use { f ->
            try {
                val id = start(c)
                await(entered)
                c.execute(call("supervise_task", JSONObject().put("task_id", id).put("action", "pause")))
                AgentChildRunControl.terminate(session, AgentChildControlPolicy.Reason.SUCCESS)
                registry.detach(generation)
                assertTrue(session.complete(AgentRuntimeWire.RunResult(runId = run, ok = true, content = "parent done")))
                assertTrue(session.isTerminal)
                assertTrue(session.terminalResult?.ok == true)
                assertTrue(session.controller.isCancelled)
                AgentChildRunControl.finish(session)
                release.countDown()
                awaitCondition { f.get(id).optBoolean("pause_confirmed") }
                val paused = f.get(id)
                assertEquals("awaiting_decision", paused.getString("status"))
                assertEquals("manual", paused.getString("pause_source"))
                assertEquals(0, paused.getInt("continuation_count"))
                assertFalse(paused.optBoolean("archived"))
                assertSame(c, field(f.group, "coordinator"))
                assertEquals(1L, f.released.count)
                assertTrue(AgentChildRunControl.pendingSelections.value.none { it.runId == run })
            } finally {
                release.countDown()
                AgentChildRunControl.finish(session)
            }
        }
    }

    @Test fun explicitParentPauseThenDetachKeepsTaskAndNextRoundCanTakeOver() {
        val generation = UUID.randomUUID().toString()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val c = SubAgentCoordinator(listOf(model), onTaskChanged = { registry.onTaskChanged(generation) }) { _, _, controller ->
            entered.countDown(); release.await(); controller.throwIfCancelled(); "same task result"
        }
        Fixture(generation = generation, coordinator = c).use { f ->
            val id = start(c)
            await(entered)
            val oldTarget = f.target()
            registry.pause(oldTarget)
            registry.detach(generation)
            awaitCondition { f.get(id).optString("status") == "awaiting_decision" }
            assertFalse(f.get(id).optBoolean("archived"))
            assertSame(c, field(f.group, "coordinator"))
            assertEquals(1L, f.released.count)
            val nextRun = "next-${UUID.randomUUID()}"
            val continued = JSONObject(registry.execute(f.owner, null,
                call("continue_task", JSONObject().put("task_id", id)), currentRunId = nextRun).content)
            assertTrue(continued.getBoolean("ok"))
            assertTrue(registry.captureRunStopTargets(f.run).isEmpty())
            assertFalse(registry.isCurrent(oldTarget))
            registry.pause(oldTarget) // Old finally must not pause the same generation after takeover.
            assertFalse(registry.stop(oldTarget)) // Nor may an old dialog stop it.
            assertEquals("running", f.get(id).getString("status"))
            val newTarget = registry.captureRunStopTargets(nextRun).single()
            assertTrue(registry.isCurrent(newTarget))
            registry.pause(newTarget)
            awaitCondition { f.get(id).optString("status") == "awaiting_decision" }
            registry.resume(newTarget)
            release.countDown()
            awaitCondition { f.get(id).optBoolean("archived") }
            assertEquals(id, f.get(id).getString("task_id"))
            assertEquals("original", f.get(id).getString("model"))
            assertEquals("same task result", f.get(id).getString("result"))
            await(f.released)
        }
    }

    @Test fun stopRemainsValidAfterPauseAndArchivesOnlyAfterWorkerCleanup() {
        val generation = UUID.randomUUID().toString()
        val entered = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val calls = AtomicInteger()
        val c = SubAgentCoordinator(listOf(model), onTaskChanged = { registry.onTaskChanged(generation) },
            executeObservedChild = { _, _, controller, _, _, _, event ->
                calls.incrementAndGet()
                controller.reportTaskProgress("Verified source; next validate tests")
                event(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, 8, "verified"))
                event(AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.TEXT, 0, contentChars = 8))
                event(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 1, 7, "private"))
                entered.countDown()
                try { CountDownLatch(1).await() } catch (_: InterruptedException) { cleanup.countDown() }
                releaseCleanup.await()
                controller.throwIfCancelled()
                "unreachable"
            }, executeChild = { _, _, _ -> error("observed path expected") })
        Fixture(generation = generation, coordinator = c).use { f ->
            try {
                val id = start(c)
                await(entered)
                val queued = start(c)
                val target = f.target()
                registry.pause(target)
                awaitCondition { f.get(id).optString("status") == "awaiting_decision" }
                assertTrue(registry.isCurrent(target))
                assertTrue(registry.stop(target))
                await(cleanup)
                assertEquals("cancelled", f.get(id).getString("status"))
                assertFalse(f.get(id).getBoolean("execution_exited"))
                assertFalse(f.get(id).optBoolean("archived"))
                assertEquals(1L, f.released.count)
                assertEquals("RUN_CLOSED", JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "late"))).content).getString("code"))
                releaseCleanup.countDown()
                awaitCondition { f.get(id).optBoolean("archived") }
                val archived = f.get(id)
                assertEquals("cancelled", archived.getString("status"))
                assertEquals("", archived.getString("partial_result"))
                assertEquals("verified", f.get(id, "partial_result").getString("partial_result"))
                assertFalse(archived.toString().contains("private"))
                assertTrue(archived.getJSONObject("supervision").getString("checkpoint").contains("Verified"))
                assertTrue(archived.getBoolean("execution_exited"))
                assertFalse(archived.getBoolean("can_continue"))
                assertFalse(archived.getBoolean("can_replace"))
                assertEquals("cancelled", f.get(queued).getString("status"))
                assertEquals(1, calls.get())
                await(f.released)
            } finally { releaseCleanup.countDown() }
        }
    }

    @Test fun staleGenerationDoesNotTouchNewGroupAndTerminalResultsNeverBecomePaused() {
        val owner = UUID.randomUUID().toString()
        Fixture(owner = owner, coordinator = SubAgentCoordinator(listOf(model)) { _, _, _ -> "finished" }).use { old ->
            val completed = start(old.coordinator)
            awaitCondition { read(old.coordinator, completed).getString("status") == "completed" }
            val oldTarget = old.target()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            Fixture(owner = owner, coordinator = SubAgentCoordinator(listOf(model)) { _, _, controller ->
                entered.countDown(); release.await(); controller.throwIfCancelled(); "new"
            }).use { new ->
                try {
                    val active = start(new.coordinator)
                    await(entered)
                    registry.pause(oldTarget)
                    assertTrue(registry.stop(oldTarget))
                    awaitCondition { old.get(completed).optBoolean("archived") }
                    assertEquals("completed", old.get(completed).getString("status"))
                    assertEquals("finished", old.get(completed).getString("result"))
                    assertFalse(old.get(completed).getBoolean("pause_requested"))
                    assertEquals("running", new.get(active).getString("status"))
                } finally { release.countDown() }
            }
        }
    }

    @Test fun queuedOldPauseIsRevalidatedAfterSameGenerationTakeover() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(coordinator = SubAgentCoordinator(listOf(model)) { _, _, controller ->
            entered.countDown(); release.await(); controller.throwIfCancelled(); "done"
        }).use { f ->
            try {
                val id = start(f.coordinator)
                await(entered)
                f.coordinator.pauseGroup()
                val stale = f.target()
                // Hold lifecycle application at the same lock used by takeover; query reads do not change epochs.
                synchronized(field(f.group, "controlLock")!!) {
                    registry.pause(stale)
                    repeat(3) { f.get(id) }
                    assertTrue(registry.isCurrent(stale))
                    val result = registry.execute(f.owner, null,
                        call("continue_task", JSONObject().put("task_id", id)), currentRunId = "next-run")
                    assertTrue(JSONObject(result.content).getBoolean("ok"))
                }
                awaitCondition { synchronized(registry) { (field(f.group, "inFlight") as Int) == 0 } }
                assertEquals("running", f.get(id).getString("status"))
                assertFalse(registry.isCurrent(stale))
            } finally { release.countDown() }
        }
    }

    @Test fun mediaNeverClaimsPauseOrContinuationAndIsNeverRedispatched() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        SubAgentCoordinator(listOf(model), roles = listOf("image_generation"), executeImageChild = { config, _, _, _ ->
            assertSame(model, config); calls.incrementAndGet(); entered.countDown(); release.await(); "image receipt"
        }, executeChild = { _, _, _ -> error("not text") }).use { c ->
            val id = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "image").put("role", "image_generation"))).content).getString("task_id")
            await(entered)
            c.pauseGroup(); c.resumeGroup()
            val state = read(c, id)
            assertEquals("running", state.getString("status"))
            assertFalse(state.getBoolean("pause_supported"))
            assertFalse(state.getBoolean("pause_requested"))
            assertFalse(state.getBoolean("can_continue"))
            assertFalse(state.getBoolean("can_replace"))
            assertFalse(JSONObject(c.execute(call("continue_task", JSONObject().put("task_id", id))).content).getBoolean("ok"))
            release.countDown()
            awaitCondition { read(c, id).getString("status") == "completed" }
            assertEquals(1, calls.get())
        }
    }
}
