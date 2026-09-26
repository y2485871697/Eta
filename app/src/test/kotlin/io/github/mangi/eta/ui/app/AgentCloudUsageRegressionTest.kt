package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentCloudUsageRegressionTest {
    @Test fun receivedUsageSurvivesRequestStartAndStoppedTailWithoutOldRoundFiltering() {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val app = AgentAppState(context, scope)
            val state = AgentChatHomeUiState(messages = listOf(
                ContextCompactedMessageUi("old-run-marker", 5, "summary", resumeRound = 9)),
                input = "", isStreaming = true, thinkingEnabled = false, providerId = "p", modelId = "m")
            call(app, "updateConversation", "c", state, false)
            call(app, "bindUsageRun", "new-run", "c")
            fun send(event: AgentEvent) { call(app, "applyRunEvent", "new-run", event, false, true) }
            fun current() = call(app, "conversationStateForRun", "new-run") as AgentChatHomeUiState
            send(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 152885)))
            assertEquals(152885, current().livePromptTokens)
            send(AgentEvent.ProviderRequestStarted(2))
            assertEquals(152885, current().livePromptTokens)
            send(AgentEvent.UsageReceived(2, AgentTokenUsage(outputTokens = 30)))
            assertEquals(152885, current().livePromptTokens)
            send(AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 999999), projected = true))
            assertEquals(152885, current().livePromptTokens)
            val field = app.javaClass.getDeclaredField("stoppingRuns").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(app) as MutableMap<String, Boolean>)["new-run"] = true
            send(AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 4000)))
            assertEquals(4000, current().livePromptTokens)
            call(app, "updateConversation", "c", current().copy(modelId = "other"), false)
            assertNull(current().livePromptTokens)
            call(app, "updateConversation", "c", current().copy(modelId = "m"), false)
            send(AgentEvent.UsageReceived(3, AgentTokenUsage(inputTokens = 90000)))
            assertNull(current().livePromptTokens)
        } finally { scope.cancel(); EtaDatabase.closeForTests() }
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
}
