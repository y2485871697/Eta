package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort

/**
 * Resolves which thinking levels a model actually has.
 *
 * Mirrors OpenMinis ThinkingLevelCatalog / selectableThinkingLevels:
 *  1. User override wins.
 *  2. Declared backend effort tiers (supported_efforts / reasoning_options) win.
 *  3. ID catalog rules (hyphen/dot and provider-prefix tolerant).
 *  4. Unknown reasoning models default to Low..XHigh, not a lone Default.
 */
internal object ReasoningCapabilityResolver {
    private val lowToXHigh = listOf(
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
    )
    private val lowToMax = lowToXHigh + ReasoningEffort.MAX
    private val openAiGpt55 = listOf(
        ReasoningEffort.MINIMAL,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
    )
    private val openAiGpt56 = openAiGpt55 + ReasoningEffort.MAX

    fun resolve(
        sourceType: String,
        model: Model,
        inferExactCatalogModel: Boolean = false,
    ): ModelReasoningCapabilities? {
        if (model.effectiveReasoning == false) return null
        if (model.reasoningOverride == true) {
            val override = model.reasoningCapabilitiesOverride
            if (override != null && hasUsableEffortTiers(override)) return override
            if (isToggleOnly(override)) return override
            return catalogByModelId(model.modelId) ?: unknownReasoningDefault()
        }

        val declared = model.reasoningCapabilities
        if (declared != null && hasUsableEffortTiers(declared)) return declared
        if (isToggleOnly(declared)) return declared

        val catalog = catalogByModelId(model.modelId)
        if (catalog != null) return catalog

        if (model.effectiveReasoning == true) {
            return unknownReasoningDefault()
        }
        if (inferExactCatalogModel) return null
        return null
    }

    fun automaticCapabilities(
        model: Model,
        sourceType: String = ProviderSourceTypes.CUSTOM,
    ): ModelReasoningCapabilities? = resolve(
        sourceType = sourceType,
        model = model.copy(
            reasoningOverride = null,
            reasoningCapabilitiesOverride = null,
        ),
    )

    fun suggestedCapabilities(
        model: Model,
        sourceType: String = ProviderSourceTypes.CUSTOM,
    ): ModelReasoningCapabilities = automaticCapabilities(model, sourceType)
        ?: resolve(
            sourceType = sourceType,
            model = model.copy(
                reasoning = true,
                reasoningOverride = null,
                reasoningCapabilitiesOverride = null,
            ),
        )
        ?: unknownReasoningDefault()

    fun catalogCapabilities(
        sourceType: String,
        modelId: String,
    ): ModelReasoningCapabilities? = catalogByModelId(modelId)

    internal fun catalogByModelId(modelId: String): ModelReasoningCapabilities? {
        val id = normalizedId(modelId)
        if (id.isEmpty()) return null

        return when {
            hasPrefix(id, "gpt-5-6") -> capabilities(
                openAiGpt56,
                canDisable = true,
                defaultEffort = ReasoningEffort.MEDIUM,
            )
            hasPrefix(id, "gpt-5-5") -> capabilities(
                openAiGpt55,
                canDisable = true,
                defaultEffort = ReasoningEffort.MEDIUM,
            )
            hasPrefix(id, "claude-opus-4") -> capabilities(
                lowToMax,
                canDisable = true,
                defaultEffort = ReasoningEffort.HIGH,
            )
            hasPrefix(id, "claude-fable-5") || hasPrefix(id, "claude-sonnet-5") -> capabilities(
                lowToMax,
                canDisable = true,
                defaultEffort = ReasoningEffort.HIGH,
            )
            hasPrefix(id, "deepseek-v4-flash") -> capabilities(
                listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
                canDisable = true,
                defaultEffort = ReasoningEffort.HIGH,
            )
            hasPrefix(id, "deepseek-v4") -> capabilities(
                listOf(ReasoningEffort.HIGH, ReasoningEffort.MAX),
                canDisable = true,
                defaultEffort = ReasoningEffort.HIGH,
            )
            hasPrefix(id, "kimi-k3") -> capabilities(
                listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
                mandatory = true,
                defaultEffort = ReasoningEffort.MAX,
            )
            hasPrefix(id, "kimi-k2-7-code") -> mandatoryDefault()
            hasPrefix(id, "kimi-k2-6") || hasPrefix(id, "kimi-k2-5") ->
                capabilities(emptyList(), canDisable = true)
            hasPrefix(id, "qwen3-7") -> capabilities(
                supported = lowToMax,
                canDisable = true,
                supportsBudget = true,
                maxBudgetTokens = 262_144,
                defaultEffort = ReasoningEffort.MAX,
            )
            hasPrefix(id, "qwen3-8") -> capabilities(
                listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.XHIGH),
                canDisable = true,
            )
            containsToken(id, "mimo") || containsToken(id, "agnes") -> capabilities(
                listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                canDisable = true,
                defaultEffort = ReasoningEffort.HIGH,
            )
            containsToken(id, "seed-") || containsToken(id, "bytedance-seed") -> capabilities(
                listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                canDisable = true,
                defaultEffort = ReasoningEffort.HIGH,
            )
            containsToken(id, "minimax") -> mandatoryDefault()
            hasPrefix(id, "step-3-5-flash-2603") -> capabilities(
                listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
                mandatory = true,
            )
            hasPrefix(id, "step-") -> capabilities(
                listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
                canDisable = true,
            )
            containsToken(id, "grok") &&
                "non-reasoning" !in id &&
                !hasPrefix(id, "grok-build") &&
                !hasPrefix(id, "grok-stt") &&
                !hasPrefix(id, "grok-tts") -> capabilities(
                lowToXHigh,
                canDisable = true,
                defaultEffort = ReasoningEffort.HIGH,
            )
            else -> null
        }
    }

    private fun hasUsableEffortTiers(capabilities: ModelReasoningCapabilities): Boolean =
        capabilities.supportedEfforts.any {
            it != ReasoningEffort.OFF && it != ReasoningEffort.DEFAULT
        } || capabilities.supportsBudget

    private fun isToggleOnly(capabilities: ModelReasoningCapabilities?): Boolean =
        capabilities != null &&
            !capabilities.mandatory &&
            capabilities.canDisable &&
            capabilities.supportedEfforts.isEmpty() &&
            !capabilities.supportsBudget

    private fun unknownReasoningDefault() = capabilities(
        supported = lowToXHigh,
        canDisable = true,
        defaultEffort = ReasoningEffort.MEDIUM,
    )

    private fun mandatoryDefault() = ModelReasoningCapabilities(
        defaultEnabled = true,
        mandatory = true,
    )

    private fun capabilities(
        supported: List<ReasoningEffort>,
        canDisable: Boolean = false,
        mandatory: Boolean = false,
        supportsBudget: Boolean = false,
        maxBudgetTokens: Int? = null,
        defaultEffort: ReasoningEffort? = null,
    ) = ModelReasoningCapabilities(
        supportedEfforts = supported,
        defaultEffort = defaultEffort,
        defaultEnabled = true,
        mandatory = mandatory,
        canDisable = canDisable,
        supportsBudget = supportsBudget,
        maxBudgetTokens = maxBudgetTokens,
    )

    internal fun modelIdMatchesPrefix(modelId: String, prefix: String): Boolean =
        hasPrefix(normalizedId(modelId), prefix)

    internal fun modelIdContains(modelId: String, token: String): Boolean =
        containsToken(normalizedId(modelId), token)

    private fun normalizedId(modelId: String): String =
        modelId.trim().lowercase()
            .substringAfterLast('/')
            .replace('.', '-')
            .trim()

    private fun hasPrefix(normalizedId: String, prefix: String): Boolean {
        val needle = prefix.lowercase().replace('.', '-')
        return normalizedId.startsWith(needle)
    }

    private fun containsToken(normalizedId: String, token: String): Boolean {
        val needle = token.lowercase().replace('.', '-')
        return normalizedId.contains(needle)
    }
}
