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
        // Bounded history survives a held-session adoption; includes successful later attempts.
        val handoffAttempts = JSONArray()
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
    private fun fail(s: Session, code: String, detail: String = ""): JSONObject {
        s.phase = "uncertain"
        return reply(false, code, detail).withHandoffAttempts(s).also { s.receipt = it }
    }
    /**
     * finish 可能被重入。首次失败往往携带具体诊断（例如 owner 的 HANDOFF_PREFLIGHT_FAILED）；
     * 之后的重入不得把这份诊断降级成笼统的 RECOVERY_UNCERTAIN。
     */
    private fun failPreservingPrior(s: Session, code: String): JSONObject {
        val prior = s.receipt
        val priorCode = prior?.takeIf { !it.optBoolean("ok") }?.optString("error").orEmpty()
        if (code in setOf("RECOVERY_UNCERTAIN", "OWNER_STATE_UNKNOWN") && priorCode.isNotBlank() && priorCode != "RECOVERY_UNCERTAIN") {
            s.phase = "uncertain"
            return prior!!
        }
        return fail(s, code)
    }
    /** 只转发无歧义的安全细节；绝不回显 token、Intent 转储或数据 URI。 */
    private fun safeOwnerDetail(response: OwnerResponse): String {
        val raw = response.json?.optString("message").orEmpty()
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
        val rawSamples = response.json?.optJSONArray("focusSamples")
        val samples = JSONArray()
        if (rawSamples != null) for (i in 0 until minOf(rawSamples.length(), 4)) {
            val token = rawSamples.opt(i) as? String ?: continue
            if (token.length <= 80 && token.matches(Regex("OK|Focus_[A-Z_]+"))) samples.put(token)
        }
        if (s.handoffAttempts.length() >= 6) s.handoffAttempts.remove(0)
        s.handoffAttempts.put(JSONObject()
            .put("ok", completed)
            .put("error", if (completed) "" else response.errorCode.ifBlank { "HANDOFF_UNCERTAIN" })
            .put("message", if (completed) "" else safeOwnerDetail(response))
            .put("focusSamples", samples))
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
            // A deliberate later run adopting a held session gets a fresh bounded retry budget.
            s.handoffBudget.reset()
            return reply(true).put("recovered", true).put("cleanup_only", true).put("phase", s.phase)
        }
        val saved = try { recoveryPrefs(context) } catch (_: Exception) { return reply(false, "RECOVERY_STATE_UNREADABLE") }
        if (!createIfMissing && runCatching { saved.all.isEmpty() }.getOrDefault(false)) {
            return reply(false, "NO_VIRTUAL_SESSION")
        }
        val s = Session()
        sessions[runId] = s
        val boot = bootId() ?: return fail(s, "BOOT_ID_UNAVAILABLE")
        try {
            val hasRecord = saved.all.isNotEmpty()
            val savedBoot = saved.getString("boot", null)
            if (hasRecord && savedBoot != null && savedBoot != boot) {
                // An exact boot UUID change proves the old process/display cannot survive.
                if (!saved.edit().clear().commit()) return fail(s, "RECOVERY_STATE_UNWRITABLE")
            } else if (hasRecord) {
                // Legacy records without a boot UUID are never silently discarded.
                val c = VirtualDisplayOwnerClient.reconnect(AndroidAgentLogger,
                    saved.getString("socket", "").orEmpty(), saved.getLong("pid", -1),
                    saved.getInt("display", -1), saved.getString("unique", "").orEmpty(),
                    saved.getString("token", "").orEmpty(), saved.getString("run", runId).orEmpty())
                    ?: return fail(s, "RECOVERY_UNCERTAIN")
                s.client = c
                val state = c.status()
                if (!state.ok) return fail(s, "RECOVERY_UNCERTAIN")
                val registered = ids(body(state).optJSONArray("retainedTaskIds"))
                    ?: return fail(s, "OWNER_TASKS_UNKNOWN")
                if (flags(body(state)) == null) return fail(s, "OWNER_RECOVERY_PROTOCOL_UNSUPPORTED")
                val selection = saved.getString("kept", null)
                val restored = if (selection == null) registered else ids(JSONArray(selection))
                    ?: return fail(s, "RECOVERY_SELECTION_INVALID")
                if (!registered.containsAll(restored)) return fail(s, "RECOVERY_SELECTION_INVALID")
                s.kept.addAll(restored)
                s.persisted = true
                s.cleanupOnly = true
                s.closedRun = true
                s.phase = "held"
                return body(state).put("recovered", true).put("cleanup_only", true).put("run_id", runId)
            }
        } catch (_: Exception) { return fail(s, "RECOVERY_STATE_UNREADABLE") }
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
                if (!state.ok || flags(body(state)) == null) fail(s, "OWNER_STATE_UNKNOWN")
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

    /** A snapshot only: no start/adoption, GUI observation contract, handoff or release. */
    @Synchronized fun previewFrame(context: Context): VirtualDisplayPreviewHttpServer.Frame {
        fun denied(code: String) = VirtualDisplayPreviewHttpServer.Frame(error = code)
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
            fun sourcePackages(response: OwnerResponse): Set<String>? {
                val state = response.json ?: return null
                if (!response.ok || state.opt("displayId") != owner.displayId ||
                    state.optString("uniqueId") != owner.uniqueId) return null
                val packages = state.optJSONArray("sourcePackages") ?: return null
                val result = linkedSetOf<String>()
                for (i in 0 until packages.length()) {
                    val pkg = packages.opt(i) as? String ?: return null
                    if (pkg.isBlank()) return null
                    result.add(pkg)
                }
                return result
            }
            val excluded = session?.previewExcludedPackages.orEmpty() + context.packageName
            val before = sourcePackages(owner.status()) ?: return denied("SCREEN_CONTENT_UNKNOWN")
            if (before.isEmpty()) return denied("NO_FRAME")
            if (before.any { it in excluded }) return denied("SCREENSHOT_EXCLUDED_PACKAGE")
            val shot = owner.snapshot()
            if (!shot.ok) return denied("NO_FRAME")
            val after = sourcePackages(owner.status()) ?: return denied("SCREEN_CONTENT_UNKNOWN")
            if (after != before || after.any { it in excluded }) return denied("SCREEN_CONTENT_CHANGED")
            val data = shot.json ?: return denied("NO_FRAME")
            val encoded = data.optString("data")
            if (data.opt("displayId") != owner.displayId || data.optString("format") != "png" ||
                encoded.length !in 1..(VirtualDisplayPreviewHttpServer.MAX_FRAME_BYTES * 4 / 3 + 16))
                return denied("FRAME_INVALID")
            val png = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            if (!VirtualDisplayPreviewHttpServer.validPng(png)) return denied("FRAME_INVALID")
            return VirtualDisplayPreviewHttpServer.Frame(png, owner.displayId, session?.phase ?: "recovery_pending")
        } catch (_: Exception) {
            return denied("PREVIEW_UNAVAILABLE")
        } finally {
            // Closing a borrowed read connection never releases the display or its owner.
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
        if (s.phase == "finished") return s.receipt ?: reply(true).put("already_finished", true).put("released", true)
        val c = s.client ?: return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        val state = c.status()
        if (!state.ok) return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        val f = flags(body(state)) ?: return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
        val action = VirtualDisplayRecoveryPolicy.finishAction(f)
        if (action == VirtualDisplayRecoveryPolicy.Action.REFUSE) return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && s.kept.isEmpty()) {
            if (!f.sourceEmpty) return reply(false, "NO_DELIVERY_TASKS")
        }
        if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && s.kept.isNotEmpty()) {
            s.phase = "finishing"
            // Frozen selection: retries never widen it and the fresh-state gate re-checks it.
            val frozen = s.kept.toList()
            val outcome = VirtualDisplayHandoffRetry.run(
                budget = s.handoffBudget,
                handoff = {
                    val handoff = c.handoff(mapOf("taskIds" to JSONArray(frozen)))
                    val completed = handoff.ok && body(handoff).opt("handedOff") == true &&
                        body(handoff).opt("sourceEmpty") == true
                    recordHandoff(s, handoff, completed)
                    if (completed) VirtualDisplayHandoffRetry.Attempt.Completed
                    else VirtualDisplayHandoffRetry.Attempt.Refused(
                        handoff.errorCode.ifBlank { "HANDOFF_UNCERTAIN" }, safeOwnerDetail(handoff))
                },
                verifyFresh = {
                    val reread = c.status()
                    VirtualDisplayHandoffRetry.freshStateAllowsRetry(
                        statusOk = reread.ok,
                        flags = if (reread.ok) flags(body(reread)) else null,
                        retainedTaskIds = if (reread.ok) ids(body(reread).optJSONArray("retainedTaskIds")) else null,
                        frozenSelectedIds = frozen.toSet(),
                    )
                },
                delay = { millis -> Thread.sleep(millis) },
            )
            when (outcome) {
                VirtualDisplayHandoffRetry.Outcome.HandedOff -> Unit
                is VirtualDisplayHandoffRetry.Outcome.Stopped -> {
                    // Preserve the first meaningful handoff diagnostics; never downgrade them.
                    val first = outcome.failure
                    if (first != null) return fail(s, first.code, first.detail)
                    s.phase = "uncertain"
                    return s.receipt ?: reply(false, "HANDOFF_UNCERTAIN")
                }
            }
        }
        // Re-read phase before release; never replay an uncertain release or handoff.
        val beforeRelease = c.status()
        val latest = if (beforeRelease.ok) flags(body(beforeRelease)) else null
        if (latest == null || !latest.sourceEmpty || latest.releaseAttempted || latest.mutationUncertain ||
            (latest.finishing && !latest.handoffComplete))
            return fail(s, "RELEASE_UNCERTAIN", if (beforeRelease.ok) "" else safeOwnerDetail(beforeRelease))
        val released = c.release()
        if (!released.ok || body(released).opt("released") != true)
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
            s.receipt = reply(false, "RECOVERY_UNCERTAIN")
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
                //观察失败不得保留上一帧的有效坐标：先作废，成功登记新帧后才可再次输入。
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
                    return text(reply(false, "VIRTUAL_FRAME_UNKNOWN"))
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
