package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelPickerProjectorTest {
    @Test
    fun project_keepsOnlyEnabledProvidersAndModelsAndResolvesSelection() {
        val selected = model(id = "model-selected", displayName = "GPT 5.6", contextWindow = 1_050_000)
        val providers = listOf(
            provider(
                id = "disabled-provider",
                name = "Disabled",
                enabled = false,
                models = listOf(model(id = "hidden-provider-model")),
            ),
            provider(
                id = "no-enabled-models",
                name = "No enabled models",
                models = listOf(model(id = "disabled-only-model", enabled = false)),
            ),
            provider(
                id = "openai",
                name = "OpenAI",
                sourceType = ProviderSourceTypes.OPENAI,
                models = listOf(
                    model(id = "disabled-model", enabled = false),
                    selected,
                ),
            ),
        )

        val result = AgentModelPickerProjector.project(
            providers = providers,
            selectedProviderId = "openai",
            selectedModelId = selected.id,
        )

        assertEquals(listOf("openai"), result.providerGroups.map { it.providerId })
        assertEquals(listOf(selected.id), result.providerGroups.single().models.map { it.id })
        assertEquals(selected.id, result.selectedModel?.id)
        assertEquals(1_050_000, result.selectedModel?.contextWindow)
    }

    @Test
    fun project_hidesProvidersWithoutApiKeyButPreservesCurrentSelection() {
        val selected = model(id = "selected", displayName = "Selected")
        val result = AgentModelPickerProjector.project(
            providers = listOf(
                provider(
                    id = "missing-key",
                    apiKey = "   ",
                    models = listOf(selected),
                ),
                provider(
                    id = "configured",
                    models = listOf(model(id = "available")),
                ),
            ),
            selectedProviderId = "missing-key",
            selectedModelId = selected.id,
        )

        assertEquals(listOf("configured"), result.providerGroups.map { it.providerId })
        assertEquals(selected.id, result.selectedModel?.id)
        assertEquals("missing-key", result.selectedModel?.providerId)
    }

    @Test
    fun providerGroups_expandCurrentByDefault() {
        val selected = AgentModelOptionUi(
            id = "model",
            providerId = "current",
            providerName = "Current",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            modelId = "model",
            displayName = "Model",
            contextWindow = null,
        )
        val expanded = defaultExpandedModelProviderIds(selected)

        assertEquals(setOf("current"), expanded)
    }

    @Test
    fun latestContextUsage_usesLastMeasuredRoundAndSelectedWindow() {
        val selected = AgentModelOptionUi(
            id = "model",
            providerId = "provider",
            providerName = "Provider",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            modelId = "model",
            displayName = "Model",
            contextWindow = 100_000,
        )
        val messages = listOf(
            AgentMessageUi(id = "first", content = "one", usage = TokenUsageUi(contextTokens = 10_000)),
            AgentMessageUi(id = "missing", content = "two"),
            AgentMessageUi(id = "last", content = "three", usage = TokenUsageUi(contextTokens = 82_000)),
        )

        val usage = latestContextUsage(messages, selected)

        assertEquals(82_000, usage.contextTokens)
        assertEquals(100_000, usage.contextWindow)
        assertEquals(0.82f, usage.progress ?: 0f, 0.0001f)
        assertEquals("82K / 100K tokens · 82.0%", formatContextUsage(usage))
    }

    @Test
    fun contextUsageProgress_handlesMissingInvalidAndOverflowValues() {
        assertNull(contextUsageProgress(null, 100_000))
        assertNull(contextUsageProgress(10_000, null))
        assertNull(contextUsageProgress(10_000, 0))
        assertEquals(0f, contextUsageProgress(0, 100_000) ?: -1f, 0f)
        assertEquals(1f, contextUsageProgress(120_000, 100_000) ?: -1f, 0f)
        assertEquals("1.05M", formatCompactTokenCount(1_050_000))
        assertEquals(
            "No conversation context yet",
            formatContextUsage(AgentContextUsageUi(contextTokens = null, contextWindow = 100_000)),
        )
        assertEquals(
            "12K tokens\nThe current model does not provide a context limit",
            formatContextUsage(AgentContextUsageUi(contextTokens = 12_000, contextWindow = null)),
        )
    }

    @Test
    fun contextUsageFormattingUsesTheRequestedLocale() {
        assertEquals(
            "82K / 100K tokens · 82,0%",
            formatContextUsage(
                usage = AgentContextUsageUi(contextTokens = 82_000, contextWindow = 100_000),
                locale = java.util.Locale.GERMANY,
            ),
        )
    }


    @Test
    fun liveContextUsage_countsHistoryInputImagesAndFormattedFileReferences() {
        val selected = AgentModelOptionUi(
            id = "model",
            providerId = "provider",
            providerName = "Provider",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            modelId = "model",
            displayName = "Model",
            contextWindow = 8_000,
        )
        val history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "hello world"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "hi there"),
        )
        val images = listOf(
            PendingImageUi(
                id = "img",
                uri = "content://img",
                dataUrl = "data:image/png;base64,AA",
                mimeType = "image/png",
            ),
        )
        val files = listOf(
            PendingFileReferenceUi(
                id = "file",
                reference = AgentFileReference(
                    displayName = "notes.txt",
                    absolutePath = "/sdcard/notes.txt",
                    kind = AgentFileReferenceKind.File,
                ),
            ),
        )
        val usage = liveContextUsage(
            history = history,
            currentInput = "please read this",
            pendingImages = images,
            selectedModel = selected,
            pendingFileReferences = files,
        )
        val expectedPrompt = AgentFileReferencePromptCodec.format(
            "please read this",
            files.map { it.reference },
        )
        val expectedImages = images.map { it.toLiveModelImage() }
        val expected = history.sumOf { AgentContextBudget.countMessage(it) } +
            AgentContextBudget.countCurrentTurn(expectedPrompt, expectedImages)
        assertEquals(expected, usage.contextTokens)
        assertEquals(8_000, usage.contextWindow)
        assertTrue(expectedPrompt.contains("/sdcard/notes.txt"))
        assertTrue(expectedPrompt.contains("please read this"))
        assertEquals(images.single().dataUrl.length, expectedImages.single().bytes)
    }

    @Test
    fun liveContextUsage_nonVisionDoesNotCountImagePayload() {
        val selected = AgentModelOptionUi(
            id = "model",
            providerId = "provider",
            providerName = "Provider",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            modelId = "model",
            displayName = "Model",
            contextWindow = 8_000,
            supportsVision = false,
        )
        val huge = PendingImageUi(
            id = "img",
            uri = "content://img",
            dataUrl = "data:image/png;base64," + "A".repeat(50_000),
            mimeType = "image/png",
        )
        val withImage = liveContextUsage(
            history = emptyList(),
            currentInput = "看图",
            pendingImages = listOf(huge),
            selectedModel = selected,
        )
        val textOnly = liveContextUsage(
            history = emptyList(),
            currentInput = "看图",
            pendingImages = emptyList(),
            selectedModel = selected,
        )
        val vision = liveContextUsage(
            history = emptyList(),
            currentInput = "看图",
            pendingImages = listOf(huge),
            selectedModel = selected.copy(supportsVision = true),
        )
        assertTrue((withImage.contextTokens ?: 0) < (vision.contextTokens ?: 0) / 2)
        assertTrue((withImage.contextTokens ?: 0) > (textOnly.contextTokens ?: 0))
    }

    @Test
    fun liveContextUsage_emptyDraftDoesNotAddCurrentTurnOverhead() {
        val selected = AgentModelOptionUi(
            id = "model",
            providerId = "provider",
            providerName = "Provider",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            modelId = "model",
            displayName = "Model",
            contextWindow = 8_000,
        )
        val history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "hello world"),
        )
        val usage = liveContextUsage(
            history = history,
            currentInput = "",
            pendingImages = emptyList(),
            selectedModel = selected,
        )
        assertEquals(history.sumOf { AgentContextBudget.countMessage(it) }, usage.contextTokens)
    }

    @Test
    fun liveContextUsage_countsImagePayloadAndPrecomputedHistoryTokens() {
        val selected = AgentModelOptionUi(
            id = "model",
            providerId = "provider",
            providerName = "Provider",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            modelId = "model",
            displayName = "Model",
            contextWindow = 32_000,
        )
        val history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "hello world"),
        )
        val image = PendingImageUi(
            id = "img",
            uri = "content://img",
            dataUrl = "data:image/png;base64," + "A".repeat(4_096 * 90),
            mimeType = "image/png",
        )
        val usage = liveContextUsage(
            history = history,
            currentInput = "look",
            pendingImages = listOf(image),
            selectedModel = selected,
            historyTokenCount = 1_000,
        )
        val expected = 1_000 + AgentContextBudget.countCurrentTurn(
            "look",
            listOf(image.toLiveModelImage()),
        )
        assertEquals(expected, usage.contextTokens)
        assertTrue(AgentContextBudget.countImageTokens(image.toLiveModelImage()) > 85)
    }

    @Test
    fun isContextWindowExceeded_requiresConfiguredWindowAndNinetyNinePercent() {
        assertFalse(isContextWindowExceeded(AgentContextUsageUi(contextTokens = 99, contextWindow = null)))
        assertFalse(isContextWindowExceeded(AgentContextUsageUi(contextTokens = 98, contextWindow = 100)))
        assertTrue(isContextWindowExceeded(AgentContextUsageUi(contextTokens = 99, contextWindow = 100)))
        assertTrue(isContextWindowExceeded(AgentContextUsageUi(contextTokens = 120, contextWindow = 100)))
        assertFalse(
            shouldBlockSendForContextWindow(
                autoCompressEnabled = true,
                usage = AgentContextUsageUi(contextTokens = 99, contextWindow = 100),
            )
        )
        assertTrue(
            shouldBlockSendForContextWindow(
                autoCompressEnabled = false,
                usage = AgentContextUsageUi(contextTokens = 99, contextWindow = 100),
            )
        )
        assertFalse(
            shouldBlockSendForContextWindow(
                autoCompressEnabled = false,
                usage = AgentContextUsageUi(contextTokens = 98, contextWindow = 100),
            )
        )
    }

    @Test
    fun shouldShowLiveContextUsage_showsRingForEmptyConversationDraft() {
        val emptyDraft = AgentContextUsageUi(contextTokens = 0, contextWindow = 8_000)
        val typing = AgentContextUsageUi(contextTokens = 12, contextWindow = 8_000)
        val noWindow = AgentContextUsageUi(contextTokens = 12, contextWindow = null)
        assertFalse(
            shouldShowLiveContextUsage(
                showContextUsage = false,
                contextSendBlocked = false,
                usage = emptyDraft,
            )
        )
        assertTrue(
            shouldShowLiveContextUsage(
                showContextUsage = false,
                contextSendBlocked = false,
                usage = typing,
            )
        )
        assertFalse(
            shouldShowLiveContextUsage(
                showContextUsage = false,
                contextSendBlocked = false,
                usage = noWindow,
            )
        )
        assertTrue(
            shouldShowLiveContextUsage(
                showContextUsage = true,
                contextSendBlocked = false,
                usage = emptyDraft,
            )
        )
        assertTrue(
            shouldShowLiveContextUsage(
                showContextUsage = false,
                contextSendBlocked = true,
                usage = AgentContextUsageUi(contextTokens = 99, contextWindow = 100),
            )
        )
    }

    @Test
    fun project_exposesPreferredReasoningEffortForSelectedModel() {
        val selected = model(id = "model-selected").copy(preferredReasoningEffort = ReasoningEffort.HIGH)
        val other = model(id = "model-other").copy(preferredReasoningEffort = ReasoningEffort.LOW)
        val result = AgentModelPickerProjector.project(
            providers = listOf(
                provider(
                    id = "openai",
                    name = "OpenAI",
                    sourceType = ProviderSourceTypes.OPENAI,
                    models = listOf(selected, other),
                ),
            ),
            selectedProviderId = "openai",
            selectedModelId = selected.id,
        )
        assertEquals(ReasoningEffort.HIGH, result.selectedModel?.preferredReasoningEffort)
        assertEquals(
            listOf(ReasoningEffort.HIGH, ReasoningEffort.LOW),
            result.providerGroups.single().models.map { it.preferredReasoningEffort },
        )
    }

    private fun provider(
        id: String,
        name: String = id,
        sourceType: String = ProviderSourceTypes.CUSTOM,
        enabled: Boolean = true,
        apiKey: String = "test-key",
        models: List<Model>,
    ) = CustomProviderSetting(
        id = id,
        name = name,
        baseUrl = "https://example.com/$id",
        sourceType = sourceType,
        apiKey = apiKey,
        isEnabled = enabled,
        models = models,
    )

    private fun model(
        id: String,
        modelId: String = id,
        displayName: String = modelId,
        enabled: Boolean = true,
        contextWindow: Int? = null,
    ) = Model(
        id = id,
        modelId = modelId,
        displayName = displayName,
        isEnabled = enabled,
        contextWindow = contextWindow,
    )


    @Test
    fun liveContextUsage_usesBilledContextTokensInsteadOfLocalHistoryEstimate() {
        val selected = AgentModelOptionUi(
            id = "model",
            providerId = "provider",
            providerName = "Provider",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            modelId = "model",
            displayName = "Model",
            contextWindow = 500_000,
        )
        val history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "a".repeat(30_000)),
            AgentModelClient.ConversationMessage(role = "assistant", content = "b".repeat(30_000)),
        )
        val idle = liveContextUsage(
            history = history,
            currentInput = "",
            pendingImages = emptyList(),
            selectedModel = selected,
            billedContextTokens = 262_556,
        )
        assertEquals(262_556, idle.contextTokens)

        val typing = liveContextUsage(
            history = history,
            currentInput = "next question",
            pendingImages = emptyList(),
            selectedModel = selected,
            billedContextTokens = 262_556,
        )
        assertEquals(
            262_556 + AgentContextBudget.countCurrentTurn("next question", emptyList()),
            typing.contextTokens,
        )
        assertTrue((idle.contextTokens ?: 0) < history.sumOf { AgentContextBudget.countMessage(it) })
    }

    @Test
    fun liveContextUsage_addsRequestOverheadWhenThereIsNoBill() {
        val selected = AgentModelOptionUi(
            id = "model",
            providerId = "provider",
            providerName = "Provider",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            modelId = "model",
            displayName = "Model",
            contextWindow = 128_000,
        )
        val history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "hello"),
        )
        val local = history.sumOf { AgentContextBudget.countMessage(it) }
        val usage = liveContextUsage(
            history = history,
            currentInput = "",
            pendingImages = emptyList(),
            selectedModel = selected,
            requestOverheadTokens = 12_000,
        )
        assertEquals(local + 12_000, usage.contextTokens)
    }

    @Test
    fun liveContextUsage_appliesOverheadDeltaOnTopOfBill() {
        val selected = AgentModelOptionUi(
            id = "model",
            providerId = "provider",
            providerName = "Provider",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            modelId = "model",
            displayName = "Model",
            contextWindow = 500_000,
        )
        val unchanged = liveContextUsage(
            history = emptyList(),
            currentInput = "",
            pendingImages = emptyList(),
            selectedModel = selected,
            billedContextTokens = 262_556,
            requestOverheadTokens = 12_000,
            billedOverheadTokens = 12_000,
        )
        assertEquals(262_556, unchanged.contextTokens)
        val increased = liveContextUsage(
            history = emptyList(),
            currentInput = "",
            pendingImages = emptyList(),
            selectedModel = selected,
            billedContextTokens = 262_556,
            requestOverheadTokens = 14_500,
            billedOverheadTokens = 12_000,
        )
        assertEquals(265_056, increased.contextTokens)
        val decreased = liveContextUsage(
            history = emptyList(),
            currentInput = "",
            pendingImages = emptyList(),
            selectedModel = selected,
            billedContextTokens = 262_556,
            requestOverheadTokens = 10_000,
            billedOverheadTokens = 12_000,
        )
        assertEquals(260_556, decreased.contextTokens)
    }


    @Test
    fun latestBilledContextTokensUsesTotalThenAddsUnbilledUserTail() {
        val completed = listOf(
            UserMessageUi(id = "u1", content = "hi"),
            AgentMessageUi(
                id = "a1",
                content = "ok",
                usage = TokenUsageUi(inputTokens = 100, outputTokens = 10),
            ),
            UserMessageUi(id = "u2", content = "again"),
            AgentMessageUi(
                id = "a2",
                content = "sure",
                usage = TokenUsageUi(
                    contextTokens = 262_556,
                    inputTokens = 262_295,
                    outputTokens = 261,
                ),
            ),
        )
        assertEquals(262_556, latestBilledContextTokens(completed))

        val afterSend = completed + UserMessageUi(id = "u3", content = "next question")
        assertEquals(
            262_556 + AgentContextBudget.countCurrentTurn("next question", emptyList()),
            latestBilledContextTokens(afterSend),
        )
    }

    @Test
    fun windowTokensFromUsageFallsBackToInputPlusOutput() {
        assertEquals(
            262_556,
            windowTokensFromUsage(TokenUsageUi(inputTokens = 262_295, outputTokens = 261)),
        )
        assertEquals(
            262_556,
            windowTokensFromUsage(
                TokenUsageUi(contextTokens = 262_556, inputTokens = 1, outputTokens = 1),
            ),
        )
    }


    @Test
    fun clearBilledTokenUsageDropsAssistantUsage() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "hi"),
            AgentMessageUi(
                id = "a1",
                content = "ok",
                usage = TokenUsageUi(inputTokens = 262_295, outputTokens = 261),
            ),
        )
        val cleared = clearBilledTokenUsage(messages)
        assertNull((cleared[1] as AgentMessageUi).usage)
        assertEquals("hi", (cleared[0] as UserMessageUi).content)
    }

}
