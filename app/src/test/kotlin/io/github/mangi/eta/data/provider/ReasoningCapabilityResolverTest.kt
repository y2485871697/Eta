package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningCapabilityResolverTest {
    @Test
    fun userFacingEffortLabelsAreStableEnglishValues() {
        assertEquals(
            listOf("Off", "Default", "Minimal", "Low", "Medium", "High", "XHigh", "Max"),
            ReasoningEffort.entries.map(ReasoningEffort::displayName),
        )
    }

    @Test
    fun deepSeekCatalogExposesOnlyMeaningfulLevels() {
        val flash = resolve(ProviderSourceTypes.DEEPSEEK, "deepseek-v4-flash")
        val pro = resolve(ProviderSourceTypes.DEEPSEEK, "deepseek-v4-pro")

        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            flash.selectableEfforts,
        )
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            pro.selectableEfforts,
        )
        assertEquals(ReasoningEffort.HIGH, pro.normalize(ReasoningEffort.XHIGH))
    }

    @Test
    fun mandatoryKimiModelsNeverExposeOff() {
        assertEquals(
            listOf(
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            resolve(ProviderSourceTypes.MOONSHOT, "kimi-k3").selectableEfforts,
        )
        assertEquals(
            emptyList<ReasoningEffort>(),
            resolve(ProviderSourceTypes.MOONSHOT, "kimi-k2.7-code").selectableEfforts,
        )
    }

    @Test
    fun unverifiedModelsUseOpenMinisCeilingInsteadOfLoneDefault() {
        assertEquals(
            emptyList<ReasoningEffort>(),
            resolve(ProviderSourceTypes.MINIMAX, "MiniMax-M3").selectableEfforts,
        )
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
            ),
            resolve(ProviderSourceTypes.STEPFUN, "step-3.7-flash").selectableEfforts,
        )
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
            ),
            resolve(ProviderSourceTypes.CUSTOM, "unknown-thinking-model").selectableEfforts,
        )
    }

    @Test
    fun openMinisCatalogMatchesHyphenDotAndProviderPrefix() {
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.MINIMAL,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX,
            ),
            resolve(ProviderSourceTypes.OPENAI, "openai/gpt-5.6-sol").selectableEfforts,
        )
        assertEquals(
            ReasoningEffort.MAX,
            resolve(ProviderSourceTypes.ANTHROPIC, "claude-opus-4.6").supportedEfforts.last(),
        )
        assertEquals(
            ReasoningEffort.MAX,
            resolve(ProviderSourceTypes.OPENROUTER, "anthropic/claude-opus-4-8").supportedEfforts.last(),
        )
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
            ),
            resolve(ProviderSourceTypes.MIMO, "mimo-v2.5").selectableEfforts,
        )
        assertEquals(
            listOf(
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            ReasoningCapabilityResolver.resolve(
                sourceType = ProviderSourceTypes.CUSTOM,
                model = Model(
                    id = "glm",
                    modelId = "glm-5.2",
                    displayName = "GLM",
                    reasoning = true,
                    reasoningCapabilities = ModelReasoningCapabilities(
                        supportedEfforts = listOf(ReasoningEffort.HIGH, ReasoningEffort.MAX),
                        mandatory = true,
                    ),
                ),
            )?.selectableEfforts,
        )
    }

    @Test
    fun exactRemoteMetadataWinsOverProviderFamilyRules() {
        val remote = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.MEDIUM),
            defaultEffort = ReasoningEffort.MEDIUM,
            mandatory = true,
        )
        val resolved = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.DEEPSEEK,
            model = Model(
                id = "id",
                modelId = "deepseek-v4-flash",
                displayName = "DeepSeek",
                reasoning = true,
                reasoningCapabilities = remote,
            ),
        )

        assertEquals(remote, resolved)
        assertEquals(
            listOf(ReasoningEffort.MEDIUM),
            resolved?.selectableEfforts,
        )
    }

    @Test
    fun userOverrideWinsOverRemoteMetadata() {
        val overridden = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.MINIMAL),
            canDisable = true,
        )

        val resolved = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.DEEPSEEK,
            model = Model(
                id = "id",
                modelId = "deepseek-v4-flash",
                displayName = "DeepSeek",
                reasoning = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.HIGH),
                ),
                reasoningOverride = true,
                reasoningCapabilitiesOverride = overridden,
            ),
        )

        assertEquals(overridden, resolved)
    }

    @Test
    fun responsesRelayInfersOnlyExactOfficialModelUnlessExplicitlyDisabled() {
        val inferred = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.OPENAI,
            model = Model(id = "luna", modelId = "gpt-5.6-luna", displayName = "Luna"),
            inferExactCatalogModel = true,
        )
        val unknown = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.OPENAI,
            model = Model(id = "unknown", modelId = "relay-thinking", displayName = "Unknown"),
            inferExactCatalogModel = true,
        )
        val disabled = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.OPENAI,
            model = Model(
                id = "disabled",
                modelId = "gpt-5.6-luna",
                displayName = "Disabled",
                reasoning = false,
            ),
            inferExactCatalogModel = true,
        )

        assertEquals(ReasoningEffort.MEDIUM, inferred?.defaultEffort)
        assertNull(unknown)
        assertNull(disabled)
    }


    @Test
    fun grok46UsesCatalogLevelsEvenWithoutRemoteReasoningFlag() {
        val grok = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.CUSTOM,
            model = Model(
                id = "grok",
                modelId = "grok-4.6",
                displayName = "grok-4.6",
            ),
        )
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
            ),
            grok?.selectableEfforts,
        )
    }

    @Test
    fun emptyManualOverrideFallsBackToCatalogLevels() {
        val grok = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.CUSTOM,
            model = Model(
                id = "grok",
                modelId = "grok-4.6",
                displayName = "grok-4.6",
                reasoningOverride = true,
                reasoningCapabilitiesOverride = ModelReasoningCapabilities(
                    defaultEnabled = true,
                    mandatory = true,
                ),
            ),
        )
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
            ),
            grok?.selectableEfforts,
        )
    }
    private fun resolve(source: String, modelId: String): ModelReasoningCapabilities =
        requireNotNull(
            ReasoningCapabilityResolver.resolve(
                sourceType = source,
                model = Model(
                    id = "id-$modelId",
                    modelId = modelId,
                    displayName = modelId,
                    reasoning = true,
                ),
            )
        )
}
