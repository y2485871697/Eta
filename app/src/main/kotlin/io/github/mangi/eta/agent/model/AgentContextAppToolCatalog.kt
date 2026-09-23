package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 上下文、应用入口与屏幕观察工具 schema。 */
internal object AgentContextAppToolCatalog {
    fun appendTo(tools: JSONArray) {
        for ((name,description) in listOf(
            "start_virtual_session" to "启动本次后台副屏会话，重复调用不重复创建；失败不会回退主屏。",
            "finish_virtual_session" to "移交已标记的交付任务到主屏后台，清理本次中间任务并关闭空副屏。必须检查 handedOff 和 released；失败时不杀进程，不声称交付成功。"
        )) tools.put(AgentToolSchema.function(name=name,description=description,parameters=JSONObject().put("type","object").put("properties",JSONObject())))
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "获取手机当前时间、时区和最近系统位置；涉及现在、今天、明天或所在位置时调用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "搜索手机上已安装的 Android 应用，返回应用名和包名。打开应用前如果不确定包名，先调用这个工具。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用名或包名片段，例如 QQ、微信、com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否包含系统应用，默认 false")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 20 个结果，默认 10")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "启动一个已安装 Android 应用。优先提供 package_name；只有应用名时允许模糊匹配，匹配多个会返回候选而不会启动。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "精确 Android 包名，例如 com.tencent.mobileqq")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用显示名，例如 QQ")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "inspect_virtual_backend",
                    description = "用当前应用内的探针进行 Root 只读后端检查，不启动副屏。查询成功不等于授权；mutations_enabled 与 session_authenticated 恒为 false，不迁移、恢复或关闭应用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "keep_virtual_result",
                    description = "标记本次副屏会话要交付的任务。优先 task_ids；也可按本次已启动包名选择。这里只标记，必须再调用 finish_virtual_session 验证迁移及关闭。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("task_ids", JSONObject().put("type","array").put("items",JSONObject().put("type","integer")).put("description","本次启动工具返回的精确任务编号"))
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "一个最终应用的精确包名")
                                )
                                .put(
                                    "packages",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("items", JSONObject().put("type", "string"))
                                        .put("description", "多个最终应用的精确包名")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "把一个确定有效的 URI 显式交给 Android 外部应用处理，例如 https、tel、geo 或应用 deep link。它不用于读取网页或网页交互。不要编造 URI。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "确定有效、可由系统处理的 URI")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "observe_screen",
                    description = "观察当前手机屏幕，默认只返回前台应用、屏幕尺寸、observation_id 与可见 UI 节点，不附截图。节点为空、目标无法唯一识别、界面以 Canvas/地图/图片/二维码等视觉内容为主，或任务依赖颜色、图像、空间布局时，显式设置 include_screenshot=true；补截图时保持 include_ui_tree=true，以同一次新观察刷新节点和 observation_id，禁止把新截图与旧节点混用。节点动作必须原样携带同一次观察的 observation_id；树被截断但节点语义仍有效时，优先把 max_nodes 提高到 120 后重试，不要仅因截断请求截图。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "include_screenshot",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_SCREENSHOT)
                                        .put("description", "是否附加当前屏幕原图给模型，默认 false；仅在 UI 节点不足以完成任务时显式开启")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_UI_TREE)
                                        .put("description", "是否返回 UI 节点列表，默认 true")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentScreenObservationContract.MIN_MAX_NODES)
                                        .put("maximum", AgentScreenObservationContract.MAX_MAX_NODES)
                                        .put("default", AgentScreenObservationContract.DEFAULT_MAX_NODES)
                                        .put("description", "最多返回 1 到 120 个 UI 节点，默认 60")
                                )
                        )
                )
            )
    }
}
