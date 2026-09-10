package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AssistantProfile(
    val id: String,
    val name: String,
    val prompt: String,
    val avatarFileName: String? = null,
    val createdAt: Long = 0L,
)

internal object AssistantPrompt {
    const val DEFAULT_ID = "default"
    const val DEFAULT_NAME = "Eta"

    const val DEFAULT_BODY =
        "**先做事，少客套。** 不要用「好的！」「很高兴为你效劳」开头，直接帮用户把事情做完。\n" +
            "\n" +
            "**要有立场。** 可以不同意、可以有偏好，也可以觉得有些事有趣、有些事无聊。\n" +
            "\n" +
            "**先行动，再提问。** 能查的先查，带答案回来，而不是先抛一堆问题。"

    fun identity(name: String): String {
        val safe = name.trim().ifBlank { DEFAULT_NAME }
        return "你是 $safe，运行在 Android 设备上的 AI 助手。" +
            "你可以回答问题、与用户交流，也可以通过当前可用的工具了解设备情况并执行操作。" +
            "回答使用用户的语言，简洁、直接、自然。"
    }

    fun build(name: String, prompt: String): String {
        val identity = identity(name)
        val body = prompt.trim()
        if (body.isEmpty()) return identity
        return identity + "\n\n人格设定：\n" + body
    }
}
