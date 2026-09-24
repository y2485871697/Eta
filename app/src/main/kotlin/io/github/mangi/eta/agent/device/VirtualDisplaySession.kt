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
        var observed = false
        var width = 0
        var height = 0
        var closedRun = false
        var cleanupOnly = false
        var persisted = false
        var receipt: JSONObject? = null
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
    private fun fail(s: Session, code: String): JSONObject {
        s.phase = "uncertain"
        return reply(false, code).also { s.receipt = it }
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
    @Synchronized fun start(context: Context, runId: String): JSONObject {
        recoveryContext = context.applicationContext
        if (runId.isBlank()) return reply(false, "RUN_ID_REQUIRED")
        sessions[runId]?.let { s ->
            if (s.phase == "finished") return reply(false, "SESSION_FINISHED")
            // Do not turn a held owner into an active GUI session.
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
            return reply(true).put("recovered", true).put("cleanup_only", true).put("phase", s.phase)
        }
        val s = Session()
        sessions[runId] = s
        val saved = try { recoveryPrefs(context) } catch (_: Exception) { return fail(s, "RECOVERY_STATE_UNREADABLE") }
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
            val recovered = start(ctx, runId)
            if (!recovered.optBoolean("ok")) return recovered
        }
        val s = sessions[runId] ?: return reply(false, "NO_VIRTUAL_SESSION")
        if (s.phase == "finished") return s.receipt ?: reply(true).put("already_finished", true).put("released", true)
        val c = s.client ?: return fail(s, "RECOVERY_UNCERTAIN")
        val state = c.status()
        if (!state.ok) return fail(s, "RECOVERY_UNCERTAIN")
        val f = flags(body(state)) ?: return fail(s, "OWNER_STATE_UNKNOWN")
        val action = VirtualDisplayRecoveryPolicy.finishAction(f)
        if (action == VirtualDisplayRecoveryPolicy.Action.REFUSE) return fail(s, "RECOVERY_UNCERTAIN")
        if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && s.kept.isEmpty()) {
            if (!f.sourceEmpty) return reply(false, "NO_DELIVERY_TASKS")
        }
        if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && s.kept.isNotEmpty()) {
            s.phase = "finishing"
            val handoff = c.handoff(mapOf("taskIds" to JSONArray(s.kept)))
            if (!handoff.ok || body(handoff).opt("handedOff") != true || body(handoff).opt("sourceEmpty") != true)
                return fail(s, handoff.errorCode.ifBlank { "HANDOFF_UNCERTAIN" })
        }
        // Re-read phase before release; never replay an uncertain release or handoff.
        val beforeRelease = c.status()
        val latest = if (beforeRelease.ok) flags(body(beforeRelease)) else null
        if (latest == null || !latest.sourceEmpty || latest.releaseAttempted || latest.mutationUncertain ||
            (latest.finishing && !latest.handoffComplete)) return fail(s, "RELEASE_UNCERTAIN")
        val released = c.release()
        if (!released.ok || body(released).opt("released") != true) return fail(s, released.errorCode.ifBlank { "RELEASE_UNCERTAIN" })
        return clearReleased(ctx, s).put("handedOff", latest.handoffComplete).also { s.receipt = it }
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
        if(s.phase!="active" || s.cleanupOnly || !s.persisted)return text(reply(false,"SESSION_NOT_ACTIVE"))
        val c=s.client!!
        try {
            if(tool=="observe_screen") {
                val visible=c.status()
                val packages=body(visible).optJSONArray("sourcePackages")
                if(!visible.ok || packages==null)return text(reply(false,"SCREEN_CONTENT_UNKNOWN"))
                if((0 until packages.length()).any { packages.getString(it) in excludedPackages })return text(reply(false,"SCREENSHOT_EXCLUDED_PACKAGE"))
                val shot=c.snapshot();if(!shot.ok)return text(body(shot))
                val data=body(shot);val encoded=data.optString("data")
                if(encoded.isBlank())return text(reply(false,"NO_FRAME"))
                s.width=data.getInt("width");s.height=data.getInt("height");s.observed=true
                val image=AgentModelClient.ModelImage("data:image/png;base64,$encoded","image/png",data.getInt("bytes"),s.width,s.height,"virtual_display")
                data.remove("data")
                return AgentModelClient.ToolResult(data.put("tool",tool).put("coordinate_space","screen").put("ui_nodes",JSONArray()).put("note","虚拟屏原始像素坐标；仅截图模式，不支持节点操作").toString(),listOf(image))
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
                val launched=c.launch(component)
                if(launched.ok) {
                    val ids=body(launched).optJSONArray("taskIds")?:return text(reply(false,"LAUNCH_TASKS_UNKNOWN"))
                    val current=(0 until ids.length()).map{ids.getInt(it)}.toSet()
                    s.packages[pkg]=(s.packages[pkg]?:emptySet())+(current-prior)
                    s.observed=false
                }
                return text(body(launched))
            }
            if(tool=="wait") {Thread.sleep(args.optLong("duration_ms",1000).coerceIn(100,30000));return text(reply(true))}
            val fields=linkedMapOf<String,Any?>()
            fun point(x:Int,y:Int) {require(s.observed && x in 0 until s.width && y in 0 until s.height){"observe current virtual screen first; coordinates outside frame"}}
            when(tool) {
                "tap","tap_area" -> {
                    val x=if(tool=="tap")args.getInt("x") else ((args.getInt("x1").toLong()+args.getInt("x2"))/2).toInt()
                    val y=if(tool=="tap")args.getInt("y") else ((args.getInt("y1").toLong()+args.getInt("y2"))/2).toInt()
                    point(x,y);fields.putAll(mapOf("kind" to "tap","x" to x,"y" to y))
                }
                "swipe","long_press" -> {
                    val x1=args.getInt(if(tool=="swipe")"x1" else "x");val y1=args.getInt(if(tool=="swipe")"y1" else "y")
                    val x2=if(tool=="swipe")args.getInt("x2") else x1;val y2=if(tool=="swipe")args.getInt("y2") else y1
                    point(x1,y1);point(x2,y2);fields.putAll(mapOf("kind" to "swipe","x1" to x1,"y1" to y1,"x2" to x2,"y2" to y2,"durationMs" to args.optInt("duration_ms",500).coerceIn(100,3000)))
                }
                "press_key" -> {
                    val key=mapOf("BACK" to 4,"ENTER" to 66,"PASTE" to 279)[args.getString("button")]
                        ?:return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY"))
                    fields.putAll(mapOf("kind" to "key","keyCode" to key))
                }
                "paste_text","input_text" -> {
                    if(tool=="input_text" && args.optString("mode","append")!="append")return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY"))
                    require(s.observed){"observe input target first"}
                    val value=args.getString("text");require(value.length<=20000)
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("",value))
                    fields.putAll(mapOf("kind" to "key","keyCode" to 279))
                }
                else -> return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY","不会回退到主屏操作"))
            }
            return text(body(c.input(fields)))
        }catch(e:Exception){return text(reply(false,"VIRTUAL_OPERATION_FAILED",e.javaClass.simpleName))}
    }
    fun onRunStarted(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun onRunFinished(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun engage(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun keep(packageName: String): Nothing = throw VirtualDisplayHandoffNotReadyException()
}
internal class VirtualDisplayHandoffNotReadyException : IllegalStateException(VirtualDisplaySession.NOT_READY)
