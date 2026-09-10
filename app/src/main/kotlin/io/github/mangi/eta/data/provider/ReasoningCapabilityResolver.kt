package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort

/**
 * If a model supports reasoning, expose every thinking level.
 * Catalog rules only decide whether reasoning is available, not which
 * individual levels to check.
 */
internal object ReasoningCapabilityResolver {
    private val allEffortTiers = listOf(
        ReasoningEffort.MINIMAL,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
        ReasoningEffort.MAX,
    )

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
            return catalogByModelId(model.modelId) ?: allReasoningCapabilities()
        }

        val declared = model.reasoningCapabilities
        if (declared != null && (hasUsableEffortTiers(declared) || isToggleOnly(declared) || declared.mandatory)) {
            return allReasoningCapabilities(
                canDisable = declared.canDisable || !declared.mandatory,
                mandatory = declared.mandatory,
                supportsBudget = declared.supportsBudget,
                maxBudgetTokens = declared.maxBudgetTokens,
                defaultEffort = declared.defaultEffort ?: ReasoningEffort.MEDIUM,
            )
        }

        val catalog = catalogByModelId(model.modelId)
        if (catalog != null) return catalog

        if (model.effectiveReasoning == true) {
            return allReasoningCapabilities()
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
        ?: allReasoningCapabilities()

    fun catalogCapabilities(
        sourceType: String,
        modelId: String,
    ): ModelReasoningCapabilities? = catalogByModelId(modelId)

    internal fun catalogByModelId(modelId: String): ModelReasoningCapabilities? {
        val id = normalizedId(modelId)
        if (id.isEmpty()) return null
        if (!isKnownReasoningModel(id)) return null
        return when {
            hasPrefix(id, "qwen3-7") -> allReasoningCapabilities(
                supportsBudget = true,
                maxBudgetTokens = 262_144,
                defaultEffort = ReasoningEffort.MAX,
            )
            hasPrefix(id, "kimi-k3") -> allReasoningCapabilities(
                canDisable = false,
                mandatory = true,
                defaultEffort = ReasoningEffort.MAX,
            )
            else -> allReasoningCapabilities()
        }
    }

    private fun isKnownReasoningModel(id: String): Boolean = when {
        hasPrefix(id, "gpt-5") || hasPrefix(id, "o1") || hasPrefix(id, "o3") || hasPrefix(id, "o4") -> true
        hasPrefix(id, "claude-") -> true
        hasPrefix(id, "deepseek") -> true
        hasPrefix(id, "kimi-") -> true
        hasPrefix(id, "qwen3-") -> true
        containsToken(id, "mimo") || containsToken(id, "agnes") -> true
        containsToken(id, "seed-") || containsToken(id, "bytedance-seed") -> true
        containsToken(id, "minimax") -> true
        hasPrefix(id, "step-") -> true
        containsToken(id, "grok") &&
            "non-reasoning" !in id &&
            !hasPrefix(id, "grok-build") &&
            !hasPrefix(id, "grok-stt") &&
            !hasPrefix(id, "grok-tts") -> true
        else -> false
    }

    private fun allReasoningCapabilities(
        canDisable: Boolean = true,
        mandatory: Boolean = false,
        supportsBudget: Boolean = false,
        maxBudgetTokens: Int? = null,
        defaultEffort: ReasoningEffort? = ReasoningEffort.MEDIUM,
    ) = ModelReasoningCapabilities(
        supportedEfforts = allEffortTiers,
        defaultEffort = defaultEffort,
        defaultEnabled = true,
        mandatory = mandatory,
        canDisable = canDisable,
        supportsBudget = supportsBudget,
        maxBudgetTokens = maxBudgetTokens,
    )

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