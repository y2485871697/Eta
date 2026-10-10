package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.agent.delegation.SubAgentCoordinator
import io.github.mangi.eta.agent.delegation.SubAgentResultPage
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local ownership. Finished generations become bounded detached result records. */
internal object AgentChildTaskGroups {
    // The two-argument constructor remains available; copy() must preserve, not refresh, an old token's epochs.
    data class StopTarget @JvmOverloads constructor(val ownerId: String, val generations: Set<String>,
        internal val controlEpochs: Map<String, Long> = captureControlEpochs(ownerId, generations))
    data class Worker(val id: String, val role: String, val providerId: String,
        val configuration: ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>? = null)
    private class Group(val ownerId: String, val runId: String, val generation: String, val leaseId: String,
        var coordinator: SubAgentCoordinator?, var releaseTools: (() -> Unit)?, val workers: List<Worker>,
        var binding: AgentRuntimeConnection.Lease?) {
        var attached = true
        var closed = false
        var retiring = false
        var stopping = false
        var inFlight = 0
        var operationVersion = 0L
        var controlEpoch = 0L
        var controlRunId = runId
        val controlLock = Any()
        val handoffs = ChildTaskHandoff()
        var leaseHeld = true
        var workspaceEnvironment: String? = null
        var snapshots: Map<String, String> = emptyMap()
        var snapshotBytes = 0L
    }
    private data class Claim(val predecessorGeneration: String, val successorGeneration: String, val successorId: String)
    private data class Lineage(val predecessorId: String, val predecessorGeneration: String, val successorGeneration: String)
    private const val MAX_ARCHIVED_GROUPS = 24
    private const val MAX_ARCHIVED_BYTES = 2 * 1024 * 1024
    private const val MAX_CHECKPOINT_CHARS = 1024
    private const val HANDOFF_WAIT_MS = 2000L
    private val groups = linkedMapOf<String, Group>()
    private fun captureControlEpochs(ownerId: String, generations: Set<String>): Map<String, Long> = synchronized(this) {
        generations.associateWith { generation -> groups[generation]?.takeIf { it.ownerId == ownerId }?.controlEpoch ?: -1L }
    }
    private val claimed = mutableMapOf<String, Claim>()
    private val replacedBy = mutableMapOf<String, Lineage>()
    private val lifecycle = Executors.newSingleThreadExecutor { work ->
        Thread(work, "child-group-lifecycle").apply { isDaemon = true }
    }
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes
    private fun changed() { changes.update { it + 1 } }

    fun register(context: Context, ownerId: String, runId: String, coordinator: SubAgentCoordinator,
        releaseTools: () -> Unit, workers: List<Worker> = emptyList(), workspaceEnvironment: String? = null): String? {
        val binding = AgentRuntimeConnection.acquire(context, AndroidAgentLogger) ?: return null
        val generation = UUID.randomUUID().toString()
        val leaseId = "child:$generation"
        val stopRequested = AtomicBoolean(false)
        if (!AgentExecutionService.acquire(context, leaseId, countsAsExecutingSession = false, onStop = {
            stopRequested.set(true)
            stopGeneration(generation)
        })) {
            binding.close()
            return null
        }
        try {
            synchronized(this) {
                groups[generation] = Group(ownerId, runId, generation, leaseId, coordinator, releaseTools, workers, binding)
                    .also { it.workspaceEnvironment = workspaceEnvironment }
                changed()
            }
        } catch (failure: Throwable) {
            AgentExecutionService.release(leaseId)
            binding.close()
            throw failure
        }
        if (stopRequested.get()) stopGeneration(generation)
        return synchronized(this) { generation.takeIf { groups[it]?.let { group -> !group.closed && !group.stopping } == true } }
    }
    fun hasActive(ownerId: String): Boolean = synchronized(this) {
        groups.values.filter { it.ownerId == ownerId && !it.closed }.mapNotNull { it.coordinator }
    }.any { it.hasActiveTasks() }
    fun hasAnyActive(): Boolean = synchronized(this) {
        groups.values.filter { !it.closed }.mapNotNull { it.coordinator }
    }.any { it.hasActiveTasks() }

    /** Read-only owner telemetry across live and retained generations; never emits handoff receipts or takes control. */
    fun contextStats(ownerId: String): List<SubAgentContextStats> {
        val sources = synchronized(this) {
            groups.values.filter { it.ownerId == ownerId && !it.closed }.map { group ->
                group.coordinator to group.snapshots.values.toList()
            }
        }
        val byTask = linkedMapOf<String, SubAgentContextStats>()
        sources.forEach { (coordinator, archived) ->
            coordinator?.contextStats()?.forEach { byTask[it.taskId] = it }
            archived.forEach { raw ->
                val stats = runCatching {
                    JSONObject(raw).optJSONObject("context_usage")?.let(SubAgentContextStats::fromJson)
                }.getOrNull()
                if (stats != null) byTask.putIfAbsent(stats.taskId, stats)
            }
        }
        return byTask.values.toList()
    }

    private fun controllable(group: Group) = !group.closed && !group.retiring && !group.stopping && group.coordinator != null
    private fun matches(target: StopTarget, group: Group) = group.ownerId == target.ownerId &&
        group.generation in target.generations && target.controlEpochs[group.generation] == group.controlEpoch
    fun isCurrent(target: StopTarget): Boolean = synchronized(this) {
        groups.values.any { controllable(it) && matches(target, it) }
    }
    fun captureStopTarget(ownerId: String): StopTarget? = synchronized(this) {
        groups.values.filter { it.ownerId == ownerId && controllable(it) }.mapTo(linkedSetOf()) { it.generation }
            .takeIf { it.isNotEmpty() }?.let { StopTarget(ownerId, it) }
    }
    fun captureRunStopTargets(runId: String): List<StopTarget> = synchronized(this) {
        groups.values.filter { controllable(it) && it.controlRunId == runId }.groupBy { it.ownerId }
            .map { (owner, owned) -> StopTarget(owner, owned.map { it.generation }.toSet()) }
    }
    fun captureActiveStopTargets(): List<StopTarget> {
        val current = synchronized(this) { groups.values.filter(::controllable).map { Triple(it, it.coordinator, it.controlEpoch) } }
        val active = current.filter { it.second?.hasActiveTasks() == true }
        return synchronized(this) {
            active.filter { (group, _, epoch) -> groups[group.generation] === group && controllable(group) && group.controlEpoch == epoch }
                .groupBy { it.first.ownerId }
                .map { (owner, entries) -> StopTarget(owner, entries.map { it.first.generation }.toSet()) }
        }
    }
    fun pause(target: StopTarget): Unit = control(target, true) { it.pauseGroup() }
    fun resume(target: StopTarget): Unit = control(target, false) { it.resumeGroup() }
    private fun control(target: StopTarget, pausing: Boolean, action: (SubAgentCoordinator) -> Unit) {
        synchronized(this) {
            groups.values.filter { matches(target, it) && controllable(it) }.forEach { group ->
                val coordinator = group.coordinator ?: return@forEach
                if (pausing) coordinator.reserveGroupPause()
                group.inFlight++; group.operationVersion++
                lifecycle.execute {
                    try {
                        synchronized(group.controlLock) {
                            val valid = synchronized(this) {
                                groups[group.generation] === group && matches(target, group) &&
                                    !group.closed && !group.stopping && group.coordinator === coordinator
                            }
                            if (valid) action(coordinator)
                        }
                    } finally {
                        if (pausing) coordinator.finishGroupPauseRequest()
                        end(group)
                    }
                }
            }
        }
    }
    fun stop(target: StopTarget): Boolean = stopMatching { matches(target, it) }
    fun stopAll() { stopMatching { true } }
    fun stopRun(runId: String): Boolean = stopMatching { it.controlRunId == runId }
    private fun stopGeneration(generation: String) { stopMatching { it.generation == generation } }
    private fun stopMatching(predicate: (Group) -> Boolean): Boolean = synchronized(this) {
        val selected = groups.values.filter { predicate(it) && controllable(it) }
        selected.forEach { group ->
            val coordinator = group.coordinator ?: return@forEach
            coordinator.preventNewTasks()
            group.stopping = true
            group.inFlight++; group.operationVersion++
            lifecycle.execute { try { coordinator.cancelAll() } finally { end(group) } }
        }
        if (selected.isNotEmpty()) changed()
        selected.isNotEmpty()
    }

    fun detach(generation: String) {
        synchronized(this) { groups[generation]?.also { it.attached = false; changed() } }
        onTaskChanged(generation)
        prune()
    }
    fun onTaskChanged(generation: String) {
        val probe = synchronized(this) {
            groups[generation]?.takeIf { !it.closed && !it.retiring }?.let { group ->
                group.coordinator?.let { Triple(group, it, group.operationVersion) }
            }
        } ?: return
        val (group, coordinator, version) = probe
        val active = coordinator.hasActiveTasks()
        val retire = synchronized(this) {
            if (groups[generation] !== group || group.closed || group.retiring || group.coordinator !== coordinator ||
                group.operationVersion != version) return@synchronized false
            changed()
            if ((!group.attached || group.stopping) && !active && group.inFlight == 0) {
                group.retiring = true
                (this as java.lang.Object).notifyAll()
                true
            } else false
        }
        if (retire) retire(group, coordinator)
    }
    private fun archiveSnapshot(json: JSONObject): String {
        val snapshot = JSONObject()
        listOf("ok", "task_id", "worker", "agent_id", "agent_name", "model", "model_display_name",
            "provider_id", "provider_name", "status", "role", "project", "error_code", "workspace_id",
            "workspace_path", "workspace_ownership_verified", "review_required", "can_continue", "can_replace", "replace_reason",
            "continuation_count", "parallel_limit", "successor_task_id", "replaces_task_id", "context_usage",
            "pause_supported", "pause_requested", "pause_confirmed", "pause_source", "execution_exited",
            "execution_stopped", "handoff_version", "text_revision", "text_evicted", "partial_result_truncated", "partial_result_unverified", "stopping", "allowed_actions", "next_step", "browser_access", "delivery_state", "artifact_verified", "artifact_evidence", "acceptance_verified").forEach { key -> if (json.has(key)) snapshot.put(key, json.get(key)) }
        listOf("result", "partial_result", "model_report_unverified").forEach { key ->
            snapshot.put(key, json.optString(key))
        }
        json.optJSONObject("supervision")?.let { evidence ->
            val supervision = JSONObject(evidence.toString())
            supervision.put("checkpoint", evidence.optString("checkpoint").take(MAX_CHECKPOINT_CHARS))
            snapshot.put("supervision", supervision)
        }
        snapshot.put("stopping", false).put("can_continue", false).put("archived", true)
        return snapshot.toString()
    }
    private fun retire(group: Group, coordinator: SubAgentCoordinator) {
        val snapshots = linkedMapOf<String, String>()
        try {
            coordinator.taskIds().forEach { id ->
                snapshots[id] = archiveSnapshot(group.handoffs.observe(coordinator.archiveRecord(id)))
            }
        } catch (_: Exception) {
            synchronized(this) { group.retiring = false; changed(); (this as java.lang.Object).notifyAll() }
            return
        }
        boundArchive(snapshots)
        group.handoffs.retainOnly(snapshots.keys)
        val binding: AgentRuntimeConnection.Lease?
        val release: (() -> Unit)?
        val held: Boolean
        synchronized(this) {
            if (groups[group.generation] !== group || group.closed || group.coordinator !== coordinator) return
            group.snapshots = snapshots
            group.snapshotBytes = snapshots.values.sumOf { it.length.toLong() * 2 }
            group.coordinator = null
            group.retiring = false
            binding = group.binding.also { group.binding = null }
            release = group.releaseTools.also { group.releaseTools = null }
            held = group.leaseHeld.also { group.leaseHeld = false }
            changed()
            (this as java.lang.Object).notifyAll()
        }
        try { coordinator.releaseExecutionResources() } finally {
            if (held) AgentExecutionService.release(group.leaseId)
            binding?.close()
            release?.let { runCatching { it() } }
            prune()
        }
    }
    private fun boundArchive(snapshots: LinkedHashMap<String, String>) {
        var bytes = snapshots.values.sumOf { it.length.toLong() * 2 }
        if (bytes <= MAX_ARCHIVED_BYTES) return
        snapshots.replaceAll { _, raw -> JSONObject(raw).apply {
            SubAgentResultPage.fields.forEach { put(it, "") }
            put("text_evicted", true)
        }.toString() }
        bytes = snapshots.values.sumOf { it.length.toLong() * 2 }
        // Metadata can also exceed the budget. Evict oldest tasks like old archived groups.
        val iterator = snapshots.entries.iterator()
        while (bytes > MAX_ARCHIVED_BYTES && iterator.hasNext()) {
            bytes -= iterator.next().value.length.toLong() * 2
            iterator.remove()
        }
    }

    private fun forgetGeneration(generation: String) {
        claimed.entries.removeAll { (_, claim) -> claim.predecessorGeneration == generation }
        replacedBy.entries.removeAll { (_, lineage) -> lineage.predecessorGeneration == generation || lineage.successorGeneration == generation }
    }
    private fun prune() {
        synchronized(this) {
            val archived = groups.values.filter { !it.attached && it.coordinator == null }
            var count = archived.size
            var bytes = archived.sumOf { it.snapshotBytes }
            archived.dropLast(1).forEach { old ->
                if (count > MAX_ARCHIVED_GROUPS || bytes > MAX_ARCHIVED_BYTES) {
                    groups.remove(old.generation)
                    old.closed = true
                    count--
                    bytes -= old.snapshotBytes
                    forgetGeneration(old.generation)
                    changed()
                    (this as java.lang.Object).notifyAll()
                }
            }
        }
    }
    private fun begin(group: Group): SubAgentCoordinator? = synchronized(this) {
        if (groups[group.generation] !== group || group.closed || group.retiring) null
        else group.coordinator?.also { group.inFlight++; group.operationVersion++ }
    }
    private fun end(group: Group) {
        synchronized(this) { group.inFlight--; group.operationVersion++; (this as java.lang.Object).notifyAll() }
        onTaskChanged(group.generation)
    }
    private fun ownerGroups(owner: String) = synchronized(this) { groups.values.filter { it.ownerId == owner && !it.closed }.toList() }
    private fun owns(group: Group, id: String): Boolean {
        val coordinator = synchronized(this) { if (id in group.snapshots) return true else group.coordinator }
        return coordinator?.ownsTask(id) == true
    }
    private fun observe(group: Group, response: AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        val json = runCatching { JSONObject(response.content) }.getOrNull() ?: return response
        return AgentModelClient.ToolResult(group.handoffs.observe(json).toString(), sensitive = true)
    }
    private fun archivedResult(raw: String, call: AgentModelClient.ToolCall): AgentModelClient.ToolResult = try {
        AgentModelClient.ToolResult(SubAgentResultPage.project(JSONObject(raw), JSONObject(call.argumentsJson)).toString(), sensitive = true)
    } catch (_: IllegalArgumentException) { error("INVALID_TASK_ARGUMENTS") }
      catch (_: org.json.JSONException) { error("INVALID_TASK_ARGUMENTS") }

    private fun result(group: Group, id: String, call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val deadline = System.nanoTime() + HANDOFF_WAIT_MS * 1_000_000
        while (true) {
            val snapshot = synchronized(this) { group.snapshots[id] }
            if (snapshot != null) return if (call.name == "get_task_result") archivedResult(snapshot, call) else error("TASK_FINISHED")
            if (call.name != "get_task_result" && synchronized(this) { group.stopping }) return error("RUN_CLOSED")
            val coordinator = begin(group)
            if (coordinator != null) return try { observe(group, coordinator.execute(call)) } finally { end(group) }
            val waiting = synchronized(this) {
                if (group.snapshots[id] != null) false
                else if (groups[group.generation] !== group || group.closed || !group.retiring || group.coordinator == null) false
                else {
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining > 0) (this as java.lang.Object).wait(remaining.coerceAtLeast(1))
                    true
                }
            }
            if (!waiting) {
                val archived = synchronized(this) { group.snapshots[id] }
                return if (archived != null && call.name == "get_task_result") archivedResult(archived, call)
                    else error(if (synchronized(this) { group.retiring && !group.closed }) "TASK_RESULT_PENDING" else if (archived != null) "TASK_FINISHED" else "TASK_NOT_FOUND")
            }
            if (System.nanoTime() >= deadline) return error("TASK_RESULT_PENDING")
        }
    }
    fun ownedWorkspaceIds(ownerId: String, project: String, environment: String? = null): Set<String> {
        if (project.isBlank()) return emptySet()
        val ids = linkedSetOf<String>()
        for (group in ownerGroups(ownerId)) {
            if (environment != null && group.workspaceEnvironment != environment) continue
            synchronized(this) { group.snapshots.values.toList() }.forEach { workspaceIdFor(it, project)?.let(ids::add) }
            val coordinator = begin(group)
            if (coordinator == null) {
                val handedOff = synchronized(this) {
                    if (groups[group.generation] === group && group.retiring && !group.closed && group.coordinator != null) (this as java.lang.Object).wait(HANDOFF_WAIT_MS)
                    group.snapshots.values.toList()
                }
                handedOff.forEach { workspaceIdFor(it, project)?.let(ids::add) }
                continue
            }
            try {
                for (id in coordinator.taskIds()) {
                    val dto = coordinator.execute(AgentModelClient.ToolCall("workspace-check-$id", "get_task_result",
                        JSONObject().put("task_id", id).toString())).content
                    workspaceIdFor(dto, project)?.let(ids::add)
                }
            } finally { end(group) }
        }
        return ids
    }
    fun ownsWorkspace(ownerId: String, project: String, workspaceId: String?): Boolean {
        val ids = ownedWorkspaceIds(ownerId, project)
        return if (workspaceId == null) ids.isNotEmpty() else workspaceId in ids
    }
    private val workspaceIdPattern = Regex("[0-9a-f]{32}")
    private fun workspaceIdFor(dto: String, project: String): String? {
        val json = runCatching { JSONObject(dto) }.getOrNull() ?: return null
        if (!json.optBoolean("ok", true) || json.optString("project") != project) return null
        if (json.opt("workspace_ownership_verified") != true) return null
        return (json.opt("workspace_id") as? String)?.takeIf(workspaceIdPattern::matches)
    }

    /** The newest retained generation is the source of ordinary configuration snapshots. */
    fun retainedOrdinaryCandidates(ownerId: String): List<ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>>? =
        synchronized(this) {
            groups.values.filter { it.ownerId == ownerId && !it.closed }
                .filter { group -> group.snapshots.isNotEmpty() || group.coordinator?.taskIds()?.isNotEmpty() == true }
                .lastOrNull()?.workers?.mapNotNull { it.configuration }
        }

    fun hasRetainedTasks(ownerId: String): Boolean = synchronized(this) {
        groups.values.any { group ->
            group.ownerId == ownerId && !group.closed &&
                (group.snapshots.isNotEmpty() || group.coordinator?.taskIds()?.isNotEmpty() == true)
        }
    }

    /**
     * Capture configuration, not execution ownership, before constructing this run's groups.
     * A live retained coordinator can be running or explicitly paused by prior controls.
     * Its immutable candidates still govern ordinary dispatch, but new work must NOT execute
     * through that historical coordinator. Archived result-only groups do not constrain new work.
     * Missing frozen candidates fail closed; the current setting is only for explicit replacement.
     */
    fun ordinaryDispatchPlan(ownerId: String,
        current: List<ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>>,
    ): ChildTaskOrdinaryDispatchSelection.Plan<ChildWorkerConfigResolver.Configuration> = synchronized(this) {
        val retained = groups.values.lastOrNull { group ->
            group.ownerId == ownerId && !group.closed && group.coordinator?.taskIds()?.isNotEmpty() == true
        }
        ChildTaskOrdinaryDispatchSelection.plan(current, retained?.workers?.mapNotNull { it.configuration },
            retainedTasks = retained != null)
    }

    fun execute(ownerId: String, currentGeneration: String?, call: AgentModelClient.ToolCall,
        currentRunId: String? = null, replacementGeneration: String? = currentGeneration): AgentModelClient.ToolResult {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return error("INVALID_TASK_ARGUMENTS")
        // delegate_task never controls an existing task merely because task_id was supplied.
        val id = if (call.name == "delegate_task") "" else args.optString("task_id")
        if (call.name == "get_task_result" && id.isBlank()) return list(ownerId, args)
        val candidates = ownerGroups(ownerId)
        val wantsReplacement = call.name == "delegate_task" && args.optString("replace_task_id").isNotBlank()
        val selectedGeneration = if (wantsReplacement) replacementGeneration else currentGeneration
        val current = candidates.firstOrNull { it.generation == selectedGeneration && it.attached &&
            (currentRunId == null || it.runId == currentRunId) }
        if (wantsReplacement) return replace(candidates, current, call, args)
        // New dispatch is run-local. The construction entry point supplies frozen ordinary
        // candidates to this run's coordinator; historical coordinators remain task_id controls.
        // Never resume, adopt or fall back to a retained group just to create a new task.
        val group = if (id.isNotBlank()) candidates.firstOrNull { owns(it, id) } else current
        if (group == null) return error(if (id.isNotBlank()) "TASK_NOT_FOUND" else "RUN_CLOSED")
        val admissionError = synchronized(this) {
            // Ordinary dispatch belongs to this run, not an archived task. Keep the
            // stop fence stable before and after asynchronous coordinator retirement.
            if (call.name == "delegate_task" && group.stopping) "RUN_CLOSED"
            else if (group.coordinator == null && !group.retiring && call.name != "get_task_result") "TASK_FINISHED"
            else null
        }
        if (admissionError != null) return error(admissionError)
        val response = if (call.name == "continue_task") continueOwned(group, currentRunId ?: current?.runId, call) else result(group, id, call)
        val json = runCatching { JSONObject(response.content) }.getOrNull() ?: return response
        if (call.name == "get_task_result" && id.isNotBlank()) group.handoffs.recordRead(json)
        val taskId = id.ifBlank { json.optString("task_id") }
        synchronized(this) { replacedBy[taskId]?.predecessorId }?.let { json.put("replaces_task_id", it) }
        return AgentModelClient.ToolResult(json.toString(), sensitive = true)
    }
    private fun continueOwned(group: Group, runId: String?, call: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
        synchronized(group.controlLock) control@ {
            var oldEpoch = 0L
            var oldRun = ""
            val coordinator = synchronized(this) reservation@ {
                if (groups[group.generation] !== group || !controllable(group)) return@reservation null
                oldEpoch = group.controlEpoch
                oldRun = group.controlRunId
                group.controlEpoch++
                if (runId != null) group.controlRunId = runId
                group.inFlight++; group.operationVersion++
                group.coordinator
            } ?: return@control error("RUN_CLOSED")
            var continued = false
            try {
                val response = observe(group, coordinator.execute(call))
                continued = runCatching { JSONObject(response.content).optBoolean("ok") }.getOrDefault(false)
                response
            } finally {
                synchronized(this) {
                    if (!continued && !group.stopping && group.controlEpoch == oldEpoch + 1) {
                        group.controlEpoch = oldEpoch
                        group.controlRunId = oldRun
                    }
                    changed()
                }
                end(group)
            }
        }
    private fun replace(candidates: List<Group>, current: Group?, call: AgentModelClient.ToolCall,
        args: JSONObject): AgentModelClient.ToolResult {
        if (current == null || synchronized(this) { current.stopping }) return error("RUN_CLOSED")
        val predecessorId = args.optString("replace_task_id")
        val old = candidates.firstOrNull { owns(it, predecessorId) } ?: return error("TASK_NOT_FOUND")
        val snapshot = runCatching { JSONObject(result(old, predecessorId, AgentModelClient.ToolCall(
            "replacement-check", "get_task_result", JSONObject().put("task_id", predecessorId).toString())).content) }
            .getOrNull() ?: return error("REPLACEMENT_EVIDENCE_UNAVAILABLE")
        val role = snapshot.optString("role")
        val workspaceId = (snapshot.opt("workspace_id") as? String)?.takeIf { it.isNotBlank() }
        val workspaceBound = role == "implementation" || workspaceId != null || snapshot.optString("workspace_path").isNotBlank()
        // A failed isolated worktree is not "just another failed task": this run never replaces an
        // implementation/workspace task, so name the accurate inspect/discard/relaunch path instead.
        if (workspaceBound && snapshot.optString("status") == "failed") {
            return error("WORKSPACE_HANDOFF_REQUIRES_MANUAL_REVIEW") {
                put("workspace_id", workspaceId ?: JSONObject.NULL)
                put("can_replace", false)
                put("allowed_actions", snapshot.optJSONArray("allowed_actions") ?: JSONArray(listOf("get_task_result")))
                put("next_step", snapshot.optString("next_step").ifBlank {
                    "带工作区的任务不能替换；先用 get_task_result 确认 execution_stopped，再 inspect 核验工作树。"
                })
            }
        }
        if (!ChildTaskReplacementSelection.eligibleStatus(snapshot) || !snapshot.optBoolean("can_replace")) {
            return error("REPLACEMENT_NOT_ALLOWED") {
                put("can_replace", false)
                put("status", snapshot.optString("status"))
                put("allowed_actions", snapshot.optJSONArray("allowed_actions") ?: JSONArray(listOf("get_task_result")))
                put("next_step", snapshot.optString("next_step").ifBlank {
                    "仅 failed 或 SUB_AGENT_NO_PROGRESS 的 awaiting_decision 任务，在 can_replace=true 且 execution_stopped=true 后可显式替换；先读取实际状态，不重放任务。"
                })
            }
        }
        if (!snapshot.optBoolean("execution_stopped") || old.coordinator?.hasActiveTasks() == true) return error("REPLACE_PENDING_STOP")
        if (role in setOf("image_generation", "video_generation")) return error("MEDIA_DELIVERY_UNCERTAIN")
        if (workspaceBound) return error("WORKSPACE_HANDOFF_REQUIRES_MANUAL_REVIEW") { put("workspace_id", workspaceId ?: JSONObject.NULL) }
        if (args.has("role") && args.optString("role") != role) return error("REPLACEMENT_ROLE_MISMATCH")
        // 失败的任务已经停了，没法再要检查点；没报过检查点就让接替的任务从头做。
        val checkpoint = snapshot.optJSONObject("supervision")?.optString("checkpoint").orEmpty()
        val handoffVersion = snapshot.optLong("handoff_version", -1)
        if (!old.handoffs.matchesRead(predecessorId, handoffVersion)) return error("HANDOFF_NOT_READ")
        val selection = ChildTaskReplacementSelection.choose(current.ownerId, old.generation, old.workers,
            current.workers, snapshot, args, old.handoffs.readVersion(predecessorId),
            synchronized(this) { claimed.containsKey(predecessorId) })
        selection.error?.let { return error(it) }
        val chosen = current.workers[selection.index ?: return error("AGENT_NOT_CONFIGURED")]
        val provider = snapshot.optString("provider_id")
        val originalContext = args.optString("context")
        val previous = "\n\nPrevious failed task $predecessorId (provider $provider, model ${snapshot.optString("model")}) "
        val evidence = if (checkpoint.isBlank()) {
            previous + "reported no checkpoint; its progress is unknown. Do the whole task from the start. " +
                "Never replay uncertain external side effects."
        } else {
            previous + "reported this unverified checkpoint: $checkpoint. Continue only unfinished work; " +
                "verify the checkpoint independently. Never replay uncertain external side effects."
        }
        if (originalContext.length + evidence.length > 20000) return error("INVALID_TASK_ARGUMENTS")
        val next = JSONObject(args.toString()).apply {
            if (old !== current) remove("replace_task_id")
            put("role", role).put("agent_id", chosen.id).put("context", originalContext + evidence)
        }
        val coordinator = begin(current) ?: return error("RUN_CLOSED")
        val pending = Claim(old.generation, current.generation, "pending")
        val reserved = synchronized(this) {
            if (groups[old.generation] !== old || old.closed || groups[current.generation] !== current || current.closed || current.stopping ||
                claimed.containsKey(predecessorId) || !old.handoffs.matchesRead(predecessorId, handoffVersion)) false
            else { claimed[predecessorId] = pending; true }
        }
        if (!reserved) { end(current); return error("REPLACEMENT_ALREADY_CLAIMED") }
        var dispatchStarted = false
        var definitive = false
        try {
            if (old.coordinator?.hasActiveTasks() == true) return error("REPLACE_PENDING_STOP")
            dispatchStarted = true
            val response = coordinator.execute(AgentModelClient.ToolCall(call.id, call.name, next.toString()))
            val payload = runCatching { JSONObject(response.content) }.getOrNull() ?: return error("REPLACEMENT_DISPATCH_UNKNOWN")
            if (payload.optBoolean("ok") && payload.optString("task_id").isNotBlank()) {
                synchronized(this) {
                    if (groups[old.generation] !== old || old.closed || groups[current.generation] !== current ||
                        current.closed || claimed[predecessorId] != pending) return@synchronized
                    val successor = payload.getString("task_id")
                    claimed[predecessorId] = pending.copy(successorId = successor)
                    replacedBy[successor] = Lineage(predecessorId, old.generation, current.generation)
                    definitive = true
                }
                if (!definitive) return error("REPLACEMENT_DISPATCH_UNKNOWN")
                payload.put("replaces_task_id", predecessorId).put("replacement_checkpoint_unverified", true)
            } else if (!payload.optBoolean("ok")) definitive = true
            else return error("REPLACEMENT_DISPATCH_UNKNOWN")
            return AgentModelClient.ToolResult(payload.toString(), sensitive = true)
        } catch (_: Exception) {
            return error(if (dispatchStarted) "REPLACEMENT_DISPATCH_UNKNOWN" else "REPLACE_PENDING_STOP")
        } finally {
            synchronized(this) {
                if (claimed[predecessorId] == pending) {
                    if (!dispatchStarted || definitive) claimed.remove(predecessorId)
                    else claimed[predecessorId] = pending.copy(successorId = "unknown")
                }
            }
            end(current)
        }
    }
    fun requestCompact(ownerId: String, id: String, keep: Int?, model: AgentModelClient.ModelConfig?): Boolean {
        val group = ownerGroups(ownerId).firstOrNull { owns(it, id) } ?: return false
        val coordinator = begin(group) ?: return false
        return try { coordinator.requestCompact(id, keep, model) } finally { end(group) }
    }
    private fun list(ownerId: String, args: JSONObject): AgentModelClient.ToolResult {
        val offset = args.optInt("offset", 0).coerceIn(0, 10_000)
        val groups = ownerGroups(ownerId)
        val ids = groups.flatMap { group -> synchronized(this) { group.snapshots.keys.toList() } +
            (group.coordinator?.taskIds() ?: emptyList()) }.distinct().take(10_000)
        val page = JSONArray()
        for (id in ids.drop(offset).take(20)) {
            val group = groups.firstOrNull { owns(it, id) } ?: continue
            val json = runCatching { JSONObject(result(group, id, AgentModelClient.ToolCall(
                "list-$id", "get_task_result", JSONObject().put("task_id", id).toString())).content) }.getOrNull() ?: continue
            val item = JSONObject().put("task_id", id).put("status", json.optString("status"))
                .put("role", json.optString("role")).put("agent_id", json.optString("agent_id"))
            listOf("pause_requested", "pause_confirmed", "pause_source", "pause_supported", "can_continue", "archived", "stopping", "execution_exited", "execution_stopped", "delivery_state", "artifact_verified", "acceptance_verified", "error_code", "workspace_id", "review_required", "allowed_actions", "next_step")
                .forEach { key -> if (json.has(key)) item.put(key, json.get(key)) }
            synchronized(this) { replacedBy[id]?.predecessorId }?.let { item.put("replaces_task_id", it) }
            page.put(item)
        }
        return AgentModelClient.ToolResult(JSONObject().put("ok", true).put("tasks", page)
            .put("total", ids.size).put("next_offset", if (offset + page.length() < ids.size) offset + page.length() else JSONObject.NULL)
            .toString(), sensitive = true)
    }
    private fun error(code: String, details: JSONObject.() -> Unit = {}) = AgentModelClient.ToolResult(
        io.github.mangi.eta.agent.delegation.SubAgentErrorHints.annotate(
            JSONObject().put("ok", false).put("code", code).apply(details)).also { json ->
            // 替换策略的拒绝码自带英文原因，没有中文说明时用它。
            if (json.optString("message").isBlank()) {
                ChildTaskConfigPolicy.Code.entries.firstOrNull { it.name == code }?.let { json.put("message", it.reason) }
            }
        }.toString(),
        sensitive = true,
    )
}
