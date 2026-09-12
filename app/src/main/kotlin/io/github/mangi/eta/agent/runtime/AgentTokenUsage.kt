package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null

    /**
     * 当前窗口占用：优先 ST 的 prompt+completion，没有分项时才用 total_tokens。
     * cache 已包含在 prompt 里，不再另加。
     */
    fun occupancyTokens(): Int? {
        val input = inputTokens ?: 0
        val output = outputTokens ?: 0
        if (input > 0 || output > 0) return input + output
        return contextTokens?.takeIf { it > 0 }
    }
}
