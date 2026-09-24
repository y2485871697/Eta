package io.github.mangi.eta.agent.device

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray
import org.json.JSONObject

/** Single-device, run-bound experimental owner. No transport error falls back to display zero. */
internal object VirtualDisplaySession {
    const val NOT_READY = "VIRTUAL_DISPLAY_HANDOFF_NOT_READY"
    private class Session(var client: VirtualDisplayOwnerClient? = null, var phase: String = "starting") {
        val kept = linkedSetOf<Int>()
        val packages = linkedMapOf<String, Set<Int>>()
        var observed = false
        var width = 0
        var height = 0
    }
    private val sessions = linkedMapOf<String, Session>()
    private fun reply(ok: Boolean, code: String = "", detail: String = "") =
        JSONObject().put("ok",ok).put("error",code).put("message",detail)
    private fun body(response: OwnerResponse): JSONObject = response.json ?: reply(false,response.errorCode)
    @Synchronized fun start(context: Context, runId: String): JSONObject {
        if(runId.isBlank())return reply(false,"RUN_ID_REQUIRED")
        sessions[runId]?.let { return reply(it.phase == "active",if(it.phase == "active") "" else "SESSION_${it.phase.uppercase()}").put("phase",it.phase) }
        if(sessions.values.any { it.phase != "finished" })return reply(false,"VIRTUAL_SESSION_BUSY")
        val session=Session();sessions[runId]=session // claim before external side effects
        return when(val started=VirtualDisplayOwnerClient.start(context,AndroidAgentLogger)) {
            is OwnerStartResult.Failed -> {
                session.phase=if(started.errorCode in setOf(VirtualDisplayOwnerError.CLASSPATH_UNAVAILABLE,VirtualDisplayOwnerError.CLASSPATH_INVALID,VirtualDisplayOwnerError.PROCESS_START_FAILED))"finished" else "uncertain"
                reply(false,started.errorCode)
            }
            is OwnerStartResult.Ready -> {
                session.client=started.client
                val state=started.client.status()
                if(!state.ok){session.phase="uncertain";body(state)} else {
                    session.phase="active";body(state).put("run_id",runId)
                }
            }
        }
    }
    @Synchronized fun keep(runId: String,args: JSONObject): JSONObject {
        val s=sessions[runId]?:return reply(false,"NO_VIRTUAL_SESSION")
        if(s.phase!="active")return reply(false,"SESSION_NOT_ACTIVE")
        val wanted=linkedSetOf<Int>()
        val ids=args.optJSONArray("task_ids")
        if(ids!=null)for(i in 0 until ids.length()) {
            val id=ids.opt(i);if(id !is Int || id<=0)return reply(false,"INVALID_TASK_IDS");wanted.add(id)
        }
        val packages=mutableListOf<String>()
        if(args.has("package_name"))packages.add(args.getString("package_name"))
        args.optJSONArray("packages")?.let { for(i in 0 until it.length())packages.add(it.getString(i)) }
        for(pkg in packages)wanted.addAll(s.packages[pkg]?:return reply(false,"PACKAGE_NOT_SESSION_OWNED"))
        if(wanted.isEmpty())return reply(false,"NO_DELIVERY_TASKS")
        val status=s.client!!.status();if(!status.ok)return body(status)
        val registered=body(status).optJSONArray("retainedTaskIds")?:return reply(false,"OWNER_TASKS_UNKNOWN")
        val known=(0 until registered.length()).map { registered.getInt(it) }.toSet()
        if(!known.containsAll(wanted))return reply(false,"TASK_NOT_SESSION_OWNED")
        s.kept.addAll(wanted)
        return reply(true).put("kept_task_ids",JSONArray(s.kept))
    }
    @Synchronized fun finish(runId: String): JSONObject {
        val s=sessions[runId]?:return reply(false,"NO_VIRTUAL_SESSION")
        if(s.phase=="finished")return reply(true).put("already_finished",true)
        if(s.phase!="active")return reply(false,"SESSION_NOT_ACTIVE")
        if(s.kept.isEmpty()) {
            val status=s.client!!.status()
            if(!status.ok || body(status).opt("sourceEmpty") != true)return reply(false,"NO_DELIVERY_TASKS","先明确标记交付任务；不会自动删除所有任务")
            val released=s.client!!.release()
            if(!released.ok || !body(released).optBoolean("released")) {s.phase="uncertain";return body(released).put("ok",false)}
            s.phase="finished";s.client!!.close()
            return reply(true).put("released",true).put("empty_session",true)
        }
        s.phase="finishing"
        val client=s.client!!
        val handoff=client.handoff(mapOf("taskIds" to JSONArray(s.kept)))
        if(!handoff.ok || !body(handoff).optBoolean("handedOff")) {s.phase="uncertain";return body(handoff).put("ok",false)}
        val released=client.release()
        if(!released.ok || !body(released).optBoolean("released")) {s.phase="uncertain";return body(released).put("ok",false)}
        s.phase="finished";client.close()
        return body(handoff).put("released",true)
    }
    /**
     * A closed agent run is the explicit end-of-work signal for this session. Finish only the
     * tasks that this owner launched and that the caller marked for delivery; the existing owner
     * handoff/release checks remain the admission gate. Unknown or foreign tasks therefore keep
     * the session held instead of being killed or moved implicitly.
     */
    @Synchronized fun onRunClosed(runId: String) {
        sessions[runId]?.let {
            if(it.phase=="active") {
                // Launch registration is already provenance-checked by the owner. Treat those
                // session-owned tasks as delivery candidates even when the model omitted the
                // optional keep tool; foreign tasks never enter this set.
                it.packages.values.forEach { ids -> it.kept.addAll(ids) }
                runCatching { finish(runId) }
                if(it.phase=="active")it.phase="held"
            }
        }
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
        if(s.phase!="active")return text(reply(false,"SESSION_NOT_ACTIVE"))
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
