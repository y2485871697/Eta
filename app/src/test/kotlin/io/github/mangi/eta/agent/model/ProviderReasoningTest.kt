package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderReasoningTest {
    @Test
    fun mandatoryModelRejectsOffInsteadOfSilentlyDisablingReasoning() {
        val config = config(
            source = ProviderSourceTypes.MOONSHOT,
            model = "kimi-k3",
            effort = ReasoningEffort.OFF,
        ).copy(
            reasoningCapabilities = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.HIGH),
                mandatory = true,
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProviderReasoning.applyOpenAiCompatibleRequest(JSONObject(), config)
        }
    }

    @Test
    fun defaultDoesNotOverrideAdvancedRequestBody() {
        val request = JSONObject()
            .put("reasoning_effort", "medium")
            .put("thinking_budget", 1234)

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.DEFAULT),
        )

        assertEquals("medium", request.getString("reasoning_effort"))
        assertEquals(1234, request.getInt("thinking_budget"))
    }

    @Test
    fun openAiNamedEffortOverridesAdvancedValue() {
        val request = JSONObject().put("reasoning_effort", "low")

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.XHIGH),
        )

        assertEquals("xhigh", request.getString("reasoning_effort"))
    }

    @Test
    fun openAiMinimalUsesDocumentedNamedValue() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.MINIMAL),
        )

        assertEquals("minimal", request.getString("reasoning_effort"))
    }

    @Test
    fun openAiRejectsUnsupportedMaxEffort() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderReasoning.applyOpenAiCompatibleRequest(
                JSONObject(),
                config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.MAX),
            )
        }
    }

    @Test
    fun openAiOffUsesDocumentedNoneValue() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.OFF),
        )

        assertEquals("none", request.getString("reasoning_effort"))
    }

    @Test
    fun anthropicMaxUsesAdaptiveThinkingAndLargeOutputLimit() {
        val request = JSONObject()
            .put("max_tokens", 4096)
            .put("output_config", JSONObject().put("other", true))

        ProviderReasoning.applyAnthropicRequest(
            request,
            config(source = ProviderSourceTypes.ANTHROPIC, effort = ReasoningEffort.MAX),
        )

        assertEquals("adaptive", request.getJSONObject("thinking").getString("type"))
        assertEquals("max", request.getJSONObject("output_config").getString("effort"))
        assertTrue(request.getJSONObject("output_config").getBoolean("other"))
        assertEquals(65_536, request.getInt("max_tokens"))
    }

    @Test
    fun anthropicOffUsesDisabledThinkingAndRemovesOnlyEffortOverride() {
        val request = JSONObject()
            .put("thinking", JSONObject().put("type", "adaptive"))
            .put("output_config", JSONObject().put("effort", "high").put("other", true))

        ProviderReasoning.applyAnthropicRequest(
            request,
            config(source = ProviderSourceTypes.ANTHROPIC, effort = ReasoningEffort.OFF),
        )

        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertFalse(request.getJSONObject("output_config").has("effort"))
        assertTrue(request.getJSONObject("output_config").getBoolean("other"))
    }

    @Test
    fun anthropicRejectsUnsupportedMinimalEffort() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderReasoning.applyAnthropicRequest(
                JSONObject(),
                config(source = ProviderSourceTypes.ANTHROPIC, effort = ReasoningEffort.MINIMAL),
            )
        }
    }

    @Test
    fun deepSeekWritesThinkingTypeAndExactEffort() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.DEEPSEEK, effort = ReasoningEffort.MAX),
        )

        assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
        assertEquals("max", request.getString("reasoning_effort"))
    }

    @Test
    fun kimiK3UsesTopLevelEffortWithoutThinkingObject() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.MOONSHOT,
                model = "kimi-k3",
                effort = ReasoningEffort.HIGH,
            ),
        )

        assertEquals("high", request.getString("reasoning_effort"))
        assertFalse(request.has("thinking"))
    }

    @Test
    fun qwenBudgetReservesAnswerTokensBelowCompletionLimit() {
        val request = JSONObject().put("max_completion_tokens", 20_000)

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.BAILIAN,
                model = "qwen3.7-plus",
                effort = ReasoningEffort.HIGH,
            ),
        )

        assertTrue(request.getBoolean("enable_thinking"))
        assertEquals(17_500, request.getInt("thinking_budget"))
    }

    @Test
    fun siliconFlowMapsEveryNamedLevelToBudget() {
        val budgets = mapOf(
            ReasoningEffort.MINIMAL to 128,
            ReasoningEffort.LOW to 1_024,
            ReasoningEffort.MEDIUM to 4_096,
            ReasoningEffort.HIGH to 8_192,
            ReasoningEffort.XHIGH to 16_384,
            ReasoningEffort.MAX to 32_768,
        )

        budgets.forEach { (effort, budget) ->
            val request = JSONObject()
            ProviderReasoning.applyOpenAiCompatibleRequest(
                request,
                config(source = ProviderSourceTypes.SILICONFLOW, effort = effort),
            )
            assertEquals(budget, request.getInt("thinking_budget"))
        }
    }

    @Test
    fun mimoOffUsesDocumentedDisabledThinkingType() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.MIMO, effort = ReasoningEffort.OFF),
        )

        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun openRouterUsesNestedReasoningEffort() {
        val request = JSONObject().put("reasoning", JSONObject().put("exclude", true))

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENROUTER, effort = ReasoningEffort.LOW),
        )

        assertEquals("low", request.getJSONObject("reasoning").getString("effort"))
        assertTrue(request.getJSONObject("reasoning").getBoolean("exclude"))
    }

    @Test
    fun stepFunUsesOnlyVerifiedNamedLevels() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.STEPFUN,
                model = "step-3.5-flash-2603",
                effort = ReasoningEffort.LOW,
            ),
        )

        assertEquals("low", request.getString("reasoning_effort"))
    }

    @Test
    fun defaultOnlyProvidersDoNotInventRequestFields() {
        listOf(ProviderSourceTypes.MINIMAX, ProviderSourceTypes.CUSTOM).forEach { source ->
            val request = JSONObject().put("metadata", "kept")

            ProviderReasoning.applyOpenAiCompatibleRequest(
                request,
                config(source = source, effort = ReasoningEffort.DEFAULT),
            )

            assertEquals(setOf("metadata"), request.keys().asSequence().toSet())
        }
    }

    private fun config(
        source: String,
        effort: ReasoningEffort,
        model: String = "test-model",
    ) = AgentModelClient.ModelConfig(
        providerSourceType = source,
        baseUrl = "https://example.com/v1",
        apiKey = "key",
        model = model,
        systemPrompt = "system",
        thinkingEnabled = effort.enablesReasoning,
        reasoningEffort = effort,
        reasoningCapabilities = ModelReasoningCapabilities(
            supportedEfforts = ReasoningEffort.entries.filter {
                it != ReasoningEffort.OFF && it != ReasoningEffort.DEFAULT
            },
            canDisable = true,
        ),
    )
}
