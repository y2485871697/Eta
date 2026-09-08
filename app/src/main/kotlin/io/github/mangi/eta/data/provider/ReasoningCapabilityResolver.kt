package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort

internal object ReasoningCapabilityResolver {
    private val lowToMax = listOf(
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
        ReasoningEffort.MAX,
    )
    private val openAiEfforts = listOf(
        ReasoningEffort.MINIMAL,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
    )

    fun resolve(
        sourceType: String,
        model: Model,
        inferExactCatalogModel: Boolean = false,
    ): ModelReasoningCapabilities? {
        if (model.effectiveReasoning == false) return null
        model.effectiveReasoningCapabilities?.let { return it }
        if (model.effectiveReasoning != true) {
            return if (inferExactCatalogModel) catalogCapabilities(sourceType, model.modelId) else null
        }
        return catalogCapabilities(sourceType, model.modelId)
            ?: familyCapabilities(sourceType, model.modelId)
            ?: ModelReasoningCapabilities(
                defaultEnabled = true,
                mandatory = true,
            )
    }

    fun catalogCapabilities(
        sourceType: String,
        modelId: String,
    ): ModelReasoningCapabilities? {
        val model = modelId.trim().lowercase()
        return when (sourceType) {
            ProviderSourceTypes.OPENAI -> when {
                model == "gpt-5.5" || model.startsWith("gpt-5.6-") ->
                    capabilities(
                        openAiEfforts,
                        canDisable = true,
                        defaultEffort = ReasoningEffort.MEDIUM,
                    )
                else -> null
            }

            ProviderSourceTypes.ANTHROPIC -> when (model) {
                "claude-fable-5", "claude-opus-4-8", "claude-sonnet-5" ->
                    capabilities(
                        lowToMax,
                        canDisable = true,
                        defaultEffort = ReasoningEffort.HIGH,
                    )
                else -> null
            }

            ProviderSourceTypes.BAILIAN -> when {
                model.startsWith("qwen3.7-") -> capabilities(
                    supported = lowToMax,
                    canDisable = true,
                    supportsBudget = true,
                    maxBudgetTokens = 262_144,
                    defaultEffort = ReasoningEffort.MAX,
                )
                model.startsWith("kimi-k2.7-code") -> mandatoryDefault()
                model.startsWith("kimi-k2.6") -> capabilities(emptyList(), canDisable = true)
                else -> null
            }

            ProviderSourceTypes.DEEPSEEK -> when {
                model.startsWith("deepseek-v4-flash") ->
                    capabilities(
                        listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
                        canDisable = true,
                        defaultEffort = ReasoningEffort.HIGH,
                    )
                model.startsWith("deepseek-v4-pro") ->
                    capabilities(
                        listOf(ReasoningEffort.HIGH, ReasoningEffort.MAX),
                        canDisable = true,
                        defaultEffort = ReasoningEffort.HIGH,
                    )
                else -> null
            }

            ProviderSourceTypes.MOONSHOT -> when {
                model.startsWith("kimi-k3") -> capabilities(
                    supported = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
                    mandatory = true,
                    defaultEffort = ReasoningEffort.MAX,
                )
                model.startsWith("kimi-k2.7-code") -> mandatoryDefault()
                model.startsWith("kimi-k2.6") || model.startsWith("kimi-k2.5") ->
                    capabilities(emptyList(), canDisable = true)
                else -> null
            }

            ProviderSourceTypes.MIMO -> when {
                model.startsWith("mimo-v2.5") -> capabilities(emptyList(), canDisable = true)
                else -> null
            }

            ProviderSourceTypes.MINIMAX -> mandatoryDefault()
            ProviderSourceTypes.STEPFUN -> when {
                model.startsWith("step-3.5-flash-2603") -> capabilities(
                    listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
                    mandatory = true,
                )
                else -> mandatoryDefault()
            }

            else -> null
        }
    }

    private fun familyCapabilities(
        sourceType: String,
        modelId: String,
    ): ModelReasoningCapabilities? {
        val model = modelId.trim().lowercase()
        return when (sourceType) {
            ProviderSourceTypes.BAILIAN -> when {
                model.startsWith("qwen3.7-") -> catalogCapabilities(sourceType, model)
                model.startsWith("qwen3.8-") -> capabilities(
                    listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.XHIGH),
                    canDisable = true,
                )
                "deepseek" in model -> capabilities(
                    listOf(ReasoningEffort.HIGH, ReasoningEffort.MAX),
                    canDisable = true,
                    defaultEffort = ReasoningEffort.HIGH,
                )
                model.startsWith("kimi-k3") -> capabilities(
                    listOf(ReasoningEffort.MAX),
                    mandatory = true,
                    defaultEffort = ReasoningEffort.MAX,
                )
                model.startsWith("kimi-k2.7-code") -> mandatoryDefault()
                model.startsWith("kimi-k2.6") -> capabilities(emptyList(), canDisable = true)
                else -> null
            }

            ProviderSourceTypes.DEEPSEEK,
            ProviderSourceTypes.MOONSHOT,
            ProviderSourceTypes.MIMO,
            ProviderSourceTypes.MINIMAX,
            ProviderSourceTypes.STEPFUN -> catalogCapabilities(sourceType, model)

            else -> null
        }
    }

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
}
