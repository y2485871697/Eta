package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.SubAgentCoordinator
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real coordinators and registry routing; only Android foreground leases are replaced by fixtures. */
class RetainedGenerationDispatchTest {
    @get:org.junit.Rule val timeout = org.junit.rules.Timeout.seconds(30)
    private val registry = AgentChildTaskGroups
    private val original = AgentModelClient.ModelConfig(
        baseUrl = "https://original.invalid", apiKey = "fixture-original", model = "original",
        providerId = "original-provider", systemPrompt = "original configuration",
    )
    private val edited = original.copy(baseUrl = "https://edited.invalid", apiKey = "fixture-edited",
        model = "edited", providerId = "edited-provider", systemPrompt = "edited configuration")
    private fun call(name: String, args: JSONObject = JSONObject()) = AgentModelClient.ToolCall("fixture", name, args.toString())
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var satisfied = check()
        while (!satisfied && System.nanoTime() < deadline) {
            Thread.sleep(5)
            satisfied = check()
        }
        assertTrue("condition did not become true", satisfied)
    }
    private fun candidate(owner: String, model: AgentModelClient.ModelConfig, worker: String = "worker-1") =
        ChildTaskConfigPolicy.Candidate(ChildTaskConfigPolicy.WorkerKey(owner, worker, "review"),
            ChildTaskConfigPolicy.Availability.AVAILABLE, model.model,
            ChildWorkerConfigResolver.Configuration(SubAgentProfile(worker, worker, "review",
                providerId = model.providerId, modelId = model.model), model))

    @Suppress("UNCHECKED_CAST")
    private fun groups() = registry.javaClass.getDeclaredField("groups").apply { isAccessible = true }
        .get(registry) as MutableMap<String, Any>
    private fun set(target: Any, name: String, value: Any?) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private inner class GroupFixture(val owner: String, val run: String,
        candidates: List<ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>>,
        execute: (AgentModelClient.ModelConfig, String, AgentRunController) -> String,
    ) : AutoCloseable {
        val generation = UUID.randomUUID().toString()
        // Same candidate mapping as RunExecutor.createChildGroup; no snapshot task/workspace copying.
        private val children = AgentChildWorkerAvailability.configuredChildren(candidates)
        val coordinator = SubAgentCoordinator(children.map { it.second }, roles = children.map { it.first.role },
            workerIds = children.map { it.first.id }, poolScope = generation, executeChild = execute)
        val group: Any
        init {
            val type = registry.javaClass.declaredClasses.single { it.simpleName == "Group" }
            val ctor = type.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
            group = ctor.newInstance(owner, run, generation, "fixture-lease", coordinator, null,
                AgentChildWorkerAvailability.workers(candidates), null)
            set(group, "leaseHeld", false)
            synchronized(registry) { groups()[generation] = group }
        }
        fun execute(name: String, args: JSONObject = JSONObject(), replacement: String? = generation) = JSONObject(
            registry.execute(owner, generation, call(name, args), currentRunId = run,
                replacementGeneration = replacement).content)
        fun delegate(args: JSONObject = JSONObject().put("task", "inspect")) = execute("delegate_task", args)
        fun get(id: String) = execute("get_task_result", JSONObject().put("task_id", id))
        fun target() = registry.captureRunStopTargets(run).single()
        override fun close() {
            // Remove first so a fixture's worker cleanup cannot race Android-only retirement code.
            synchronized(registry) { groups().remove(generation) }
            coordinator.close()
        }
    }

    @Test fun successFrozenHistoricalTaskDoesNotBlockFreshOrdinaryDispatchOrChangeItsIdentity() {
        val owner = UUID.randomUUID().toString()
        val oldRun = UUID.randomUUID().toString()
        val session = AgentRuntimeSession(oldRun)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldCalls = AtomicInteger()
        val frozen = candidate(owner, original)
        AgentChildRunControl.begin(session)
        try {
            GroupFixture(owner, oldRun, listOf(frozen)) { config, _, controller ->
                assertSame(original, config)
                oldCalls.incrementAndGet(); entered.countDown(); release.await()
                controller.throwIfCancelled(); "old result"
            }.use { old ->
                val id = old.delegate(JSONObject().put("task", "historical").put("project", "/workspace/fixture")).getString("task_id")
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                // Runtime-verified workspace DTO fixture: no backend/worktree is created or transferred.
                @Suppress("UNCHECKED_CAST")
                val tasks = field(old.coordinator, "tasks") as Map<String, Any>
                val task = requireNotNull(tasks[id])
                val workspace = "0123456789abcdef0123456789abcdef"
                set(task, "workspaceId", workspace)
                set(task, "workspacePath", "/workspace/fixture/isolated")
                set(task, "workspaceOwnershipVerified", true)
                AgentChildRunControl.terminate(session, AgentChildControlPolicy.Reason.SUCCESS)
                registry.detach(old.generation)
                await { old.get(id).optString("status") == "awaiting_decision" }
                assertTrue(AgentChildRunControl.pendingSelections.value.none { it.runId == oldRun })
                val before = old.get(id)
                val oldTarget = old.target()
                val plan = registry.ordinaryDispatchPlan(owner, listOf(candidate(owner, edited)))
                assertTrue(plan.retained)
                assertSame(frozen, plan.ordinary.single())
                assertSame(edited, plan.replacement.single().configuration!!.model)
                val newCalls = AtomicInteger()
                GroupFixture(owner, UUID.randomUUID().toString(), plan.ordinary) { config, _, _ ->
                    assertSame(original, config); newCalls.incrementAndGet(); "new result"
                }.use { current ->
                    val response = current.delegate(JSONObject().put("task", "fresh").put("task_id", id))
                    assertTrue(response.toString(), response.optBoolean("ok"))
                    val freshId = response.getString("task_id")
                    assertNotEquals(id, freshId)
                    assertTrue(current.coordinator.ownsTask(freshId))
                    assertFalse(old.coordinator.ownsTask(freshId))
                    await { current.get(freshId).optString("status") == "completed" }
                    assertEquals(1, newCalls.get())
                    val after = current.get(id)
                    listOf("task_id", "status", "model", "provider_id", "workspace_id", "workspace_path",
                        "continuation_count", "pause_source").forEach { assertEquals(it, before.get(it), after.get(it)) }
                    assertEquals(1, oldCalls.get())
                    assertEquals(listOf(id), old.coordinator.taskIds())
                    assertEquals(1, current.execute("get_task_result").getJSONArray("tasks").let { list ->
                        (0 until list.length()).count { list.getJSONObject(it).getString("task_id") == id }
                    })
                    assertTrue(after.isNull("successor_task_id"))
                    assertEquals("REPLACEMENT_NOT_ALLOWED", current.execute("delegate_task", JSONObject()
                        .put("task", "not authorized").put("replace_task_id", id)).getString("code"))
                    // A delayed old finally may pause its own group, never this run's coordinator.
                    registry.pause(oldTarget)
                    registry.detach(old.generation)
                    val another = current.delegate()
                    assertTrue(another.toString(), another.optBoolean("ok"))
                    assertEquals("awaiting_decision", current.get(id).getString("status"))
                }
            }
        } finally {
            release.countDown()
            AgentChildRunControl.finish(session)
        }
    }

    @Test fun currentPauseAndStopRejectNewTasksWithoutHistoricalFallbackAndOldCleanupIsRunScoped() {
        val owner = UUID.randomUUID().toString()
        val release = CountDownLatch(1)
        try {
            GroupFixture(owner, UUID.randomUUID().toString(), listOf(candidate(owner, original))) { _, _, controller ->
                release.await(); controller.throwIfCancelled(); "old"
            }.use { old ->
                val oldId = old.delegate().getString("task_id")
                registry.pause(old.target()); registry.detach(old.generation)
                await { old.get(oldId).optString("status") == "awaiting_decision" }
                val plan = registry.ordinaryDispatchPlan(owner, listOf(candidate(owner, edited)))
                GroupFixture(owner, UUID.randomUUID().toString(), plan.ordinary) { _, _, _ -> "new" }.use { current ->
                    val target = current.target()
                    registry.pause(target)
                    // The synchronous pause reservation fences admission before lifecycle execution.
                    assertEquals("TASK_GROUP_PAUSED", current.delegate().getString("code"))
                    registry.resume(target)
                    await { current.delegate().optBoolean("ok") }
                    val count = current.coordinator.taskIds().size
                    assertTrue(registry.stopRun(old.run))
                    assertTrue(current.delegate().optBoolean("ok"))
                    assertEquals(count + 1, current.coordinator.taskIds().size)
                    val archivedId = current.coordinator.taskIds().first()
                    assertTrue(registry.stop(target))
                    assertEquals("RUN_CLOSED", current.delegate().getString("code"))
                    // Exercise the other side of the stop/retire race deterministically:
                    // archived tasks remain readable, but ordinary admission stays closed.
                    await {
                        // The fixture has no Android task-change callback; a real result
                        // read also drives the registry's end/onTaskChanged retirement.
                        current.get(archivedId)
                        synchronized(registry) { field(current.group, "coordinator") == null }
                    }
                    assertEquals("RUN_CLOSED", current.delegate().getString("code"))
                    assertEquals(count + 1, current.coordinator.taskIds().size)
                    assertTrue(current.get(archivedId).optBoolean("archived"))
                    assertEquals("TASK_FINISHED", current.execute("continue_task",
                        JSONObject().put("task_id", archivedId)).getString("code"))
                }
            }
        } finally { release.countDown() }
    }

    @Test fun frozenOrdinaryIgnoresEditedUnavailableSelectionButDoesNotSubstituteAnotherWorker() {
        val owner = UUID.randomUUID().toString()
        val release = CountDownLatch(1)
        try {
            val frozen = candidate(owner, original)
            GroupFixture(owner, "old-$owner", listOf(frozen)) { _, _, controller ->
                release.await(); controller.throwIfCancelled(); "old"
            }.use { old ->
                val id = old.delegate().getString("task_id")
                registry.pause(old.target()); registry.detach(old.generation)
                await { old.get(id).optString("status") == "awaiting_decision" }
                val unavailable = ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>(
                    frozen.worker, ChildTaskConfigPolicy.Availability.MODEL_UNAVAILABLE)
                val currentCandidates = listOf(unavailable, candidate(owner, edited, "worker-2"))
                val plan = registry.ordinaryDispatchPlan(owner, currentCandidates)
                assertSame(frozen, plan.ordinary.single())
                assertSame(currentCandidates, plan.replacement)
                GroupFixture(owner, "new-$owner", plan.ordinary) { config, _, _ ->
                    assertSame(original, config); "frozen"
                }.use { current ->
                    assertEquals("AGENT_NOT_CONFIGURED", current.delegate(JSONObject().put("task", "no substitute")
                        .put("agent_id", "worker-2")).getString("code"))
                    assertTrue(current.coordinator.taskIds().isEmpty())
                    assertEquals("WORKER_ROLE_MISMATCH", current.delegate(JSONObject().put("task", "wrong role")
                        .put("agent_id", "worker-1").put("role", "implementation")).getString("code"))
                    assertTrue(current.coordinator.taskIds().isEmpty())
                    assertTrue(current.delegate(JSONObject().put("task", "original")
                        .put("agent_id", "worker-1")).optBoolean("ok"))
                    assertEquals("awaiting_decision", current.get(id).getString("status"))
                }
                // Incomplete historical state must not fall back to the edited setting.
                set(old.group, "workers", emptyList<AgentChildTaskGroups.Worker>())
                val missing = registry.ordinaryDispatchPlan(owner, currentCandidates)
                assertTrue(missing.retained)
                assertTrue(missing.ordinary.isEmpty())
                assertSame(currentCandidates, missing.replacement)
                val rejected = JSONObject(registry.execute(owner, null, call("delegate_task",
                    JSONObject().put("task", "must not fall back")), currentRunId = "next-$owner").content)
                assertEquals("RUN_CLOSED", rejected.getString("code"))
                assertEquals(listOf(id), old.coordinator.taskIds())
            }
        } finally { release.countDown() }
    }

    @Test fun finishedArchivedSnapshotDoesNotFreezeNewConfigurationAndUnavailableCurrentDoesNotFallBack() {
        val owner = UUID.randomUUID().toString()
        GroupFixture(owner, "old-$owner", listOf(candidate(owner, original))) { _, _, _ -> "finished" }.use { old ->
            val id = old.delegate().getString("task_id")
            await { old.get(id).optBoolean("execution_stopped") }
            registry.detach(old.generation)
            await { old.get(id).optBoolean("archived") }
            assertNull(field(old.group, "coordinator"))
            assertEquals("original", old.get(id).getString("model"))
            val latest = listOf(candidate(owner, edited))
            val plan = registry.ordinaryDispatchPlan(owner, latest)
            assertFalse(plan.retained)
            assertSame(latest, plan.ordinary)
            GroupFixture(owner, "new-$owner", plan.ordinary) { config, _, _ ->
                assertSame(edited, config); "new selection"
            }.use { current ->
                val fresh = current.delegate()
                assertTrue(fresh.toString(), fresh.optBoolean("ok"))
                val freshId = fresh.getString("task_id")
                await { current.get(freshId).optBoolean("execution_stopped") }
                assertEquals("edited", current.get(freshId).getString("model"))
                assertTrue(current.get(id).getBoolean("archived"))
            }
            val unavailable = listOf(ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>(
                frozenWorker(owner), ChildTaskConfigPolicy.Availability.MODEL_UNAVAILABLE))
            val disabled = registry.ordinaryDispatchPlan(owner, unavailable)
            assertFalse(disabled.retained)
            assertSame(unavailable, disabled.ordinary)
            assertTrue(AgentChildWorkerAvailability.configuredChildren(disabled.ordinary).isEmpty())
            val rejected = JSONObject(registry.execute(owner, null, call("delegate_task",
                JSONObject().put("task", "no archived fallback")), currentRunId = "disabled-$owner").content)
            assertEquals("RUN_CLOSED", rejected.getString("code"))
            assertEquals("original", old.get(id).getString("model"))
        }
    }

    private fun frozenWorker(owner: String) = ChildTaskConfigPolicy.WorkerKey(owner, "worker-1", "review")

    @Test fun explicitContinueAdoptsOnlyControlledOldTaskAndFencesOldRunCleanupFromBothGroups() {
        val owner = UUID.randomUUID().toString()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            GroupFixture(owner, "old-$owner", listOf(candidate(owner, original))) { config, _, controller ->
                assertSame(original, config); entered.countDown(); release.await(); controller.throwIfCancelled(); "old"
            }.use { old ->
                val first = old.delegate().getString("task_id")
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val second = old.delegate().getString("task_id")
                val staleTarget = old.target()
                registry.pause(staleTarget); registry.detach(old.generation)
                await { old.get(first).optString("status") == "awaiting_decision" &&
                    old.get(second).optString("status") == "awaiting_decision" }
                val plan = registry.ordinaryDispatchPlan(owner, listOf(candidate(owner, edited)))
                val nextRun = "new-$owner"
                GroupFixture(owner, nextRun, plan.ordinary) { _, _, _ -> "new" }.use { current ->
                    assertTrue(current.delegate().optBoolean("ok"))
                    val continued = current.execute("continue_task", JSONObject().put("task_id", first))
                    assertTrue(continued.toString(), continued.optBoolean("ok"))
                    assertEquals(first, continued.getString("task_id"))
                    assertEquals("original", continued.getString("model"))
                    assertEquals(1, continued.getInt("continuation_count"))
                    assertEquals("awaiting_decision", current.get(second).getString("status"))
                    assertFalse(registry.isCurrent(staleTarget))
                    registry.pause(staleTarget)
                    assertFalse(registry.stop(staleTarget))
                    assertFalse(registry.stopRun(old.run))
                    assertEquals("running", current.get(first).getString("status"))
                    assertTrue(current.delegate().optBoolean("ok"))
                    val targets = registry.captureRunStopTargets(nextRun).single()
                    assertEquals(setOf(old.generation, current.generation), targets.generations)
                    registry.pause(targets)
                    await { current.get(first).optString("status") == "awaiting_decision" }
                    assertEquals("TASK_GROUP_PAUSED", current.delegate().getString("code"))
                }
            }
        } finally { release.countDown() }
    }

    @Test fun explicitReplacementUsesCurrentGroupOnlyAfterStoppedReadEvidenceNotOrdinaryFrozenGroup() {
        val owner = UUID.randomUUID().toString()
        val frozen = candidate(owner, original)
        GroupFixture(owner, "old-$owner", listOf(frozen)) { _, _, _ -> error("child failed") }.use { old ->
            val id = old.delegate().getString("task_id")
            // Read directly from child, NOT the parent registry's handoff receipt.
            await { JSONObject(old.coordinator.execute(call("get_task_result", JSONObject().put("task_id", id))).content)
                .optBoolean("execution_exited") }
            val latest = listOf(candidate(owner, edited))
            val plan = registry.ordinaryDispatchPlan(owner, latest)
            val newRun = "new-$owner"
            GroupFixture(owner, newRun, plan.ordinary) { config, _, _ ->
                assertSame(original, config); "ordinary frozen"
            }.use { ordinary ->
                GroupFixture(owner, newRun, plan.replacement) { config, _, _ ->
                    assertSame(edited, config); "explicit successor"
                }.use { replacement ->
                    val fresh = ordinary.delegate()
                    assertTrue(fresh.optBoolean("ok"))
                    val args = JSONObject().put("task", "explicit successor").put("replace_task_id", id)
                    assertEquals("HANDOFF_NOT_READ", ordinary.execute("delegate_task", args,
                        replacement.generation).getString("code"))
                    val failed = ordinary.get(id)
                    assertEquals("failed", failed.getString("status"))
                    assertTrue(failed.getBoolean("can_replace"))
                    val unavailable = ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>(
                        frozen.worker, ChildTaskConfigPolicy.Availability.MODEL_UNAVAILABLE)
                    val alternative = listOf(unavailable, candidate(owner, edited, "worker-2"))
                    val unavailablePlan = registry.ordinaryDispatchPlan(owner, alternative)
                    GroupFixture(owner, newRun, unavailablePlan.replacement) { _, _, _ ->
                        error("unavailable original worker must not become another slot")
                    }.use { other ->
                        assertEquals("AGENT_NOT_CONFIGURED", ordinary.execute("delegate_task", args,
                            other.generation).getString("code"))
                        assertTrue(other.coordinator.taskIds().isEmpty())
                    }
                    val successor = ordinary.execute("delegate_task", args, replacement.generation)
                    assertTrue(successor.toString(), successor.optBoolean("ok"))
                    val successorId = successor.getString("task_id")
                    assertTrue(replacement.coordinator.ownsTask(successorId))
                    assertFalse(ordinary.coordinator.ownsTask(successorId))
                    assertEquals(id, successor.getString("replaces_task_id"))
                    assertEquals("SUCCESSOR_ALREADY_CLAIMED", ordinary.execute("delegate_task", args,
                        replacement.generation).getString("code"))
                    assertEquals("TASK_NOT_AWAITING_DECISION", ordinary.execute("continue_task",
                        JSONObject().put("task_id", id)).getString("code"))
                    assertEquals("original", ordinary.get(id).getString("model"))
                }
            }
        }
    }
}
