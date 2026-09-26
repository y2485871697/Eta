package io.github.mangi.eta.agent.device

import android.content.Context
import android.content.SharedPreferences
import android.content.ClipData
import android.content.ClipboardManager
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray
import org.json.JSONObject

/** Single-device owner. Recovery errors never authorize a second display. */
internal object VirtualDisplaySession {
    const val NOT_READY = "VIRTUAL_DISPLAY_HANDOFF_NOT_READY"
    private class Session(var client: VirtualDisplayOwnerClient? = null, var phase: String = "starting") {
        val kept = linkedSetOf<Int>()
        val packages = linkedMapOf<String, Set<Int>>()
        val observation = VirtualDisplayObservation()
        val previewExcludedPackages = linkedSetOf<String>()
        var closedRun = false
        var cleanupOnly = false
        var persisted = false
        var receipt: JSONObject? = null
        /** Shared across finish/onRunClosed for this run so automatic retries cannot multiply. */
        val handoffBudget = VirtualDisplayHandoffRetry.Budget()
        // Retain the pre-attempt baseline across reentry/adoption; never replace it with a new owner.
        var handoffState: VirtualDisplayHandoffRetry.OwnerState? = null
        var handoffSelection: Set<Int>? = null
        // Bounded history survives a held-session adoption; includes successful later attempts.
        val handoffAttempts = JSONArray()
        var handoffDiagnostics: VirtualDisplayHandoffDiagnostics.Failure? = null
    }
    private val sessions = linkedMapOf<String, Session>()
    private var recoveryContext: Context? = null
    private const val RECOVERY_PREFS = "virtual_display_owner_recovery"
    private fun recoveryPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
    private fun bootId(): String? = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
            .takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) }
    }.getOrNull()
    private fun reply(ok: Boolean, code: String = "", detail: String = "") =
        JSONObject().put("ok",ok).put("error",code).put("message",detail)
    private fun body(response: OwnerResponse): JSONObject = response.json ?: reply(false,response.errorCode)
    private fun ids(value: JSONArray?): Set<Int>? = value?.let {
        VirtualDisplayRecoveryPolicy.taskIds((0 until it.length()).map(it::opt))
    }
    private fun flags(state: JSONObject): VirtualDisplayRecoveryPolicy.Flags? {
        val values = listOf("finishing", "handoffComplete", "releaseAttempted", "mutationUncertain", "sourceEmpty")
            .map { state.opt(it) }
        if (values.any { it !is Boolean }) return null
        return VirtualDisplayRecoveryPolicy.Flags(values[0] as Boolean, values[1] as Boolean,
            values[2] as Boolean, values[3] as Boolean, values[4] as Boolean)
    }
    private fun handoffState(c: VirtualDisplayOwnerClient, response: OwnerResponse): VirtualDisplayHandoffRetry.OwnerState? {
        val data = response.json ?: return null
        return VirtualDisplayHandoffEvidence.state(
            identity = VirtualDisplayHandoffRetry.OwnerIdentity(c.socketName, c.ownerPid, c.displayId, c.uniqueId),
            authenticatedConnection = c.isAlive,
            statusOk = response.ok && response.op == VirtualDisplayOwnerProtocol.OP_STATUS,
            flags = flags(data),
            retainedTaskIds = ids(data.optJSONArray("retainedTaskIds")),
            field = data::opt,
        )
    }
    private fun freshHandoffState(c: VirtualDisplayOwnerClient): VirtualDisplayHandoffRetry.OwnerState? =
        try { handoffState(c, c.status()) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
        catch (_: Exception) { null }

    private fun fail(s: Session, code: String, detail: String = ""): JSONObject {
        s.handoffBudget.stop()
        s.phase = "uncertain"
        return VirtualDisplayHandoffDiagnostics.annotate(
            reply(false, code, detail).withHandoffAttempts(s), s.handoffDiagnostics, uncertain = true,
        ).also { s.receipt = it }
    }
    /**
     * finish 可能被重入。首次失败往往携带具体诊断（例如 owner 的 HANDOFF_PREFLIGHT_FAILED）；
     * 之后的重入不得把这份诊断降级成笼统的 RECOVERY_UNCERTAIN。
     */
    private fun failPreservingPrior(s: Session, code: String): JSONObject {
        s.handoffBudget.stop()
        val prior = s.receipt
        val priorCode = prior?.takeIf { !it.optBoolean("ok") }?.optString("error").orEmpty()
        if (code in setOf("RECOVERY_UNCERTAIN", "OWNER_STATE_UNKNOWN") && priorCode.isNotBlank()) {
            s.phase = "uncertain"
            return VirtualDisplayHandoffDiagnostics.preservedReceipt(prior!!)
        }
        return fail(s, code)
    }
    /** 只转发无歧义的安全细节；绝不回显 token、Intent 转储或数据 URI。 */
    private fun safeOwnerDetail(response: OwnerResponse): String {
        val raw = response.json?.opt("message") as? String ?: return ""
        val collapsed = raw.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
        if (collapsed.isEmpty() || collapsed.length > 180) return ""
        // Only the symbolic handoff phase/type and integer task tallies are allowed.
        if (!collapsed.matches(Regex("[A-Za-z0-9_:#.\\-]+:[A-Za-z0-9_$]+; ?moved=\\[[0-9, ]*\\] removed=\\[[0-9, ]*\\]"))) return ""
        return collapsed
    }
    private fun JSONObject.withHandoffAttempts(s: Session): JSONObject = apply {
        if (s.handoffAttempts.length() > 0) {
            put("handoffAttempts", JSONArray(s.handoffAttempts.toString()))
        }
    }

    private fun recordHandoff(s: Session, response: OwnerResponse, completed: Boolean) {
        val diagnostics = VirtualDisplayHandoffDiagnostics.sanitizeFailure(response.json)
        s.handoffDiagnostics = diagnostics
        val rawSamples = response.json?.optJSONArray("focusSamples")
        val samples = JSONArray()
        if (rawSamples != null) for (i in 0 until minOf(rawSamples.length(), 4)) {
            val token = rawSamples.opt(i) as? String ?: continue
            if (token.length <= 80 && token.matches(Regex("OK|Focus_[A-Z_]+"))) samples.put(token)
        }
        if (s.handoffAttempts.length() >= 6) s.handoffAttempts.remove(0)
        s.handoffAttempts.put(VirtualDisplayHandoffDiagnostics.annotate(JSONObject()
            .put("ok", completed)
            .put("error", if (completed) "" else VirtualDisplayHandoffDiagnostics.safeHandoffError(response.errorCode))
            .put("message", if (completed) "" else safeOwnerDetail(response))
            .put("focusSamples", samples), diagnostics))
    }

    private fun clearReleased(context: Context?, s: Session): JSONObject {
        s.phase = "finished"
        s.client?.close()
        val cleared = context != null && runCatching {
            recoveryPrefs(context).edit().clear().commit()
        }.getOrDefault(false)
        return if (cleared) reply(true).put("released", true)
        else reply(false, "RECOVERY_RECORD_CLEAR_FAILED").put("released", true)
    }
    @Synchronized fun start(context: Context, runId: String, createIfMissing: Boolean = true): JSONObject {
        recoveryContext = context.applicationContext
        if (runId.isBlank()) return reply(false, "RUN_ID_REQUIRED")
        sessions[runId]?.let { s ->
            if (s.phase == "finished") return reply(false, "SESSION_FINISHED")
            // A cancelled run can still select delivery tasks and finish through the owner.
            // Do not restore GUI access or reset this run's automatic retry budget.
            if (VirtualDisplayRecoveryPolicy.canRecoverExistingRun(s.phase, s.closedRun)) {
                s.cleanupOnly = true
                if (s.kept.isEmpty()) s.packages.values.forEach { s.kept.addAll(it) }
                return reply(true).put("recovered", true).put("cleanup_only", true).put("phase", s.phase)
            }
            return if (s.phase == "active") reply(true).put("phase", s.phase)
            else reply(false, "RECOVERY_REQUIRED").put("phase", s.phase)
        }
        val others = sessions.values.filter { it.phase != "finished" }
        if (others.any { !it.closedRun } || others.size > 1) return reply(false, "VIRTUAL_SESSION_BUSY")
        if (others.size == 1) {
            val s = others.single()
            sessions.entries.removeAll { it.value === s }
            s.cleanupOnly = true
            if (s.kept.isEmpty()) s.packages.values.forEach { s.kept.addAll(it) }
            sessions[runId] = s
            // A deliberate later run gets a fresh clean-rejection budget, never mutation replay.
            s.handoffBudget.reset()
            return reply(true).put("recovered", true).put("cleanup_only", true).put("phase", s.phase)
        }
        var recoveryStage = "record_read"
        val saved = try { recoveryPrefs(context) } catch (ex: Exception) {
            val e = VirtualDisplayRecoveryException.classify(recoveryStage, ex)
            return reply(false, e.code).put("recovery_stage", e.stage)
        }
        if (!createIfMissing && runCatching { saved.all.isEmpty() }.getOrDefault(false)) return reply(false, "NO_VIRTUAL_SESSION")
        val s = Session()
        sessions[runId] = s
        val boot = bootId() ?: return fail(s, "BOOT_ID_UNAVAILABLE")
        try {
            val fields = saved.all
            if (fields.isNotEmpty() && VirtualDisplayRecoveryRecord.previousBoot(fields["boot"], boot)) {
                recoveryStage = "record_write"
                if (!saved.edit().clear().commit()) return fail(s, "RECOVERY_STATE_UNWRITABLE")
            } else if (fields.isNotEmpty()) {
                recoveryStage = "record_decode"
                val record = VirtualDisplayRecoveryRecord.decode(fields)
                if (record.boot != boot) throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_BOOT_MISMATCH")
                if (record.mutationBarrier != null) return fail(s, "MUTATION_UNCERTAIN_NO_REPLAY")
                recoveryStage = "connect"
                val c = VirtualDisplayOwnerClient.reconnectChecked(AndroidAgentLogger, record.socket, record.pid,
                    record.displayId, record.uniqueId, record.token, record.run)
                s.client = c
                recoveryStage = "status"
                val state = c.status()
                recoveryStage = "handoff_state"
                val observed = handoffState(c, state)
                    ?: throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_HANDOFF_STATE_INVALID")
                val registered = observed.retainedTaskIds
                recoveryStage = "selection"
                val restored = record.kept?.let { ids(JSONArray(it))
                    ?: throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_SELECTION_INVALID") } ?: registered
                if (!registered.containsAll(restored)) throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_SELECTION_INVALID")
                s.kept.addAll(restored)
                s.persisted = true
                s.cleanupOnly = true
                s.closedRun = true
                s.phase = "held"
                return body(state).put("recovered", true).put("cleanup_only", true).put("run_id", runId)
            }
        } catch (ex: Exception) {
            val e = VirtualDisplayRecoveryException.classify(recoveryStage, ex)
            AndroidAgentLogger.warn("Virtual display recovery stage=${e.stage} code=${e.code} field=${e.field} type=${e.exceptionType}")
            // This block authenticates and reads only; it has not sent a handoff or release.
            s.phase = "held"; s.closedRun = true; s.cleanupOnly = true
            return reply(false, e.code).put("recovery_stage", e.stage).put("recovery_field", e.field)
                .put("mutation_uncertain", false).put("automatic_retry_allowed", false).also { s.receipt = it }
        }
        // Manual recovery must never create a display merely to close it.
        if (!createIfMissing) {
            sessions.remove(runId)
            return reply(false, "NO_VIRTUAL_SESSION")
        }
        return when (val started = VirtualDisplayOwnerClient.start(context, AndroidAgentLogger)) {
            is OwnerStartResult.Failed -> fail(s, started.errorCode)
            is OwnerStartResult.Ready -> {
                val c = started.client
                s.client = c
                s.persisted = runCatching { saved.edit().putString("socket", c.socketName)
                    .putLong("pid", c.ownerPid).putInt("display", c.displayId)
                    .putString("unique", c.uniqueId).putString("token", c.recoveryToken)
                    .putString("run", runId).putString("boot", boot).commit() }.getOrDefault(false)
                if (!s.persisted) return fail(s, "RECOVERY_STATE_UNWRITABLE")
                val state = c.status()
                if (handoffState(c, state) == null) fail(s, "OWNER_STATE_UNKNOWN")
                else { s.phase = "active"; body(state).put("run_id", runId) }
            }
        }
    }
    /** Read only app-owned metadata; never treat the unrelated port-3070 daemon as this owner. */
    @Synchronized fun recoveryStatus(context: Context): JSONObject {
        return try {
            val pending = sessions.values.filter { it.phase != "finished" }
            val saved = recoveryPrefs(context)
            if (pending.isEmpty() && saved.all.isEmpty()) return reply(true).put("present", false)
            val s = pending.singleOrNull()
            val busy = pending.any { !it.closedRun }
            reply(true).put("present", true).put("manager", "eta_owner")
                .put("displayId", s?.client?.displayId ?: saved.getInt("display", -1))
                .put("phase", s?.phase ?: "recovery_pending")
                .put("busy", busy).put("recoverable", !busy && pending.size <= 1)
                .put("lastError", s?.receipt?.optString("error").orEmpty())
                .put("lastDetail", s?.receipt?.optString("message").orEmpty())
        } catch (_: Exception) { reply(false, "RECOVERY_STATE_UNREADABLE") }
    }

    /** Lists only the authenticated current owner, not Android's unrelated displays. */
    @Synchronized fun previewDisplays(context: Context): VirtualDisplayPreviewHttpServer.Displays {
        val result = previewRead(context, null, capture = false)
        return when (result.error) {
            "NO_VIRTUAL_SESSION" -> VirtualDisplayPreviewHttpServer.Displays()
            "" -> VirtualDisplayPreviewHttpServer.Displays(listOf(
                VirtualDisplayPreviewHttpServer.Display(result.displayId, result.uniqueId, result.phase)))
            else -> VirtualDisplayPreviewHttpServer.Displays(error = result.error)
        }
    }

    /** A snapshot only: no start/adoption, GUI observation contract, handoff or release. */
    @Synchronized fun previewFrame(
        context: Context,
        selected: VirtualDisplayPreviewHttpServer.Identity? = null,
    ): VirtualDisplayPreviewHttpServer.Frame = previewRead(context, selected, capture = true)

    /** Called under the Session monitor. A reconnect is borrowed, never installed/adopted. */
    private fun previewRead(
        context: Context,
        selected: VirtualDisplayPreviewHttpServer.Identity?,
        capture: Boolean,
    ): VirtualDisplayPreviewHttpServer.Frame {
        fun denied(code: String) = VirtualDisplayPreviewHttpServer.Frame(error = code)
        if (selected != null && !VirtualDisplayPreviewHttpServer.validIdentity(selected))
            return denied("PREVIEW_IDENTITY_INVALID")
        val pending = sessions.values.filter { it.phase != "finished" }
        if (pending.size > 1) return denied("OWNER_STATE_UNKNOWN")
        val session = pending.singleOrNull()
        var borrowed = false
        var client = session?.client?.takeIf { it.isAlive }
        try {
            if (client == null) {
                val saved = recoveryPrefs(context)
                if (saved.all.isEmpty()) return denied(if (session == null) "NO_VIRTUAL_SESSION" else "RECOVERY_UNCERTAIN")
                val boot = bootId() ?: return denied("BOOT_ID_UNAVAILABLE")
                if (saved.getString("boot", null) != boot) return denied("RECOVERY_UNCERTAIN")
                client = VirtualDisplayOwnerClient.reconnect(AndroidAgentLogger,
                    saved.getString("socket", "").orEmpty(), saved.getLong("pid", -1),
                    saved.getInt("display", -1), saved.getString("unique", "").orEmpty(),
                    saved.getString("token", "").orEmpty(), saved.getString("run", "").orEmpty())
                    ?: return denied("RECOVERY_UNCERTAIN")
                borrowed = true
            }
            val owner = client ?: return denied("RECOVERY_UNCERTAIN")
            val identity = VirtualDisplayPreviewHttpServer.Identity(owner.displayId, owner.uniqueId)
            if (!VirtualDisplayPreviewHttpServer.validIdentity(identity)) return denied("OWNER_STATE_UNKNOWN")
            if (selected != null && selected != identity) return denied("PREVIEW_DISPLAY_GONE")
            fun matchesOwner(response: OwnerResponse): Boolean {
                val state = response.json ?: return false
                return response.ok && state.opt("displayId") == identity.displayId &&
                    state.optString("uniqueId") == identity.uniqueId
            }
            fun sourcePackages(response: OwnerResponse): Set<String>? {
                if (!matchesOwner(response)) return null
                val packages = response.json?.optJSONArray("sourcePackages") ?: return null
                val result = linkedSetOf<String>()
                for (i in 0 until packages.length()) {
                    val pkg = packages.opt(i) as? String ?: return null
                    if (pkg.isBlank()) return null
                    result.add(pkg)
                }
                return result
            }
            val state = owner.status()
            if (!matchesOwner(state)) return denied("OWNER_STATE_UNKNOWN")
            val phase = session?.phase ?: "recovery_pending"
            if (!capture) return VirtualDisplayPreviewHttpServer.Frame(
                displayId = identity.displayId, uniqueId = identity.uniqueId, phase = phase)
            val excluded = session?.previewExcludedPackages.orEmpty() + context.packageName
            val before = sourcePackages(state) ?: return denied("SCREEN_CONTENT_UNKNOWN")
            if (before.isEmpty()) return denied("NO_FRAME")
            if (before.any { it in excluded }) return denied("SCREENSHOT_EXCLUDED_PACKAGE")
            val shot = owner.snapshot()
            if (!shot.ok) return denied("NO_FRAME")
            val afterState = owner.status()
            if (!matchesOwner(afterState)) return denied("OWNER_STATE_UNKNOWN")
            val after = sourcePackages(afterState) ?: return denied("SCREEN_CONTENT_UNKNOWN")
            if (after != before || after.any { it in excluded }) return denied("SCREEN_CONTENT_CHANGED")
            val data = shot.json ?: return denied("FRAME_INVALID")
            val encoded = data.optString("data")
            if (data.opt("displayId") != owner.displayId || data.optString("format") != "png" ||
                encoded.length !in 1..(VirtualDisplayPreviewHttpServer.MAX_FRAME_BYTES * 4 / 3 + 16))
                return denied("FRAME_INVALID")
            val png = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            if (!VirtualDisplayPreviewHttpServer.validPng(png)) return denied("FRAME_INVALID")
            return VirtualDisplayPreviewHttpServer.Frame(png, identity.displayId, phase, uniqueId = identity.uniqueId)
        } catch (_: Exception) {
            return denied("PREVIEW_UNAVAILABLE")
        } finally {
            // Closing a borrowed read connection never releases the display or its owner.
            if (borrowed) client?.close()
        }
    }

    private val manualCloser = VirtualDisplayManualClose()

    @Synchronized fun prepareManualClose(context: Context, selected: VirtualDisplayPreviewHttpServer.Identity): VirtualDisplayManualClose.Result =
        manualCloseOperation(context, selected, null)

    @Synchronized fun commitManualClose(context: Context, selected: VirtualDisplayPreviewHttpServer.Identity, nonce: String): VirtualDisplayManualClose.Result =
        manualCloseOperation(context, selected, nonce)

    /** Entire prepare/commit, including evidence and journal, is under the Session monitor. */
    private fun manualCloseOperation(context: Context, selected: VirtualDisplayPreviewHttpServer.Identity, nonce: String?): VirtualDisplayManualClose.Result {
        fun blocked(code: String) = VirtualDisplayManualClose.Result("blocked", code)
        if (!VirtualDisplayPreviewHttpServer.validIdentity(selected)) return blocked("PREVIEW_IDENTITY_INVALID")
        var borrowed = false
        var client: VirtualDisplayOwnerClient? = null
        var attempted = false
        var stage = "record_read"
        try {
            val saved = recoveryPrefs(context)
            val fields = saved.all
            if (fields.isEmpty()) return blocked("NO_VIRTUAL_SESSION")
            stage = "record_decode"
            val record = VirtualDisplayRecoveryRecord.decode(fields)
            if (record.displayId != selected.displayId || record.uniqueId != selected.uniqueId) return blocked("PREVIEW_DISPLAY_GONE")
            val currentBoot = bootId() ?: return blocked("BOOT_ID_UNAVAILABLE")
            if (record.boot != currentBoot) return blocked("RECOVERY_BOOT_MISMATCH")
            val pending = sessions.values.filter { it.phase != "finished" }
            if (pending.size > 1) return blocked("OWNER_STATE_UNKNOWN")
            val session = pending.singleOrNull()
            if (pending.any { !it.closedRun }) return blocked("ACTIVE_AGENT_OWNER")
            if (record.mutationBarrier != null) return blocked("MUTATION_UNCERTAIN_NO_REPLAY")
            stage = "connect"
            client = session?.client?.takeIf { it.isAlive }
            if (client == null) {
                client = VirtualDisplayOwnerClient.reconnectChecked(AndroidAgentLogger, record.socket, record.pid,
                    record.displayId, record.uniqueId, record.token, record.run)
                borrowed = true
            }
            val owner = requireNotNull(client)
            if (owner.ownerPid != record.pid || owner.socketName != record.socket ||
                owner.displayId != record.displayId || owner.uniqueId != record.uniqueId) return blocked("RECOVERY_OWNER_IDENTITY_MISMATCH")
            stage = "status"
            val backend = object : VirtualDisplayManualClose.Backend {
                override fun evidence(): VirtualDisplayManualClose.Evidence {
                    val nowRecord = VirtualDisplayRecoveryRecord.decode(saved.all)
                    val state = owner.status()
                    val data = state.json
                    val observed = handoffState(owner, state)
                    val sameRecord = nowRecord.key() == record.key() && nowRecord.token == record.token && nowRecord.run == record.run
                    return VirtualDisplayManualClose.Evidence(
                        record.key(), bootId(), observed != null && sameRecord,
                        sessions.values.any { !it.closedRun && it.phase != "finished" },
                        data?.opt("sourceState") as? String, data?.opt("sourceTaskCount") as? Int,
                        data?.opt("sourceEmpty") as? Boolean, observed?.retainedTaskIds?.size,
                        data?.opt("finishing") as? Boolean, data?.opt("handoffComplete") as? Boolean,
                        data?.opt("releaseAttempted") as? Boolean, data?.opt("mutationUncertain") as? Boolean,
                        nowRecord.mutationBarrier != null || (session?.handoffState != null &&
                            session.receipt?.opt("mutation_uncertain") == true),
                    )
                }
                override fun markAttempt(): Boolean {
                    val now = VirtualDisplayRecoveryRecord.decode(saved.all)
                    if (now.key() != record.key() || now.token != record.token || now.run != record.run || now.mutationBarrier != null) return false
                    val persisted = saved.edit().putString(VirtualDisplayRecoveryRecord.BARRIER, "manual_release_pending").commit()
                    if (persisted) { attempted = true; session?.handoffBudget?.stop() }
                    return persisted
                }
                override fun release(): Boolean = owner.release().ok
                override fun confirmGone(): Boolean {
                    val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return false
                    repeat(40) {
                        if (bootId() != record.boot) return false
                        val originalDisplayGone = manager.getDisplay(record.displayId) == null // Reused IDs remain unconfirmed.
                        // Signal zero is a read-only existence check, never a process termination.
                        val originalProcessGone = try {
                            android.system.Os.kill(record.pid.toInt(), 0); false
                        } catch (ex: android.system.ErrnoException) {
                            ex.errno == android.system.OsConstants.ESRCH
                        }
                        if (originalDisplayGone && originalProcessGone) return true
                        Thread.sleep(50)
                    }
                    return false
                }
                override fun clearConfirmed(): Boolean {
                    val now = VirtualDisplayRecoveryRecord.decode(saved.all)
                    if (now.key() != record.key() || now.token != record.token || now.run != record.run ||
                        now.mutationBarrier != "manual_release_pending") return false
                    val cleared = saved.edit().clear().commit()
                    if (cleared) {
                        session?.let {
                            it.phase = "finished"
                            it.receipt = reply(true).put("released", true).put("manual_close", true)
                        }
                        owner.close()
                    }
                    return cleared
                }
            }
            return if (nonce == null) manualCloser.prepare(backend) else manualCloser.close(nonce, backend)
        } catch (ex: Exception) {
            val failure = VirtualDisplayRecoveryException.classify(stage, ex)
            AndroidAgentLogger.warn("Virtual display manual close stage=${failure.stage} code=${failure.code} type=${failure.exceptionType}")
            return if (attempted) VirtualDisplayManualClose.Result("closed_unconfirmed", "RELEASE_UNCONFIRMED") else blocked(failure.code)
        } finally {
            if (borrowed) client?.close()
        }
    }

    /** Called only by the settings recovery button, not by automatic run cleanup. */
    @Synchronized fun recoverAndFinishManually(context: Context): JSONObject {
        val state = recoveryStatus(context)
        if (!state.optBoolean("ok")) return state
        if (!state.optBoolean("present")) return reply(false, "NO_VIRTUAL_SESSION")
        if (!state.optBoolean("recoverable")) return reply(false, "VIRTUAL_SESSION_BUSY")
        // A failed reconnect may leave only an in-memory placeholder. Keep the persisted
        // capability intact, but allow the next explicit attempt to authenticate it again.
        val previous = sessions.entries.singleOrNull { it.value.phase != "finished" }
        if (previous != null && previous.value.client == null) sessions.remove(previous.key)
        val runId = "manual-vd-recovery-" + java.util.UUID.randomUUID()
        try {
            val restored = start(context, runId, createIfMissing = false)
            if (!restored.optBoolean("ok")) return restored
            return finish(runId, context)
        } finally {
            // There is no Agent run to close this manual operation. Even failed start/finish
            // must leave recovery available instead of permanently reporting BUSY.
            sessions[runId]?.let { it.closedRun = true; it.cleanupOnly = true }
        }
    }

    @Synchronized fun keep(runId: String, args: JSONObject): JSONObject {
        val s = sessions[runId] ?: return reply(false, "NO_VIRTUAL_SESSION")
        if (s.phase != "active" && !s.cleanupOnly) return reply(false, "SESSION_NOT_ACTIVE")
        val wanted = linkedSetOf<Int>()
        if (args.has("task_ids")) wanted.addAll(ids(args.optJSONArray("task_ids"))
            ?: return reply(false, "INVALID_TASK_IDS"))
        val packages = mutableListOf<String>()
        if (args.has("package_name")) packages.add(args.getString("package_name"))
        args.optJSONArray("packages")?.let { a -> for (i in 0 until a.length()) packages.add(a.getString(i)) }
        for (pkg in packages) wanted.addAll(s.packages[pkg] ?: return reply(false, "PACKAGE_NOT_SESSION_OWNED"))
        if (wanted.isEmpty()) return reply(false, "NO_DELIVERY_TASKS")
        val state = s.client?.status() ?: return reply(false, "RECOVERY_UNCERTAIN")
        if (!state.ok) return body(state)
        val known = ids(body(state).optJSONArray("retainedTaskIds")) ?: return reply(false, "OWNER_TASKS_UNKNOWN")
        if (!known.containsAll(wanted)) return reply(false, "TASK_NOT_SESSION_OWNED")
        val next = s.kept + wanted
        val context = recoveryContext ?: return reply(false, "RECOVERY_STATE_UNWRITABLE")
        if (!runCatching { recoveryPrefs(context).edit().putString("kept", JSONArray(next).toString()).commit() }.getOrDefault(false))
            return fail(s, "RECOVERY_STATE_UNWRITABLE")
        s.kept.addAll(wanted)
        return reply(true).put("kept_task_ids", JSONArray(s.kept))
    }
    /** Result finalization queries only this run; it must not adopt another conversation. */
    @Synchronized fun deliveryReceipt(runId: String): JSONObject? = sessions[runId]?.receipt
    @Synchronized fun holdOnCancelledRun(runId: String) {
        sessions[runId]?.let { s ->
            s.closedRun = true
            if (s.phase == "active") s.phase = "held"
        }
    }
    @Synchronized fun finish(runId: String, context: Context? = null): JSONObject {
        val ctx = context ?: recoveryContext
        if (sessions[runId] == null) {
            if (ctx == null) return reply(false, "NO_VIRTUAL_SESSION")
            val hasRecord = runCatching { recoveryPrefs(ctx).all.isNotEmpty() }.getOrDefault(true)
            val held = sessions.values.any { it.closedRun && it.phase != "finished" }
            if (!hasRecord && !held) return reply(false, "NO_VIRTUAL_SESSION")
            val recovered = start(ctx, runId, createIfMissing = false)
            if (!recovered.optBoolean("ok")) return recovered
        }
        val s = sessions[runId] ?: return reply(false, "NO_VIRTUAL_SESSION")
        if (s.phase == "finished") return s.receipt?.let {
            if (it.opt("ok") == false) VirtualDisplayHandoffDiagnostics.preservedReceipt(it) else it
        } ?: reply(true).put("already_finished", true).put("released", true)
        // An ambiguous attempt must not reach either handoff OR release, even after adoption.
        if (s.handoffBudget.blocked || Thread.currentThread().isInterrupted)
            return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        val c = s.client ?: return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        val observed = freshHandoffState(c) ?: return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
        val frozen = s.kept.toSet()
        val baseline = s.handoffState
        if (baseline != null && (s.handoffSelection != frozen ||
                !VirtualDisplayHandoffRetry.freshStateAllowsRetry(baseline, observed, frozen)))
            return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
        val f = observed.flags
        val action = VirtualDisplayRecoveryPolicy.finishAction(f)
        if (action == VirtualDisplayRecoveryPolicy.Action.REFUSE)
            return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && frozen.isEmpty()) {
            if (!f.sourceEmpty) return reply(false, "NO_DELIVERY_TASKS")
            if (observed.retainedTaskIds.isNotEmpty()) return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
        }
        var handedOff = action == VirtualDisplayRecoveryPolicy.Action.RELEASE_ONLY
        if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && frozen.isNotEmpty()) {
            // Frozen selection: retries never widen it; compare with the pre-attempt baseline.
            val before = baseline ?: observed
            if (!VirtualDisplayHandoffRetry.freshStateAllowsRetry(before, observed, frozen))
                return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
            if (!s.handoffBudget.hasRemaining()) {
                // Same round: still a FAILURE, never another handoff/release or a budget reset.
                // The status above is fresh; cached diagnostics alone cannot keep this pending.
                if (s.phase == "handoff_pending" && s.receipt?.opt("ok") == false)
                    return VirtualDisplayHandoffDiagnostics.preservedReceipt(s.receipt!!)
                return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
            }
            s.handoffState = before
            s.handoffSelection = frozen
            s.phase = "finishing"
            val outcome = VirtualDisplayHandoffRetry.run(
                budget = s.handoffBudget,
                handoff = {
                    val handoff = c.handoff(mapOf("taskIds" to JSONArray(frozen)))
                    val completed = VirtualDisplayHandoffEvidence.completed(
                        authenticatedConnection = c.isAlive && handoff.op == VirtualDisplayOwnerProtocol.OP_HANDOFF,
                        responseOk = handoff.ok,
                        selectedIds = frozen,
                        retainedIds = before.retainedTaskIds,
                        keptIds = ids(handoff.json?.optJSONArray("keptTaskIds")),
                        removedIds = ids(handoff.json?.optJSONArray("removedTaskIds")),
                        field = { handoff.json?.opt(it) },
                    )
                    recordHandoff(s, handoff, completed)
                    if (completed) VirtualDisplayHandoffRetry.Attempt.Completed
                    else VirtualDisplayHandoffEvidence.refused(
                        authenticatedConnection = c.isAlive && handoff.op == VirtualDisplayOwnerProtocol.OP_HANDOFF,
                        responseOk = handoff.ok,
                        code = handoff.errorCode.ifBlank { "HANDOFF_UNCERTAIN" },
                        safeDetail = safeOwnerDetail(handoff),
                        field = { handoff.json?.opt(it) },
                    )
                },
                verifyFresh = {
                    VirtualDisplayHandoffRetry.freshStateAllowsRetry(before, freshHandoffState(c), frozen)
                },
                delay = { millis -> Thread.sleep(millis) },
            )
            when (outcome) {
                VirtualDisplayHandoffRetry.Outcome.HandedOff -> handedOff = true
                is VirtualDisplayHandoffRetry.Outcome.Stopped -> {
                    // Do not send a PROVEN read-only rejection through fail(), which marks
                    // ambiguity. Unknown/unauthenticated/post-anchor outcomes remain uncertain.
                    val failure = outcome.failure
                    s.phase = outcome.phase
                    val receipt = if (failure != null) reply(false,
                        VirtualDisplayHandoffDiagnostics.safeHandoffError(failure.code), failure.detail).withHandoffAttempts(s)
                        else s.receipt?.let(VirtualDisplayHandoffDiagnostics::preservedReceipt)
                            ?: reply(false, "HANDOFF_UNCERTAIN").withHandoffAttempts(s)
                    return VirtualDisplayHandoffDiagnostics.annotate(
                        receipt, s.handoffDiagnostics, uncertain = !outcome.cleanPreflight,
                    ).also { s.receipt = it }
                }
            }
        }
        // Re-read identity and phase before release; never replay an uncertain release or handoff.
        val beforeRelease = freshHandoffState(c)
        val latest = beforeRelease?.flags
        if (Thread.currentThread().isInterrupted || beforeRelease == null || latest == null ||
            beforeRelease.identity != observed.identity || beforeRelease.retainedTaskIds != observed.retainedTaskIds ||
            !latest.sourceEmpty || latest.releaseAttempted || latest.mutationUncertain ||
            latest.finishing != handedOff || latest.handoffComplete != handedOff)
            return fail(s, "RELEASE_UNCERTAIN")
        s.handoffBudget.stop() // No second release, even if its response is lost or malformed.
        // A successful release stops the owner; post-response isAlive is not transport proof.
        val authenticatedReleaseConnection = c.isAlive
        if (!authenticatedReleaseConnection) return fail(s, "RELEASE_UNCERTAIN")
        val releaseContext = ctx ?: return fail(s, "RECOVERY_STATE_UNWRITABLE")
        if (!runCatching { recoveryPrefs(releaseContext).edit()
                .putString(VirtualDisplayRecoveryRecord.BARRIER, "automatic_release_pending").commit() }.getOrDefault(false))
            return fail(s, "RECOVERY_STATE_UNWRITABLE")
        val released = try { c.release() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return fail(s, "RELEASE_UNCERTAIN") }
            catch (_: Exception) { return fail(s, "RELEASE_UNCERTAIN") }
        if (!VirtualDisplayHandoffEvidence.released(observed.identity, authenticatedReleaseConnection,
                released.ok && released.op == VirtualDisplayOwnerProtocol.OP_RELEASE, { released.json?.opt(it) }))
            return fail(s, released.errorCode.ifBlank { "RELEASE_UNCERTAIN" }, safeOwnerDetail(released))
        return clearReleased(ctx, s).put("handedOff", latest.handoffComplete)
            .withHandoffAttempts(s).also { s.receipt = it }
    }
    /** Called once when the owning run closes; explicit delivery choices are preserved. */
    @Synchronized fun onRunClosed(context: Context, runId: String) {
        val s = sessions[runId] ?: return
        s.closedRun = true
        if (s.phase == "finished") return
        if (s.kept.isEmpty()) s.packages.values.forEach { s.kept.addAll(it) }
        if (s.client == null) {
            failPreservingPrior(s, "RECOVERY_UNCERTAIN")
            return
        }
        s.receipt = runCatching { finish(runId, context) }.getOrElse { fail(s, "AUTO_FINISH_FAILED") }
        if (!s.receipt!!.optBoolean("ok")) AndroidAgentLogger.warn("Virtual display auto-finish failed; recovery retained")
    }
    @Synchronized fun executeGui(context: Context,runId: String,tool: String,args: JSONObject, excludedPackages: Set<String> = emptySet()): AgentModelClient.ToolResult {
        fun text(obj: JSONObject)=AgentModelClient.ToolResult(obj.put("tool",tool).put("display","virtual").toString())
        if(tool !in setOf("observe_screen","launch_app","wait","tap","tap_area","swipe","long_press","press_key","paste_text","input_text"))return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY"))
        if(tool=="press_key" && args.optString("button") !in setOf("BACK","ENTER","PASTE"))return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY"))
        if(tool=="input_text" && args.optString("mode","append")!="append")return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY"))
        if(tool=="launch_app" && (args.optString("package_name").isBlank() || args.optString("package_name") in excludedPackages))return text(reply(false,"PACKAGE_NOT_ALLOWED"))
        if(sessions[runId]==null && tool !in setOf("launch_app","observe_screen"))return text(reply(false,"NO_VIRTUAL_SESSION"))
        if(sessions[runId]==null){val created=start(context,runId);if(!created.optBoolean("ok"))return text(created)}
        val s=sessions[runId]?:return text(reply(false,"NO_VIRTUAL_SESSION"))
        s.previewExcludedPackages.addAll(excludedPackages)
        if(s.phase!="active" || s.cleanupOnly || !s.persisted)return text(reply(false,"SESSION_NOT_ACTIVE"))
        val c=s.client!!
        try {
            if(tool=="observe_screen") {
                //观察失败不得保留上一帧的有效坐标：先作废，成功登记新帧后才能再次输入。
                s.observation.invalidate()
                val visible=c.status()
                val packages=body(visible).optJSONArray("sourcePackages")
                if(!visible.ok || packages==null)return text(reply(false,"SCREEN_CONTENT_UNKNOWN"))
                if((0 until packages.length()).any { packages.getString(it) in excludedPackages })return text(reply(false,"SCREENSHOT_EXCLUDED_PACKAGE"))
                val shot=c.snapshot();if(!shot.ok)return text(body(shot))
                val data=body(shot);val encoded=data.optString("data")
                val frameWidth=data.optInt("width",0);val frameHeight=data.optInt("height",0)
                if(encoded.isBlank() || frameWidth<=0 || frameHeight<=0)return text(reply(false,"NO_FRAME"))
                val capture=try {
                    VirtualDisplayScreenCapture.capture(encoded,frameWidth,frameHeight)
                } catch(e:Exception) {
                    return text(reply(false,"FRAME_ENCODE_FAILED",e.javaClass.simpleName))
                }
                s.observation.record(capture.contract)
                data.remove("data");data.remove("width");data.remove("height");data.remove("bytes");data.remove("format")
                return AgentModelClient.ToolResult(
                    data.put("tool",tool)
                        .put("screen",JSONObject().put("width",capture.contract.screenWidth).put("height",capture.contract.screenHeight))
                        .put("screenshot",JSONObject().put("width",capture.contract.screenshotWidth)
                            .put("height",capture.contract.screenshotHeight)
                            .put("mime_type",capture.image.mimeType)
                            .put("bytes",capture.image.bytes))
                        .put("coordinate_contract",capture.contract.toContractJson())
                        .put("ui_nodes",JSONArray())
                        .put("note","虚拟屏截图坐标默认按 screenshot 空间映射到副屏像素；screen 空间显式直通；仅截图模式，不支持节点操作")
                        .toString(),
                    listOf(capture.image))
            }
            if(tool=="launch_app") {
                val pkg=args.optString("package_name")
                if(pkg in excludedPackages)return text(reply(false,"SCREENSHOT_EXCLUDED_PACKAGE"))
                if(pkg.isBlank())return text(reply(false,"PACKAGE_NAME_REQUIRED","先 search_apps 获取精确包名"))
                val component=context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
                    ?:return text(reply(false,"APP_NOT_LAUNCHABLE"))
                val before=c.status();if(!before.ok)return text(body(before))
                val previous=body(before).optJSONArray("retainedTaskIds")?:JSONArray()
                val prior=(0 until previous.length()).map { previous.getInt(it) }.toSet()
                s.observation.invalidate()
                val launched=c.launch(component)
                if(launched.ok) {
                    val ids=body(launched).optJSONArray("taskIds")?:return text(reply(false,"LAUNCH_TASKS_UNKNOWN"))
                    val current=(0 until ids.length()).map{ids.getInt(it)}.toSet()
                    s.packages[pkg]=(s.packages[pkg]?:emptySet())+(current-prior)
                    //新任务意味着画面已变，必须重新观察后才能用坐标。
                    s.observation.invalidate()
                }
                return text(body(launched))
            }
            if(tool=="wait") {Thread.sleep(args.optLong("duration_ms",1000).coerceIn(100,30000));return text(reply(true))}
            if (tool in setOf("tap", "tap_area", "swipe", "long_press")) {
                s.observation.require()
                val live = c.status()
                if (!live.ok) {
                    s.observation.invalidate()
                    return text(reply(false,"VIRTUAL_FRAME_UNKNOWN"))
                }
                s.observation.validateFrame(body(live).optInt("width", 0), body(live).optInt("height", 0))
            }
            val fields=linkedMapOf<String,Any?>()
            val space=args.optString("coordinate_space").takeIf { it.isNotBlank() }
            when(tool) {
                "tap","tap_area" -> {
                    val point=if(tool=="tap") {
                        s.observation.resolve(space,args.getInt("x"),args.getInt("y"))
                    } else {
                        //tap_area：两个端点都必须有效，中心取映射后两端点的中点。
                        s.observation.resolveArea(space,args.getInt("x1"),args.getInt("y1"),args.getInt("x2"),args.getInt("y2"))
                    }
                    fields.putAll(mapOf("kind" to "tap","x" to point.x,"y" to point.y))
                }
                "swipe","long_press" -> {
                    val x1Key=if(tool=="swipe")"x1" else "x";val y1Key=if(tool=="swipe")"y1" else "y"
                    val first=s.observation.resolve(space,args.getInt(x1Key),args.getInt(y1Key))
                    val second=if(tool=="swipe") s.observation.resolve(space,args.getInt("x2"),args.getInt("y2")) else first
                    fields.putAll(mapOf("kind" to "swipe","x1" to first.x,"y1" to first.y,"x2" to second.x,"y2" to second.y,"durationMs" to args.optInt("duration_ms",500).coerceIn(100,3000)))
                }
                "press_key" -> {
                    val key=mapOf("BACK" to 4,"ENTER" to 66,"PASTE" to 279)[args.getString("button")]
                        ?:return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY"))
                    fields.putAll(mapOf("kind" to "key","keyCode" to key))
                }
                "paste_text","input_text" -> {
                    if(tool=="input_text" && args.optString("mode","append")!="append")return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY"))
                    s.observation.require()
                    val value=args.getString("text");require(value.length<=20000)
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("",value))
                    fields.putAll(mapOf("kind" to "key","keyCode" to 279))
                }
                else -> return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY","不会回退到主屏操作"))
            }
            val input = c.input(fields)
            if (input.errorCode in setOf("VIRTUAL_FRAME_CHANGED", "VIRTUAL_FRAME_UNKNOWN")) s.observation.invalidate()
            return text(body(input))
        }catch(e:VirtualDisplayCoordinateRejection){return text(reply(false,e.code,e.message.orEmpty()))}
        catch(e:Exception){return text(reply(false,"VIRTUAL_OPERATION_FAILED",e.javaClass.simpleName))}
    }
    fun onRunStarted(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun onRunFinished(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun engage(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun keep(packageName: String): Nothing = throw VirtualDisplayHandoffNotReadyException()
}
internal class VirtualDisplayHandoffNotReadyException : IllegalStateException(VirtualDisplaySession.NOT_READY)
