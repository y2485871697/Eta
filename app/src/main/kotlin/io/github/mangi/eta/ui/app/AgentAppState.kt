package io.github.mangi.eta.ui.app

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import kotlin.jvm.Volatile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentChatImageCache
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.agent.media.MAX_AGENT_VIDEO_BYTES
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePolicy
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentCompressionEndpoint
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentRequestOverhead
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentImageGenerationClient
import io.github.mangi.eta.agent.model.AgentImageGenerationParser
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentVideoGenerationClient
import io.github.mangi.eta.agent.model.AgentVideoGenerationParser
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRunArchiveStore
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.EtaBackupExportOptions
import io.github.mangi.eta.data.repository.EtaBackupRepository
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.ProviderBalanceStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.RuntimeConfigRepository

import io.github.mangi.eta.ui.model.CloudContextUsageState
import io.github.mangi.eta.ui.model.ContextUsageScope
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.isSteerSupplement
import io.github.mangi.eta.ui.model.hasPartialAssistantAfterLastUser
import io.github.mangi.eta.ui.model.canContinueDisconnectedRun
import io.github.mangi.eta.ui.model.MessageSearchHit
import io.github.mangi.eta.ui.model.MessageSearchRoleLabels
import io.github.mangi.eta.ui.model.searchConversationMessages
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.AgentContextCompactionUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.conversationTokenUsage
import io.github.mangi.eta.ui.model.latestBilledContextTokens
import io.github.mangi.eta.ui.model.liveContextUsage
import io.github.mangi.eta.ui.model.cacheDisplayName
import io.github.mangi.eta.ui.model.toOutboundModelImage
import io.github.mangi.eta.ui.model.shouldBlockSendForContextWindow
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.filterForFolder
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.PendingConversationMentionUi
import io.github.mangi.eta.ui.model.ConversationMention
import io.github.mangi.eta.ui.model.toMentionedConversations
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.SkillNoticeUi
import io.github.mangi.eta.ui.model.SkillReplacementUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.fullImageSourceAt
import io.github.mangi.eta.ui.model.isVideoAt
import io.github.mangi.eta.ui.model.durationMsAt
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AgentAppState(
    context: Context,
    private val scope: CoroutineScope,
    skillZipImportGateway: SkillZipImportGateway? = null,
) {
    private val appContext = context.applicationContext
    private val conversationDrafts = ConversationDrafts(
        appContext.getSharedPreferences("conversation_input_drafts", Context.MODE_PRIVATE), scope,
    )

    fun currentDraftField() = conversationDrafts.field(selectedConversationId)

    private val skillZipImportGateway = skillZipImportGateway ?: CoreSkillZipImportGateway(appContext)
    private val runConversationIds = mutableMapOf<String, String>()
    private val runCloudUsage = mutableMapOf<String, CloudContextUsageState>()
    private val runUsageResumeRounds = mutableMapOf<String, Int>()
    private val runGeneratedAtMillis = mutableMapOf<String, Long>()
    // A stopped worker still owns its transcript until its terminal result is committed.
    private val stoppingRuns = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val modelRetryState = AgentRunRetryState()
    private val runOverheadTokens = mutableMapOf<String, Int>()
    private val runMessageProjector = AgentRunMessageProjector()
    private val runEventCoalescer = AgentRunEventCoalescer()
    private val runEventFlushJobs = mutableMapOf<String, Job>()
    private val runJobs = mutableMapOf<String, Job>()
    private val imageGenerationRunIds = mutableSetOf<String>()
    private val directMediaRuns = DirectMediaRunControl()
    private val timedOutChildVisibility = TimedOutChildVisibility()
    private data class PendingSteerDraft(
        val conversationId: String?, val imageIds: Set<String>, val fileIds: Set<String>,
        val mentionIds: Set<String> = emptySet(),
        val submittedText: String = "",
    )
    private val pendingSteerDrafts = mutableMapOf<String, PendingSteerDraft>()
    private var compressionJob: Job? = null
    private var pendingManualCompress: PendingManualCompress? = null
    private val pendingInRunCompactConversationIds = mutableSetOf<String>()
    private var pendingRetiredUsage = ConversationTokenUsageUi()
    private var pendingRetiredConversations = 0
    private var pendingRetiredMessages = 0
    private var pendingRetiredHeatmap = emptyMap<java.time.LocalDate, Int>()
    private val runCompressedDuringRun = mutableSetOf<String>()
    data class ContextBudgetPrompt(val runId: String, val conversationId: String, val reason: String)
    var contextBudgetPrompt by mutableStateOf<ContextBudgetPrompt?>(null)
        private set
    private val contextBudgetBlockedRuns = mutableMapOf<String, ContextBudgetPrompt>()

    fun dismissContextBudgetPrompt() { contextBudgetPrompt = null }

    fun allowCurrentRunCompaction(runId: String) {
        val prompt = contextBudgetBlockedRuns[runId] ?: return
        if (prompt.conversationId != selectedConversationId) return
        scope.launch {
            val sent = withContext(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).compactRun(
                    runId,
                    AgentContextCompactor.keepRecentFor(),
                )
            }
            if (sent) contextBudgetPrompt = null
            else Toast.makeText(appContext, "未能联系 Runtime，任务仍保持暂停。", Toast.LENGTH_LONG).show()
        }
    }

    fun stopBudgetBlockedRun(runId: String) {
        val prompt = contextBudgetBlockedRuns[runId] ?: return
        if (prompt.conversationId != selectedConversationId) return
        contextBudgetPrompt = null
        abandonPausedRun()
    }

    private val persistenceLock = Any()
    private var persistenceJob: Deferred<Boolean>? = null
    private val conversationPersistenceMutex = Mutex()
    @Volatile private var lastConversationPersistenceError: String? = null
    private var conversationArchiveBusy = false
    private var runtimeRefreshAfterArchive = false
    private var bindingRefreshAfterArchive = false
    private val deferredArchiveSaves = mutableListOf<Pair<kotlinx.coroutines.CompletableDeferred<Boolean>, (() -> Unit)?>>()
    private val runtimeRecoveryInProgress = AtomicBoolean(false)
    private val defaultThinkingEnabled = agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
    private val initialConversations = AgentConversationStore.load(appContext)
    private var skillNoticeSequence = 0L
    private var pendingSkillZipUri: Uri? = null
    private var pendingSkillZipSha256: String? = null
    private var selectionProviders: List<io.github.mangi.eta.data.model.ProviderSetting> = emptyList()
    private var defaultProviderId: String? = null
    private var defaultModelId: String? = null
    private var modelBindingGeneration = 0L
    private var memoryEditGeneration = 0L
    private var overheadGeneration = 0L

    private var currentReasoningCapabilities: ModelReasoningCapabilities? = null
    private var fileAttachmentOwnerVersion = 0L
    private val preparingConversationMentions = mutableSetOf<String>()
    private val chatImageCache = AgentChatImageCache(appContext)


    private var selectedConversationId: String? = initialConversations.selectedConversationId
    private var conversationsById: Map<String, AgentChatHomeUiState> = initialConversations.conversationsById
    private var conversationTitles: Map<String, String> = initialConversations.titles
    private var conversationUpdatedAt: Map<String, Long> = initialConversations.updatedAt
    private var conversationFolderIds: Map<String, String> = initialConversations.folderIds
    private var conversationPinned: Set<String> = initialConversations.pinnedIds
    private var conversationFolders: List<ConversationFolderUi> = initialConversations.folders
    private var selectedFolderId: String? = null
    private var pendingNewConversationFolderId: String? = null

    var homeState by mutableStateOf(
        selectedConversationId?.let(conversationsById::get) ?: emptyChatState(false)
    )
        private set

    var autoCompressEnabled by mutableStateOf(agentBooleanForUi(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED))
        private set

    var requestOverheadTokens by mutableStateOf(0)
        private set

    var billedOverheadTokens by mutableStateOf<Int?>(null)
        private set

    private var billedOverheadConversationId: String? = null

    var modelPickerState by mutableStateOf(AgentModelPickerUiState())
        private set

    var conversationPaneState by mutableStateOf(
        ConversationPaneUiState(
            conversations = emptyList(),
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
    )
        private set

    var pendingScrollToMessageId by mutableStateOf<String?>(null)
        private set

    var toolsState by mutableStateOf(buildToolsState(appContext))
        private set

    var skillsState by mutableStateOf(AgentSkillsUiState(isLoading = true))
        private set

    var permissionHealthState by mutableStateOf(PermissionHealthUiState(emptyList()))
        private set

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    init {
        refreshConversationSummaries()
        observeRuntimeSelection()
        observeAutoCompressEnabled()
        refreshRequestOverhead()
        ProviderBalanceStore.start(scope)
        scope.launch {
            RootAccess.state.collectLatest {
                refreshPermissionHealth()
                refreshRequestOverhead()
            }
        }
        runtimeRecoveryInProgress.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                chatImageCache.deleteOrphans(conversationsById.keys)
                recoverRuntimeRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    private fun observeAutoCompressEnabled() {
        val prefs = Prefs.localAgentPreferences() ?: return
        val overheadKeys = setOf(
            Prefs.Keys.AGENT_TERMINAL_TOOLS,
            Prefs.Keys.AGENT_BROWSER_TOOLS,
            Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
            Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
        )
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED) {
                autoCompressEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED)
            }
            if (key in overheadKeys) {
                refreshRequestOverhead()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun refreshRequestOverhead() {
        val overheadRequest = ++overheadGeneration
        val state = homeState
        val owner = selectedConversationId
        val generation = modelBindingGeneration
        val assistant = AssistantRepository.active()
        scope.launch(Dispatchers.IO) {
            val tokens = runCatching { estimateRequestOverhead(state, assistant) }.getOrDefault(0)
            withContext(Dispatchers.Main) {
                if (overheadRequest != overheadGeneration || owner != selectedConversationId || generation != modelBindingGeneration || AssistantRepository.active().id != assistant.id) return@withContext
                requestOverheadTokens = tokens
                syncBilledOverhead(selectedConversationId, homeState.messages)
            }
        }
    }

    private suspend fun estimateRequestOverhead(state: AgentChatHomeUiState, assistant: io.github.mangi.eta.data.model.AssistantProfile): Int {
        val config = runtimeConfigForBoundModel(state, assistant)?.copy(
            terminalTools = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = agentBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceDirectTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
        ) ?: return 0
        val enabledSkillIds = assistant.enabledSkillIds.toSet()
        val skillContext = SkillContext(
            installedSkills = runCatching {
                SkillRuntime.createIndexService(appContext)
                    .listSkillsForManagement()
                    .filter { it.installed && it.id in enabledSkillIds }
                    .filter { SkillCompatibilityChecker.evaluate(it).available }
            }.getOrDefault(emptyList()),
        )
        val memoryContext = if (assistant.memoryEnabled) {
            runCatching {
                AgentMemoryContextBuilder.build(
                    snapshot = AgentMemoryRepository.snapshot(assistant.id),
                    contextWindow = config.contextWindow,
                )
            }.getOrDefault(AgentMemoryContextBuilder.empty(config.contextWindow))
        } else {
            AgentMemoryContext.DISABLED
        }
        val additionalTools = JSONArray()
        runCatching {
            McpRunSnapshot.appendCachedModelTools(
                additionalTools,
                McpServerRepository.enabledServers(),
            )
        }
        return AgentRequestOverhead.estimate(
            config = config,
            skillContext = skillContext,
            memoryContext = memoryContext,
            capabilities = AgentToolCapabilities.capture(appContext),
            additionalTools = additionalTools,
        )
    }

    private fun syncBilledOverhead(
        conversationId: String?,
        messages: List<AgentChatMessageUi>,
    ) {
        val hasBilled = latestBilledContextTokens(messages) != null
        if (!hasBilled) {
            if (billedOverheadConversationId == conversationId) {
                billedOverheadTokens = null
            }
            return
        }
        if (billedOverheadConversationId != conversationId || billedOverheadTokens == null) {
            billedOverheadConversationId = conversationId
            billedOverheadTokens = requestOverheadTokens
        }
    }

    private fun observeRuntimeSelection() {
        scope.launch {
            combine(SettingsDataStore.settingsFlow(), ProviderRepository.providersFlow()) { settings, providers ->
                Triple(settings.selectedProviderId, settings.selectedModelId, providers)
            }.collectLatest { (providerId, modelId, providers) ->
                if (selectionProviders != providers) modelBindingGeneration++
                selectionProviders = providers
                defaultProviderId = providerId
                defaultModelId = modelId
                refreshBoundModelPicker()
            }
        }
    }

    /** Global defaults are used only for an unbound draft; never write them over an existing binding. */
    private fun refreshBoundModelPicker() {
        if (conversationArchiveBusy) {
            bindingRefreshAfterArchive = true
            return
        }
        val initializingDraftBinding = selectedConversationId == null && homeState.modelId.isBlank() && homeState.providerId.isBlank()
        if (homeState.modelId.isBlank() && homeState.providerId.isBlank() && selectionProviders.isNotEmpty()) {
            updateCurrentConversation(homeState.copy(providerId = defaultProviderId.orEmpty(), modelId = defaultModelId.orEmpty()))
            if (selectedConversationId != null) persistConversations()
        }
        val provider = selectionProviders.firstOrNull { it.id == homeState.providerId && it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == homeState.modelId && it.isEnabled }
        val projected = AgentModelPickerProjector.project(selectionProviders, homeState.providerId, homeState.modelId)
        modelPickerState = projected.copy(selectedModel = projected.selectedModel?.takeIf {
            provider != null && model != null && it.providerId == provider.id && it.id == model.id
        }, isChanging = false)
        currentReasoningCapabilities = if (provider != null && model != null)
            RuntimeConfigRepository.buildRuntimeConfig(provider, model, assistant = null).reasoningCapabilities else null
        // Display effective choices, but retain the user's saved preference until they explicitly change it.
        val next = if (initializingDraftBinding && model != null) {
            homeState.withPreferredReasoningEffort()
        } else homeState.copy(availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty())
        if (next != homeState) {
            val conversationId = selectedConversationId
            if (conversationId == null) homeState = next else updateConversation(conversationId, next, updateTimestamp = false)
        }
        refreshRequestOverhead()
    }

    private fun rejectSendIfModelUnavailable(): Boolean {
        val selected = modelPickerState.selectedModel
        if (!modelPickerState.isChanging && selected != null && selected.providerId == homeState.providerId && selected.id == homeState.modelId) return false
        Toast.makeText(appContext, "会话绑定的模型尚未就绪或已不可用，请明确选择模型后再发送。", Toast.LENGTH_LONG).show()
        return true
    }

    private val modelReasoningMemory = HashMap<String, ReasoningEffort>()

    private fun modelReasoningKey(providerId: String, modelId: String): String =
        providerId + "\u0000" + modelId

    private fun rememberedModelReasoningEffort(
        providerId: String,
        modelId: String,
        stored: ReasoningEffort?,
    ): ReasoningEffort = modelReasoningMemory[modelReasoningKey(providerId, modelId)] ?: stored ?: ReasoningEffort.OFF

    private fun rememberModelReasoningEffort(providerId: String, modelId: String, effort: ReasoningEffort) {
        if (providerId.isBlank() || modelId.isBlank()) return
        modelReasoningMemory[modelReasoningKey(providerId, modelId)] = effort
        scope.launch(Dispatchers.IO) {
            val model = ModelRepository.modelsByProvider(providerId).firstOrNull { it.id == modelId } ?: return@launch
            if (model.preferredReasoningEffort == effort) return@launch
            ModelRepository.saveModel(providerId, model.copy(preferredReasoningEffort = effort))
        }
    }

    private fun preferredReasoningEffortForCurrentModel(): ReasoningEffort {
        val model = modelPickerState.selectedModel
        val preferred = if (model == null) {
            ReasoningEffort.OFF
        } else {
            rememberedModelReasoningEffort(model.providerId, model.id, model.preferredReasoningEffort)
        }
        return ConversationReasoningPolicy.resolve(preferred, currentReasoningCapabilities)
    }

    private fun AgentChatHomeUiState.withPreferredReasoningEffort(): AgentChatHomeUiState {
        val normalized = preferredReasoningEffortForCurrentModel()
        return copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
        )
    }

    fun refreshRuntimeResults() {
        if (conversationArchiveBusy) {
            runtimeRefreshAfterArchive = true
            return
        }
        if (!runtimeRecoveryInProgress.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                recoverRuntimeRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }


    fun refreshMemory() {
        val owner = AssistantRepository.active().id
        val generation = ++memoryEditGeneration
        memoryState = AgentMemoryUiState(assistantId = owner, isLoading = true)
        scope.launch(Dispatchers.IO) {
            val result = runCatching { AgentMemoryRepository.snapshot(owner) }
            withContext(Dispatchers.Main) {
                if (generation != memoryEditGeneration || memoryState.assistantId != owner) return@withContext
                result.fold(onSuccess = { snapshot ->
                    memoryState = AgentMemoryUiState(assistantId = owner, baseRevision = snapshot.revision,
                        enabled = AgentMemoryRepository.isEnabled(owner), isLoading = false,
                        draft = snapshot.content, savedContent = snapshot.content, draftBytes = snapshot.byteSize)
                    refreshRequestOverhead()
                }, onFailure = { error ->
                    memoryState = memoryState.copy(isLoading = false, notice = error.message ?: "读取记忆失败")
                })
            }
        }
    }

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(draft = content, draftBytes = content.toByteArray(Charsets.UTF_8).size, notice = null)
    }

    fun setMemoryEnabled(enabled: Boolean) {
        val owner = memoryState.assistantId.takeIf { it.isNotBlank() } ?: return
        val generation = memoryEditGeneration
        scope.launch(Dispatchers.IO) {
            val result = runCatching { AgentMemoryRepository.setEnabled(enabled, owner) }
            withContext(Dispatchers.Main) {
                if (generation != memoryEditGeneration || memoryState.assistantId != owner) return@withContext
                memoryState = if (result.isSuccess) memoryState.copy(enabled = enabled, notice = null)
                    else memoryState.copy(notice = result.exceptionOrNull()?.message ?: "记忆开关保存失败")
                refreshRequestOverhead()
            }
        }
    }

    fun saveMemory() {
        if (memoryState.canSave) writeMemoryDraft(memoryState.draft)
    }

    fun clearMemory() {
        if (!memoryState.isLoading && !memoryState.isSaving) writeMemoryDraft("")
    }

    private fun writeMemoryDraft(target: String) {
        val state = memoryState
        if (state.assistantId.isBlank() || state.baseRevision.isBlank() || state.isSaving) return
        if (AssistantRepository.active().id != state.assistantId) {
            memoryState = memoryState.copy(notice = "当前草稿属于另一助手，未保存；请返回原助手或重新读取。")
            return
        }
        val generation = memoryEditGeneration
        memoryState = state.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            val result = runCatching { AgentMemoryRepository.replaceAll(target, state.assistantId, state.baseRevision) }
            withContext(Dispatchers.Main) {
                if (generation != memoryEditGeneration || memoryState.assistantId != state.assistantId) return@withContext
                result.fold(onSuccess = { snapshot ->
                    val draft = if (memoryState.draft == state.draft) snapshot.content else memoryState.draft
                    memoryState = memoryState.copy(isSaving = false, baseRevision = snapshot.revision,
                        savedContent = snapshot.content, draft = draft, draftBytes = draft.toByteArray(Charsets.UTF_8).size,
                        notice = "记忆已保存")
                    refreshRequestOverhead()
                }, onFailure = { error ->
                    memoryState = memoryState.copy(isSaving = false, notice = error.message ?: "记忆保存失败，草稿已保留")
                })
            }
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }

    private fun rejectConversationArchiveMutation(): Boolean {
        if (stoppingRuns.keys.any { runConversationIds[it] == selectedConversationId }) {
            Toast.makeText(appContext, "已停止，正在保存本轮上下文，请稍后再操作。", Toast.LENGTH_SHORT).show()
            return true
        }
        if (!conversationArchiveBusy && !io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance) return false
        Toast.makeText(appContext, "正在导入或导出对话，请完成后再修改。", Toast.LENGTH_SHORT).show()
        return true
    }

    private suspend fun <T> withConversationArchive(
        requireIdleRuns: Boolean = true,
        block: suspend () -> T,
    ): T = withContext(Dispatchers.Main.immediate) {
        check(!conversationArchiveBusy) { "已有对话归档任务正在执行" }
        if (requireIdleRuns) {
            check(
                !runtimeRecoveryInProgress.get() &&
                    !modelPickerState.isChanging &&
                    pendingManualCompress == null &&
                    runJobs.isEmpty() &&
                    compressionJob?.isActive != true &&
                    conversationsById.values.none { it.isStreaming || it.isCompressingContext },
            ) { "请先等待对话任务或恢复完成" }
        }
        conversationArchiveBusy = true
        try {
            // Force a fresh save, and inspect its Boolean result instead of only joining a Job.
            val saved = withContext(Dispatchers.Main.immediate) { persistConversations(allowArchive = true) }
            check(saved.await()) { "当前对话保存失败，归档任务未开始，请重试" }
            conversationPersistenceMutex.withLock { block() }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                conversationArchiveBusy = false
                if (runtimeRefreshAfterArchive) {
                    runtimeRefreshAfterArchive = false
                    refreshRuntimeResults()
                }
                if (bindingRefreshAfterArchive) {
                    bindingRefreshAfterArchive = false
                    refreshBoundModelPicker()
                }
                val deferred = deferredArchiveSaves.toList()
                deferredArchiveSaves.clear()
                if (deferred.isNotEmpty()) scope.launch {
                    val saved = persistConversations().await()
                    deferred.forEach { (completion, callback) ->
                        if (saved) callback?.invoke()
                        completion.complete(saved)
                    }
                }
            }
        }
    }

    suspend fun exportBackup(
        output: OutputStream,
        options: EtaBackupExportOptions = EtaBackupExportOptions(),
    ): EtaBackupSummary = withConversationArchive(requireIdleRuns = false) {
        EtaBackupRepository.export(appContext, output, options)
    }

    suspend fun exportConversation(conversationId: String, output: OutputStream): EtaBackupSummary =
        withConversationArchive(requireIdleRuns = false) {
            EtaBackupRepository.exportConversation(appContext, conversationId, output)
        }

    suspend fun importBackup(input: InputStream): EtaBackupSummary = withConversationArchive {
        val activeRunQuery = withContext(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRun()
        }
        when (val active = activeRunQuery) {
            is AgentRuntimeClient.ActiveRunQuery.Known -> check(active.runId == null) { "请先停止正在运行的 Agent 任务" }
            AgentRuntimeClient.ActiveRunQuery.Unavailable -> error("无法确认 Agent Runtime 状态，请稍后重试")
        }
        withContext(kotlinx.coroutines.NonCancellable) {
            try {
                EtaBackupRepository.import(appContext, input)
            } finally {
                // Even a failed commit-marker fsync may have committed metadata already.
                // Never unlock with stale in-memory data capable of overwriting the database.
                reloadConversationsAfterBackup()
            }
        }
    }

    private suspend fun reloadConversationsAfterBackup() {
        val snapshot = withContext(Dispatchers.IO) {
            AgentConversationStore.load(appContext)
        }
        withContext(Dispatchers.Main.immediate) {
            selectedConversationId = snapshot.selectedConversationId
            conversationsById = snapshot.conversationsById
            conversationTitles = snapshot.titles
            conversationUpdatedAt = snapshot.updatedAt
            conversationFolderIds = snapshot.folderIds
            conversationPinned = snapshot.pinnedIds
            conversationFolders = snapshot.folders
            selectedFolderId = null
            pendingNewConversationFolderId = null
            fileAttachmentOwnerVersion += 1
            homeState = selectedConversationId
                ?.let(conversationsById::get)
                ?: newDraftChatState()
            conversationPaneState = conversationPaneState.copy(
                selectedConversationId = selectedConversationId,
                searchQuery = "",
            )
            refreshConversationSummaries()
            restoreConversationRuntimeModel()
        }
    }

    /** 用 checkpoint、终态 outbox 与 active session 一次性对账，避免用进程存活推断 run 状态。 */
    private suspend fun recoverRuntimeRuns() {
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        val checkpoints = withContext(Dispatchers.IO) {
            AgentRunCheckpointStore.list(appContext)
        }
        val initialCompletedQuery = client.queryCompletedRuns()
        if (initialCompletedQuery is AgentRuntimeClient.CompletedRunsQuery.Unavailable) {
            AndroidAgentLogger.warnThrottled("agent_ui_drain_results_failed") {
                "Agent UI pending result recovery failed"
            }
        }
        val initialCompletedRuns =
            (initialCompletedQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val activeRunQuery = client.queryActiveRun()
        val terminalRaceQuery = if (
            activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known && checkpoints.isNotEmpty()
        ) {
            client.queryCompletedRuns()
        } else {
            initialCompletedQuery
        }
        val terminalRaceCompletedRuns =
            (terminalRaceQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val completedRuns = (initialCompletedRuns + terminalRaceCompletedRuns)
            .associateBy { completed ->
                completed.result.runId.ifBlank { completed.handoff.id }
            }
            .values
            .toList()
        val activeStateKnown = activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known
        val terminalStateKnown = terminalRaceQuery is AgentRuntimeClient.CompletedRunsQuery.Known
        val activeRunIds = (activeRunQuery as? AgentRuntimeClient.ActiveRunQuery.Known)
            ?.runIds
            .orEmpty()
        val locallyObservedRunIds = withContext(Dispatchers.Main) {
            // Conversation bindings route events; only subscriber jobs prove local observation.
            runJobs.keys.toSet()
        }
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = checkpoints,
            completedRuns = completedRuns,
            activeStateKnown = activeStateKnown,
            terminalStateKnown = terminalStateKnown,
            activeRunIds = activeRunIds,
            locallyObservedRunIds = locallyObservedRunIds,
        )
        if (
            plan.completed.isEmpty() &&
            plan.interrupted.isEmpty() &&
            plan.reattach.isEmpty()
        ) {
            return
        }

        val acknowledgeAfterSave = mutableListOf<String>()
        val removeAfterSave = mutableListOf<String>()
        val changed = withContext(Dispatchers.Main) {
            var stateChanged = false
            plan.completed.forEach { recoveryPlan ->
                val completedRun = recoveryPlan.result
                val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
                val payload = AgentUiHandoffPayload.from(completedRun.handoff.payload)
                val conversationId = payload.conversationId
                val state = conversationsById[conversationId] ?: return@forEach
                recoveryPlan.checkpoint?.let { checkpoint ->
                    stateChanged = restoreCheckpointTrace(
                        checkpoint = checkpoint,
                        interrupted = false,
                    ) || stateChanged
                }
                val result = completedRun.result
                val recovery = AgentPendingResultRecovery.apply(
                    state = conversationsById[conversationId] ?: state,
                    runId = runId,
                    result = result,
                    promptSupplement = payload.promptSupplement,
                    supplements = payload.supplements,
                    generatedAtMillis = recoveryPlan.checkpoint?.events
                        ?.filterIsInstance<AgentEvent.RunFinished>()?.lastOrNull()?.generatedAtMillis,
                )
                if (recovery.alreadyApplied) {
                    acknowledgeAfterSave += runId
                    return@forEach
                }
                updateConversation(conversationId, recovery.state)
                acknowledgeAfterSave += runId
                stateChanged = true
            }

            plan.interrupted.forEach { checkpoint ->
                removeAfterSave += checkpoint.runId
                stateChanged = restoreCheckpointTrace(
                    checkpoint = checkpoint,
                    interrupted = true,
                ) || stateChanged
            }
            if (stateChanged) refreshConversationSummaries()
            stateChanged || acknowledgeAfterSave.isNotEmpty() || removeAfterSave.isNotEmpty()
        }

        if (changed) {
            val saved = withContext(Dispatchers.Main) { persistConversations() }.await()
            if (saved) {
                acknowledgeAfterSave.forEach(client::ackResult)
                removeAfterSave.forEach { runId ->
                    AgentRunCheckpointStore.remove(appContext, runId)
                }
            }
        }

        plan.reattach.forEach { checkpoint ->
            withContext(Dispatchers.Main) { startReattachedRun(checkpoint) }
        }
    }

    /** 把安全事件恢复为 UI 轨迹；半截回复不进入模型 history，设备工具也不会重放。 */
    private fun restoreCheckpointTrace(
        checkpoint: AgentRunCheckpointStore.Checkpoint,
        interrupted: Boolean,
    ): Boolean {
        val runId = checkpoint.runId
        if (runId.isBlank()) return false
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        val existing = conversationsById[conversationId] ?: return false
        if (AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return false

        bindRunConversation(runId, conversationId)
        updateConversation(conversationId, existing.copy(isStreaming = true))
        restoreRunEvents(runId, checkpoint.events)
        flushPendingRunDelta(runId)
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            if (interrupted) {
                val interruptedTools = runMessageProjector.interruptRunningTools(
                    reason = appContext.getString(R.string.system_notice_interrupted),
                    messages = finalizedText,
                )
                val noticeId = "interrupted-$runId"
                if (interruptedTools.any { it.id == noticeId }) {
                    interruptedTools
                } else {
                    interruptedTools + SystemNoticeMessageUi(
                        id = noticeId,
                        code = SystemNoticeCode.Interrupted,
                    )
                }
            } else {
                runMessageProjector.finalizeRun(runId, finalizedText)
            }
        }
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runGeneratedAtMillis.remove(runId)
        runConversationIds.remove(runId)
        runCloudUsage.remove(runId)
        runUsageResumeRounds.remove(runId)
        runOverheadTokens.remove(runId)
        conversationUpdatedAt = conversationUpdatedAt +
            (conversationId to checkpoint.updatedAt)
        return true
    }

    private fun startReattachedRun(checkpoint: AgentRunCheckpointStore.Checkpoint) {
        val runId = checkpoint.runId
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        val existing = conversationsById[conversationId] ?: return
        if (runId in runJobs || AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return

        bindRunConversation(runId, conversationId)
        updateConversation(conversationId, existing.copy(isStreaming = true))
        refreshConversationSummaries()
        runJobs[runId] = scope.launch(Dispatchers.IO) {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            val outcome = client.attachRun(
                runId = runId,
                onReplay = { events -> restoreRunEvents(runId, events) },
                onEvent = { event -> enqueueRunEvent(runId, event) },
            )
            when (outcome) {
                is AgentRuntimeClient.AttachOutcome.Completed -> withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId = runId,
                        result = outcome.result,
                        acknowledgeRuntimeResult = true,
                    )
                }
                AgentRuntimeClient.AttachOutcome.NotActive -> {
                    withContext(Dispatchers.Main) {
                        if (runJobs.remove(runId) != null) {
                            setConversationStreaming(runId, false)
                        }
                    }
                    recoverRuntimeRuns()
                }
                AgentRuntimeClient.AttachOutcome.Unavailable -> withContext(Dispatchers.Main) {
                    // Losing the subscriber is not evidence that Runtime stopped the run.
                    // Keep its trace and binding until foreground recovery can query Runtime.
                    runJobs.remove(runId)
                }
            }
        }
    }

    private suspend fun importArchivedExternalRuns() {
        val archivedRuns = withContext(Dispatchers.IO) {
            AgentRunArchiveStore.list(appContext)
                .filter { AgentExternalArchivePayload.from(it.handoff.payload) != null }
        }
        if (archivedRuns.isEmpty()) return

        withContext(Dispatchers.Main) {
            if (conversationArchiveBusy) return@withContext // Keep archive files for the next replay.
            val importedRunIds = archivedRuns.mapNotNull { archivedRun ->
                importExternalRun(archivedRun)
            }
            refreshConversationSummaries()
            persistConversations {
                importedRunIds.forEach { runId ->
                    AgentRunArchiveStore.remove(appContext, runId)
                }
            }
        }
    }

    suspend fun openAssistantConversation(conversationKey: String): Boolean {
        if (withContext(Dispatchers.Main.immediate) { rejectConversationArchiveMutation() }) return false
        if (conversationKey.isBlank()) return false
        importArchivedExternalRuns()
        return withContext(Dispatchers.Main.immediate) {
            val conversationId = archiveConversationId(
                source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                conversationKey = conversationKey,
            )
            if (conversationsById[conversationId] == null) {
                false
            } else {
                selectConversation(conversationId)
                true
            }
        }
    }

    private fun importExternalRun(archivedRun: AgentRunArchiveStore.ArchivedRun): String? {
        val runId = archivedRun.result.runId.ifBlank { archivedRun.handoff.id }
        if (runId.isBlank()) return null
        val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload) ?: return null
        val conversationId = archiveConversationId(
            source = archivedRun.handoff.source,
            conversationKey = payload.conversationKey,
        )
        val archivedEffort = payload.reasoningEffort
            ?: payload.thinkingEnabled?.let(ReasoningEffort::fromLegacy)
            ?: ReasoningEffort.fromLegacy(defaultThinkingEnabled)
        val existingState = conversationsById[conversationId] ?: emptyChatState(
            archivedEffort.enablesReasoning
        ).copy(reasoningEffort = archivedEffort)
        val alreadyImported = AgentRuntimeHistoryReducer.wasApplied(existingState, runId) ||
            existingState.messages.any {
                it is AgentMessageUi &&
                    (it.id == "assistant-$runId" || it.id.startsWith("assistant-$runId-")) &&
                    !it.isStreaming
            }
        if (alreadyImported) return runId

        if (conversationTitles[conversationId].isNullOrBlank()) {
            conversationTitles = conversationTitles + (conversationId to payload.title)
        }
        bindRunConversation(runId, conversationId)
        updateConversation(
            conversationId,
            existingState.copy(
                input = "",
                isStreaming = true,
                thinkingEnabled = archivedEffort.enablesReasoning,
                reasoningEffort = archivedEffort,
                pendingImages = emptyList(),
                messages = existingState.messages +
                    UserMessageUi(
                        id = "user-$runId",
                        content = payload.userText,
                        images = archivedRun.userImagePreviews,
                    ) +
                    AgentMessageUi(
                        id = "assistant-$runId",
                        content = "",
                        isStreaming = true,
                        renderMarkdown = false,
                    ),
            )
        )
        archivedRun.events.forEach { event -> applyRunEvent(runId, event) }
        applyRunResult(runId, archivedRun.result)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to archivedRun.createdAt)
        return runId
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        updateReasoningEffort(ReasoningEffort.fromLegacy(enabled))
    }

    fun updateReasoningEffort(effort: ReasoningEffort) {
        if (rejectConversationArchiveMutation()) return
        if (homeState.isStreaming && !homeState.isPaused) return
        val normalized = ConversationReasoningPolicy.resolve(effort, currentReasoningCapabilities)
        if (normalized == homeState.reasoningEffort) return
        if (homeState.isPaused) abandonPausedRun()
        updateCurrentConversation(
            homeState.copy(
                thinkingEnabled = normalized.enablesReasoning,
                reasoningEffort = normalized,
            )
        )
        rememberModelReasoningEffort(homeState.providerId, homeState.modelId, normalized)
        if (selectedConversationId != null) persistConversations()
    }

    fun selectModel(modelId: String, providerId: String = "") {
        if (rejectConversationArchiveMutation()) return
        if (homeState.isStreaming && !homeState.isPaused) return
        val provider = selectionProviders.filter { it.isEnabled && (providerId.isBlank() || it.id == providerId) && it.models.any { m -> m.id == modelId && m.isEnabled } }.singleOrNull() ?: return
        val model = provider.models.first { it.id == modelId && it.isEnabled }
        if (homeState.providerId == provider.id && homeState.modelId == model.id) return
        if (homeState.isPaused) abandonPausedRun()
        modelBindingGeneration++
        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model, assistant = null)
        val requestedEffort = rememberedModelReasoningEffort(provider.id, model.id, model.preferredReasoningEffort)
        val nextEffort = ConversationReasoningPolicy.resolve(requestedEffort, config.reasoningCapabilities)
        updateCurrentConversation(homeState.copy(providerId = provider.id, modelId = model.id,
            reasoningEffort = nextEffort, thinkingEnabled = nextEffort.enablesReasoning,
            livePromptTokens = null))
        billedOverheadTokens = null
        refreshBoundModelPicker()
        if (nextEffort != requestedEffort) Toast.makeText(appContext,
            "已按新模型支持的档位调整当前对话的思考深度", Toast.LENGTH_SHORT).show()
        if (selectedConversationId != null) persistConversations()
    }

    fun updateSearchQuery(query: String) {
        conversationPaneState = conversationPaneState.copy(searchQuery = query)
    }

    fun searchHistory(
        query: String,
        currentConversationOnly: Boolean = false,
    ): List<MessageSearchHit> {
        val conversations = if (currentConversationOnly) {
            currentConversationSearchScope()
        } else {
            conversationsById.toMutableMap().also { all ->
                val currentId = selectedConversationId
                if (currentId != null) {
                    all[currentId] = homeState
                } else if (homeState.messages.isNotEmpty()) {
                    all[""] = homeState
                }
            }
        }
        return searchConversationMessages(
            conversations = conversations,
            titles = conversationTitles,
            updatedAt = conversationUpdatedAt,
            query = query,
            unnamedTitle = appContext.getString(R.string.conversation_unnamed),
            roleLabels = MessageSearchRoleLabels(
                user = appContext.getString(R.string.search_history_role_user),
                assistant = appContext.getString(R.string.search_history_role_assistant),
                thinking = appContext.getString(R.string.search_history_role_thinking),
                tool = appContext.getString(R.string.search_history_role_tool),
            ),
        )
    }

    private fun currentConversationSearchScope(): Map<String, AgentChatHomeUiState> {
        val currentId = selectedConversationId
        return when {
            currentId != null -> mapOf(currentId to homeState)
            homeState.messages.isNotEmpty() -> mapOf("" to homeState)
            else -> emptyMap()
        }
    }

    fun openHistorySearchHit(hit: MessageSearchHit) {
        if (hit.conversationId.isNotEmpty() && hit.conversationId != selectedConversationId) {
            selectConversation(hit.conversationId)
        }
        pendingScrollToMessageId = hit.messageId
    }

    fun consumePendingScrollToMessage() {
        pendingScrollToMessageId = null
    }

    fun selectConversation(conversationId: String) {
        if (rejectConversationArchiveMutation()) return
        if (homeState.messageEdit != null) cancelMessageEdit()
        val state = conversationsById[conversationId] ?: return
        fileAttachmentOwnerVersion += 1
        selectedConversationId = conversationId
        val resolvedState = state
        conversationsById = conversationsById + (conversationId to resolvedState)
        homeState = resolvedState
        billedOverheadConversationId = null
        billedOverheadTokens = null
        syncBilledOverhead(conversationId, resolvedState.messages)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        persistConversations()
        restoreConversationRuntimeModel()
    }

    fun createConversation() {
        if (rejectConversationArchiveMutation()) return
        if (homeState.messageEdit != null) cancelMessageEdit()
        fileAttachmentOwnerVersion += 1
        selectedConversationId = null
        pendingNewConversationFolderId = selectedFolderId
        homeState = newDraftChatState()
        rememberModelReasoningEffort(homeState.providerId, homeState.modelId, homeState.reasoningEffort)
        billedOverheadConversationId = null
        billedOverheadTokens = null
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = null,
            searchQuery = "",
        )
        restoreConversationRuntimeModel()
        refreshConversationSummaries()
    }

    fun selectFolder(folderId: String?) {
        if (rejectConversationArchiveMutation()) return
        selectedFolderId = folderId
        refreshConversationSummaries()
    }

    fun createFolder(name: String) {
        if (rejectConversationArchiveMutation()) return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val folder = ConversationFolderUi(
            id = "folder-${UUID.randomUUID()}",
            name = trimmed,
            sortIndex = (conversationFolders.maxOfOrNull { it.sortIndex } ?: -1) + 1,
        )
        conversationFolders = conversationFolders + folder
        selectedFolderId = folder.id
        refreshConversationSummaries()
        persistConversations()
    }

    fun renameFolder(folderId: String, name: String) {
        if (rejectConversationArchiveMutation()) return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        conversationFolders = conversationFolders.map { folder ->
            if (folder.id == folderId) folder.copy(name = trimmed) else folder
        }
        refreshConversationSummaries()
        persistConversations()
    }

    fun deleteFolder(folderId: String) {
        if (rejectConversationArchiveMutation()) return
        conversationFolders = conversationFolders.filterNot { it.id == folderId }
        conversationFolderIds = conversationFolderIds.filterValues { it != folderId }
        if (selectedFolderId == folderId) selectedFolderId = null
        if (pendingNewConversationFolderId == folderId) pendingNewConversationFolderId = null
        refreshConversationSummaries()
        persistConversations()
    }

    fun moveConversationToFolder(conversationId: String, folderId: String?) {
        if (rejectConversationArchiveMutation()) return
        if (conversationId !in conversationsById) return
        conversationFolderIds = if (folderId.isNullOrBlank()) {
            conversationFolderIds - conversationId
        } else {
            conversationFolderIds + (conversationId to folderId)
        }
        refreshConversationSummaries()
        persistConversations()
    }

    fun toggleConversationPinned(conversationId: String) {
        if (rejectConversationArchiveMutation()) return
        if (conversationId !in conversationsById) return
        conversationPinned = if (conversationId in conversationPinned) {
            conversationPinned - conversationId
        } else {
            conversationPinned + conversationId
        }
        refreshConversationSummaries()
        persistConversations()
    }

    fun deleteAllConversations() {
        if (rejectConversationArchiveMutation()) return
        val ids = conversationsById.keys.toList()
        if (ids.isEmpty()) return
        conversationsById.forEach { (id, state) ->
            retainDeletedConversation(state.messages, conversationUpdatedAt[id])
        }
        conversationDrafts.clear()
        conversationsById = emptyMap()
        conversationTitles = emptyMap()
        conversationUpdatedAt = emptyMap()
        conversationFolderIds = emptyMap()
        conversationPinned = emptySet()
        scope.launch(Dispatchers.IO) {
            ids.forEach { chatImageCache.deleteConversation(it) }
        }
        fileAttachmentOwnerVersion += 1
        selectedConversationId = null
        pendingNewConversationFolderId = selectedFolderId
        homeState = newDraftChatState()
        conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
        refreshConversationSummaries()
        val deletion = persistConversations()
        scope.launch(Dispatchers.IO) {
            if (deletion.await()) {
                ids.forEach { id ->
                    runCatching { io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, id).delete() }
                        .onFailure { AndroidAgentLogger.warn("压缩原文清理失败：${it.javaClass.simpleName}") }
                }
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        if (rejectConversationArchiveMutation()) return
        val wasSelected = selectedConversationId == conversationId
        conversationsById[conversationId]?.let { state ->
            retainDeletedConversation(state.messages, conversationUpdatedAt[conversationId])
        }
        conversationDrafts.remove(conversationId)
        conversationsById = conversationsById - conversationId
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        conversationFolderIds = conversationFolderIds - conversationId
        conversationPinned = conversationPinned - conversationId
        scope.launch(Dispatchers.IO) { chatImageCache.deleteConversation(conversationId) }
        if (wasSelected) {
            fileAttachmentOwnerVersion += 1
            val nextId = conversationsById.keys.firstOrNull()
            if (nextId != null) {
                selectedConversationId = nextId
                homeState = conversationsById.getValue(nextId)
                conversationsById = conversationsById + (nextId to homeState)
            } else {
                selectedConversationId = null
                homeState = newDraftChatState()
            }
        }
        conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
        if (wasSelected) restoreConversationRuntimeModel()
        refreshConversationSummaries()
        val deletion = persistConversations()
        scope.launch(Dispatchers.IO) {
            if (deletion.await()) {
                listOf(conversationId).forEach { id ->
                    runCatching { io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, id).delete() }
                        .onFailure { AndroidAgentLogger.warn("压缩原文清理失败：${it.javaClass.simpleName}") }
                }
            }
        }
    }

    private val manuallyRenamedDuringTitleRequest = mutableSetOf<String>()

    fun renameConversation(conversationId: String, title: String) {
        if (rejectConversationArchiveMutation()) return
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        manuallyRenamedDuringTitleRequest += conversationId
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        refreshConversationSummaries()
        persistConversations()
    }

    fun sendCurrentMessage(submittedText: String? = null) {
        if (homeState.pendingConversationMentions.any { it.id in preparingConversationMentions }) {
            if (submittedText != null) updateCurrentConversation(homeState.copy(input = submittedText))
            Toast.makeText(appContext, "会话引用正在准备，请稍候再发送。", Toast.LENGTH_SHORT).show()
            return
        }
        if (rejectConversationArchiveMutation()) {
            // Preserve an early send while an archive operation is active.
            if (submittedText != null) updateCurrentConversation(homeState.copy(input = submittedText))
            return
        }
        if (io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance) {
            Toast.makeText(appContext, "正在恢复备份，请等待完成。", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = (submittedText ?: currentDraftField().text.toString()).trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        val pendingMentions = homeState.pendingConversationMentions
        if (!homeState.isStreaming && rejectSendIfModelUnavailable()) return
        if (homeState.isStreaming || homeState.isPaused) {
            if (
                prompt.isNotBlank() ||
                pendingImages.isNotEmpty() ||
                pendingFileReferences.isNotEmpty() || pendingMentions.isNotEmpty()
            ) {
                steerCurrentRun(submittedText ?: currentDraftField().text.toString())
            }
            return
        }
        if (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty() && pendingMentions.isEmpty()) {
            return
        }
        val generateVideo = selectedModelGeneratesVideos()
        val generateImage = !generateVideo && selectedModelGeneratesImages()
        if ((generateImage || generateVideo) && pendingMentions.isNotEmpty()) {
            Toast.makeText(appContext, "会话引用需要对话模型，图像/视频生成接口暂不支持。", Toast.LENGTH_LONG).show()
            return
        }
        if ((generateImage || generateVideo) && prompt.isBlank()) {
            Toast.makeText(
                appContext,
                appContext.getString(
                    if (generateVideo) R.string.chat_video_prompt_required
                    else R.string.chat_image_prompt_required,
                ),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (rejectSendIfCompressing()) {
            return
        }
        val fileReferences = pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.state_ui_file_path_reference_requires_opening_the_termina_deca4c),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val edit = homeState.messageEdit
        if (edit == null && selectedConversationId?.isReadOnlyExternalArchiveConversation() == true) {
            moveCurrentDraftToNewConversation()
        }

        val editBoundary = edit?.let {
            AgentConversationRevisionReducer.boundary(homeState, it.targetMessageId)
        }
        if (edit != null && editBoundary == null) {
            if (submittedText != null) updateCurrentConversation(homeState.copy(input = submittedText))
            showRevisionHistoryUnavailableNotice()
            return
        }

        val history = if (editBoundary != null) {
            editBoundary.historyPrefix
        } else {
            AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
                homeState.history,
                homeState.messages,
            )
        }
        if (!generateImage && !generateVideo && rejectSendIfContextWindowExceeded(history, prompt, pendingImages, pendingFileReferences)) {
            return
        }
        val conversationId = selectedConversationId ?: newConversationId().also { id ->
            conversationDrafts.promote(id)
            io.github.mangi.eta.agent.delegation.SubAgentPreferences.promote(id)
            selectedConversationId = id
            conversationPaneState = conversationPaneState.copy(selectedConversationId = id)
            assignPendingFolder(id)
        }
        if (conversationId !in conversationsById) {
            updateConversation(conversationId, homeState)
            refreshConversationSummaries()
        }
        val supportsVision = generateImage || generateVideo || (modelPickerState.selectedModel?.supportsVision == true) || io.github.mangi.eta.agent.model.ModelFeaturePreferences.visionEnabled()
        val supportsVideo = modelPickerState.selectedModel?.supportsVideo ?: false
        if (pendingImages.isNotEmpty()) {
            val ownerState = homeState
            val ownerDraft = currentDraftField().text.toString()
            val ownerGeneration = modelBindingGeneration
            val ownerAssistant = AssistantRepository.active().id
            scope.launch(Dispatchers.IO) {
                val staged = stageChatImages(conversationId, pendingImages)
                withContext(Dispatchers.Main) {
                    if (staged.size != pendingImages.size || staged.any { it == null }) {
                        Toast.makeText(appContext, "附件保存失败，消息未发送，附件仍保留。", Toast.LENGTH_LONG).show()
                        return@withContext
                    }
                    if (selectedConversationId != conversationId || modelBindingGeneration != ownerGeneration ||
                        homeState != ownerState || currentDraftField().text.toString() != ownerDraft ||
                        AssistantRepository.active().id != ownerAssistant) {
                        Toast.makeText(appContext, "发送准备期间会话或配置发生变化，未发送；请返回原草稿重试。", Toast.LENGTH_LONG).show()
                        return@withContext
                    }
                    if (homeState.isStreaming || rejectSendIfCompressing()) return@withContext
                    val outbound = pendingImages.filter { image ->
                        if (image.isVideo) supportsVideo || supportsVision else supportsVision
                    }
                    val extraFiles = pendingImages.mapIndexedNotNull { index, image ->
                        val file = staged.getOrNull(index) ?: return@mapIndexedNotNull null
                        val asFile = (image.isVideo && !supportsVideo) || (!image.isVideo && !supportsVision)
                        file.takeIf { asFile }
                    }
                    startPreparedSend(
                        prompt = prompt,
                        uiImages = pendingImages,
                        modelImages = outbound,
                        fileReferences = fileReferences + extraFiles,
                        persistedImages = staged.filterNotNull(),
                        history = history,
                        editBoundary = editBoundary,
                        conversationId = conversationId,
                    )
                }
            }
            return
        }
        startPreparedSend(
            prompt = prompt,
            uiImages = pendingImages,
            modelImages = pendingImages,
            fileReferences = fileReferences,
            persistedImages = emptyList(),
            history = history,
            editBoundary = editBoundary,
            conversationId = conversationId,
        )
    }

    private fun startPreparedSend(
        prompt: String,
        uiImages: List<PendingImageUi>,
        modelImages: List<PendingImageUi>,
        fileReferences: List<AgentFileReference>,
        persistedImages: List<AgentFileReference>,
        history: List<AgentModelClient.ConversationMessage>,
        editBoundary: AgentConversationRevisionReducer.Boundary?,
        conversationId: String,
    ) {
        if (conversationId != selectedConversationId || rejectSendIfModelUnavailable()) return
        if (rejectSendIfCompressing()) return
        val runtimePrompt = AgentFileReferencePromptCodec.format(prompt, fileReferences, homeState.pendingConversationMentions.toMentionedConversations())
        val runId = "run-${UUID.randomUUID()}"
        val userMessage = UserMessageUi(
            id = editBoundary?.userMessage?.id ?: "user-$runId",
            content = runtimePrompt,
            images = uiImages.map { it.dataUrl },
            isEdited = editBoundary != null,
            imageSources = when {
                persistedImages.size == uiImages.size -> persistedImages.map { it.absolutePath }
                else -> uiImages.map { it.uri }
            },
            imageIsVideo = uiImages.map { it.isVideo },
            imageDurationsMs = uiImages.map { it.durationMs },
        )
        val messages = if (editBoundary == null) {
            homeState.messages + userMessage
        } else {
            homeState.messages.take(editBoundary.userMessageIndex) + userMessage
        }
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = runtimePrompt,
            persistedImages = persistedImages.map { reference ->
                AgentConversationCodec.PersistedImage(
                    path = reference.absolutePath,
                    mimeType = mimeTypeForFileName(reference.displayName),
                    displayName = reference.displayName,
                )
            },
        )

        val currentTitle = conversationTitles[conversationId]
        val oldAutoTitle = editBoundary
            ?.takeIf { it.userMessageIndex == 0 }
            ?.userMessage
            ?.content
            ?.defaultConversationTitleFromMessage()
        val nextAutoTitle = defaultConversationTitle(prompt.ifBlank {
            homeState.pendingConversationMentions.firstOrNull()?.let { "@${it.title}" }.orEmpty()
        }, fileReferences)
        val title = if (
            editBoundary?.userMessageIndex == 0 &&
            (currentTitle == oldAutoTitle || currentTitle.isNullOrBlank())
        ) {
            nextAutoTitle
        } else {
            currentTitle?.takeIf(String::isNotBlank) ?: nextAutoTitle
        }

        conversationTitles = conversationTitles + (conversationId to title)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = runtimePrompt,
            images = modelImages,
            history = history,
            userHistoryMessage = userHistoryMessage,
            messages = messages,
            state = homeState.copy(
                input = "",
                pendingImages = emptyList(),
                pendingFileReferences = emptyList(),
                pendingConversationMentions = emptyList(),
                messageEdit = null,
            ),
            reasoningEffort = homeState.reasoningEffort,
            consumeDraft = true,
        )
    }

    private fun stageChatImages(
        conversationId: String,
        images: List<PendingImageUi>,
    ): List<AgentFileReference?> =
        images.mapIndexed { index, image ->
            if (image.isVideo) {
                val file = java.io.File(image.uri.removePrefix("file://"))
                if (!file.isFile) return@mapIndexed null
                chatImageCache.stageFromFile(
                    conversationId,
                    file,
                    image.cacheDisplayName(index),
                    MAX_AGENT_VIDEO_BYTES,
                )
            } else {
                val bytes = AgentChatImageCache.readBytes(image.uri)
                    ?: AgentChatImageCache.readBytes(image.dataUrl)
                    ?: return@mapIndexed null
                chatImageCache.stage(conversationId, bytes, image.cacheDisplayName(index))
            }
        }

    fun beginMessageEdit(messageId: String) {
        if (homeState.isStreaming || homeState.isPaused) return
        if (rejectConversationArchiveMutation()) return
        if (homeState.messageEdit != null) return
        if (rejectSendIfCompressing()) return
        abortActiveRunForRevision()
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: run {
            showRevisionHistoryUnavailableNotice()
            return
        }
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            val video = boundary.userMessage.isVideoAt(index)
            PendingImageUi(
                id = "edit-${boundary.userMessage.id}-$index",
                uri = boundary.userMessage.fullImageSourceAt(index),
                dataUrl = dataUrl,
                mimeType = if (video) "video/mp4" else dataUrl.imageMimeType(),
                isVideo = video,
                durationMs = boundary.userMessage.durationMsAt(index),
            )
        }
        val parsedPrompt = AgentFileReferencePromptCodec.parse(boundary.userMessage.content)
        val fileReferences = parsedPrompt.references.mapIndexed { index, reference ->
            PendingFileReferenceUi(
                id = "edit-${boundary.userMessage.id}-file-$index",
                reference = reference,
            )
        }
        updateCurrentConversation(
            homeState.copy(
                input = parsedPrompt.request,
                pendingImages = images,
                pendingFileReferences = fileReferences,
                pendingConversationMentions = parsedPrompt.conversations.map { mentioned ->
                    PendingConversationMentionUi("mention-${UUID.randomUUID()}", mentioned.id, mentioned.title, mentioned.transcript)
                },
                messageEdit = MessageEditUiState(
                    targetMessageId = boundary.userMessage.id,
                    previousInput = currentDraftField().text.toString(),
                    previousImages = homeState.pendingImages,
                    previousFileReferences = homeState.pendingFileReferences,
                    previousConversationMentions = homeState.pendingConversationMentions,
                    hasLaterTurns = boundary.laterTurnCount > 0,
                ),
            )
        )
        conversationDrafts.replace(selectedConversationId, parsedPrompt.request)
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
    }

    fun cancelMessageEdit() {
        if (rejectConversationArchiveMutation()) return
        val edit = homeState.messageEdit ?: return
        conversationDrafts.replace(selectedConversationId, edit.previousInput)
        updateCurrentConversation(
            homeState.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                pendingConversationMentions = edit.previousConversationMentions,
                messageEdit = null,
            )
        )
    }

    fun messageRevisionImpact(messageId: String): MessageRevisionImpact? =
        AgentConversationRevisionReducer.boundary(homeState, messageId)?.let { boundary ->
            MessageRevisionImpact(laterTurnCount = boundary.laterTurnCount)
        }

    fun deleteMessageTurn(messageId: String) {
        if (homeState.isStreaming || homeState.isPaused) return
        if (rejectConversationArchiveMutation()) return
        if (homeState.messageEdit != null) return
        abortActiveRunForRevision()
        val conversationId = selectedConversationId ?: return
        val revised = AgentConversationRevisionReducer.deleteFromTurn(homeState, messageId) ?: run {
            showRevisionHistoryUnavailableNotice()
            return
        }
        if (revised.messages.isEmpty()) {
            retainDeletedConversation(homeState.messages, conversationUpdatedAt[conversationId])
            conversationsById = conversationsById - conversationId
            conversationTitles = conversationTitles - conversationId
            conversationUpdatedAt = conversationUpdatedAt - conversationId
            conversationFolderIds = conversationFolderIds - conversationId
            conversationPinned = conversationPinned - conversationId
            scope.launch(Dispatchers.IO) { chatImageCache.deleteConversation(conversationId) }
            fileAttachmentOwnerVersion += 1
            selectedConversationId = null
            homeState = newDraftChatState()
            conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
            refreshConversationSummaries()
            persistConversations()
            return
        }
        updateConversation(conversationId, revised)
        refreshConversationSummaries()
        persistConversations()
    }

    fun branchConversation(messageId: String) {
        if (rejectConversationArchiveMutation()) return
        if (homeState.messageEdit != null) cancelMessageEdit()
        val sourceId = selectedConversationId ?: return
        val snapshot = conversationsById[sourceId] ?: homeState
        val prefix = AgentConversationRevisionReducer.branchPrefix(snapshot, messageId) ?: run {
            showRevisionHistoryUnavailableNotice()
            return
        }
        if (prefix.messages.isEmpty()) return
        val newId = newConversationId()
        val rewrite = { value: String -> chatImageCache.rewriteCachedPath(value, sourceId, newId) }
        val branched = snapshot.copy(
            messages = freezeStreamingMessages(prefix.messages).map { message ->
                message.withId("$newId:${message.id}").rewritePaths(rewrite)
            },
            history = prefix.history.map { it.rewritePaths(rewrite) },
            input = "",
            isStreaming = false,
            isPaused = false,
            isCompressingContext = false,
            isWaitingForCompression = false,
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
                pendingConversationMentions = emptyList(),
            appliedRuntimeRunIds = emptyList(),
            messageEdit = null,
            livePromptTokens = null,
        )
        val sourceTitle = conversationTitles[sourceId].orEmpty().ifBlank {
            appContext.getString(R.string.conversation_unnamed)
        }
        conversationsById = conversationsById + (newId to branched)
        conversationTitles = conversationTitles + (
            newId to appContext.getString(R.string.conversation_branch_title, sourceTitle)
        )
        conversationUpdatedAt = conversationUpdatedAt + (newId to System.currentTimeMillis())
        conversationFolderIds[sourceId]?.let { folderId ->
            conversationFolderIds = conversationFolderIds + (newId to folderId)
        }
        fileAttachmentOwnerVersion += 1
        selectedConversationId = newId
        homeState = branched
        billedOverheadConversationId = newId
        billedOverheadTokens = null
        conversationPaneState = conversationPaneState.copy(selectedConversationId = newId)
        refreshConversationSummaries()
        persistConversations()
        scope.launch(Dispatchers.IO) {
            chatImageCache.copyConversation(sourceId, newId)
        }
    }

    fun regenerateMessage(messageId: String) {
        if (rejectConversationArchiveMutation()) return
        regenerateMessage(messageId, ignoreCompression = false)
    }

    private fun regenerateMessage(messageId: String, ignoreCompression: Boolean) {
        if (homeState.isStreaming || homeState.isPaused) return
        if (rejectConversationArchiveMutation()) return
        if (homeState.messageEdit != null) return
        if (!ignoreCompression && rejectSendIfCompressing()) return
        if (rejectSendIfModelUnavailable()) return
        abortActiveRunForRevision()
        val conversationId = selectedConversationId ?: return
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: run {
            showRevisionHistoryUnavailableNotice()
            return
        }
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            val video = boundary.userMessage.isVideoAt(index)
            PendingImageUi(
                id = "regenerate-${boundary.userMessage.id}-$index",
                uri = boundary.userMessage.fullImageSourceAt(index),
                dataUrl = dataUrl,
                mimeType = if (video) "video/mp4" else dataUrl.imageMimeType(),
                isVideo = video,
                durationMs = boundary.userMessage.durationMsAt(index),
            )
        }
        val parsed = AgentFileReferencePromptCodec.parse(boundary.userMessage.content)
        val generateVideo = selectedModelGeneratesVideos()
        val generateImage = !generateVideo && selectedModelGeneratesImages()
        val supportsVision = generateImage || generateVideo || (modelPickerState.selectedModel?.supportsVision == true) || io.github.mangi.eta.agent.model.ModelFeaturePreferences.visionEnabled()
        if (!generateImage && !generateVideo && rejectSendIfContextWindowExceeded(boundary.historyPrefix, parsed.request, images, parsed.references.mapIndexed { index, reference ->
                PendingFileReferenceUi(id = "regen-$index", reference = reference)
            }, parsed.conversations.map { PendingConversationMentionUi(it.id, it.id, it.title, it.transcript) })) {
            return
        }
        if (!ignoreCompression && boundary.contextWasCompacted) showCompactedRevisionNotice()
        if (images.isNotEmpty()) {
            val ownerState = homeState
            val ownerGeneration = modelBindingGeneration
            val ownerAssistant = AssistantRepository.active().id
            scope.launch(Dispatchers.IO) {
                val extra = if (parsed.references.isEmpty()) {
                    stageChatImages(conversationId, images).filterNotNull()
                } else {
                    emptyList()
                }
                val runtimePrompt = AgentFileReferencePromptCodec.format(
                    parsed.request,
                    if (supportsVision) parsed.references else parsed.references + extra,
                    parsed.conversations,
                )
                val persisted = extra.ifEmpty { parsed.references }.map { reference ->
                    AgentConversationCodec.PersistedImage(
                        path = reference.absolutePath,
                        mimeType = mimeTypeForFileName(reference.displayName),
                        displayName = reference.displayName,
                    )
                }
                withContext(Dispatchers.Main) {
                    if (selectedConversationId != conversationId || homeState != ownerState ||
                        modelBindingGeneration != ownerGeneration || AssistantRepository.active().id != ownerAssistant ||
                        rejectSendIfModelUnavailable()) return@withContext
                    if (homeState.isStreaming || rejectSendIfCompressing()) return@withContext
                    val runId = "run-${UUID.randomUUID()}"
                    launchConversationRun(
                        conversationId = conversationId,
                        runId = runId,
                        prompt = runtimePrompt,
                        images = if (supportsVision) images else emptyList(),
                        history = boundary.historyPrefix,
                        userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
                            text = runtimePrompt,
                            persistedImages = persisted,
                        ),
                        messages = homeState.messages.take(boundary.userMessageIndex + 1),
                        state = homeState,
                        reasoningEffort = homeState.reasoningEffort,
                        skipAutoCompress = ignoreCompression,
                    )
                }
            }
            return
        }
        val runId = "run-${UUID.randomUUID()}"
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = boundary.userMessage.content,
            images = images.toHistoryImages(),
        )
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = boundary.userMessage.content,
            images = images,
            history = boundary.historyPrefix,
            userHistoryMessage = userHistoryMessage,
            messages = homeState.messages.take(boundary.userMessageIndex + 1),
            state = homeState,
            reasoningEffort = homeState.reasoningEffort,
            skipAutoCompress = ignoreCompression,
        )
    }


    private fun rejectSendIfContextWindowExceeded(
        history: List<AgentModelClient.ConversationMessage>,
        prompt: String,
        images: List<PendingImageUi>,
        fileReferences: List<PendingFileReferenceUi> = emptyList(),
        conversationMentions: List<PendingConversationMentionUi> = homeState.pendingConversationMentions,
    ): Boolean {
        val billed = if (homeState.messageEdit != null || history != homeState.history) {
            null
        } else {
            billedPromptTokens(homeState)
        }
        val usage = liveContextUsage(
            history = history,
            currentInput = prompt,
            pendingImages = images,
            selectedModel = modelPickerState.selectedModel,
            pendingFileReferences = fileReferences,
            pendingConversationMentions = conversationMentions,
            billedContextTokens = billed,
            requestOverheadTokens = requestOverheadTokens,
            billedOverheadTokens = billedOverheadTokens,
        )
        if (!shouldBlockSendForContextWindow(autoCompressEnabled, usage)) {
            return false
        }
        Toast.makeText(
            appContext,
            appContext.getString(R.string.context_window_send_blocked),
            Toast.LENGTH_LONG,
        ).show()
        return true
    }

    /**
     * 判断是否应自动压缩对话历史。
     */
    private fun coerceKeepRecent(value: Int): Int = AgentContextCompactor.coerceKeepRecent(value)

    private fun keepRecentFor(): Int = AgentContextCompactor.keepRecentFor()

    private fun billedPromptTokens(state: AgentChatHomeUiState): Int? =
        state.livePromptTokens

    private fun compressionContextWindow(fallback: Int? = null): Int? =
        modelPickerState.selectedModel?.contextWindow?.takeIf { it > 0 }
            ?: fallback?.takeIf { it > 0 }

    private fun shouldAutoCompress(
        history: List<AgentModelClient.ConversationMessage>,
        contextWindow: Int?,
        estimatedTokens: Int?,
    ): Boolean {
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED)) return false
        val window = contextWindow?.takeIf { it > 0 } ?: return false
        return AgentContextCompactor.shouldCompress(
            history = history,
            contextWindow = window,
            keepRecentMessages = keepRecentFor(),
            estimatedTokens = estimatedTokens,
        )
    }

    /**
     * 尝试压缩对话历史，失败时回退原历史。
     */
    private suspend fun tryCompressHistory(
        history: List<AgentModelClient.ConversationMessage>,
        compressModelConfig: AgentModelClient.ModelConfig?,
        keepRecent: Int? = null,
        conversationId: String? = null,
        contextWindow: Int? = null,
    ): List<AgentModelClient.ConversationMessage> {
        val resolvedKeepRecent = keepRecent ?: keepRecentFor()
        val archive = conversationId?.let {
            io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, it)
        }
        val config = AgentContextCompactor.Config(
            keepRecentMessages = coerceKeepRecent(resolvedKeepRecent),
            compressModelConfig = compressModelConfig,
            compactionArchive = archive,
            usageConversationId = conversationId,
        )
        return try {
            var summaryFailure: String? = null
            val compressed = runInterruptible {
                val window = contextWindow?.takeIf { it > 0 } ?: 0
                val initialCut = io.github.mangi.eta.agent.model.AgentCompressionBoundary.selectStart(
                    history, window)
                val working = AgentContextCompactor.pruneOversizedToolResults(history, archive, initialCut)
                val cut = io.github.mangi.eta.agent.model.AgentCompressionBoundary.selectStart(
                    working, window)
                if (cut <= 0 || cut >= working.size) return@runInterruptible working
                val boundArchive = archive ?: error("缺少会话身份，无法保存压缩原文")
                val prefix = working.take(cut)
                val id = boundArchive.save(prefix)
                boundArchive.record(id, "started")
                boundArchive.canAttach(id, prefix.size.coerceAtLeast(working.size - cut + 1), working.size - cut)
                try {
                    val summary = AgentContextCompactor.compress(
                        working,
                        config.copy(compactionArchive = null),
                        keepStartOverride = cut,
                    )
                    val result = boundArchive.attachReferences(prefix, id, summary, working.size - cut)
                    require(result.sumOf { AgentContextBudget.countMessage(it).toLong() } < history.sumOf { AgentContextBudget.countMessage(it).toLong() }) { "摘要及索引未缩小上下文" }
                    boundArchive.record(id, "ready")
                    result
                } catch (failure: Exception) {
                    runCatching { boundArchive.record(id, "failed") }
                    // Never turn stop/coroutine cancellation into pruning-only success.
                    if (failure is CancellationException || failure is InterruptedException ||
                        failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException ||
                        Thread.currentThread().isInterrupted) throw failure
                    if (working.sumOf { AgentContextBudget.countMessage(it).toLong() } < history.sumOf { AgentContextBudget.countMessage(it).toLong() }) {
                        summaryFailure = failure.message ?: "摘要失败"
                        AndroidAgentLogger.warn("摘要失败，已保留工具修剪结果：${failure.javaClass.simpleName}: ${failure.message}")
                        return@runInterruptible working
                    }
                    throw failure
                }
            }
            if (summaryFailure != null) withContext(Dispatchers.Main) {
                if (conversationId == selectedConversationId) Toast.makeText(appContext,
                    "压缩会话失败：$summaryFailure", Toast.LENGTH_LONG).show()
            }
            compressed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (Thread.currentThread().isInterrupted || failure is InterruptedException ||
                failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException) {
                throw CancellationException("摘要已取消").also { it.initCause(failure) }
            }
            AndroidAgentLogger.warn("压缩失败，保留原文：${failure.javaClass.simpleName}: ${failure.message}")
            withContext(Dispatchers.Main) {
                if (conversationId == selectedConversationId) Toast.makeText(appContext,
                    failure.message ?: "压缩失败，原历史保持不变", Toast.LENGTH_LONG).show()
            }
            history
        }
    }

    private suspend fun resolveCompressModelConfig(
        fallback: AgentModelClient.ModelConfig?,
        providerId: String? = null,
        modelId: String? = null,
        manual: Boolean = false,
    ): AgentModelClient.ModelConfig? {
        val prefs = Prefs.localAgentPreferences()
        val customEnabled = Prefs.isCustomCompressModelEnabled(prefs)
        val resolvedProviderId = providerId
            ?: prefs?.takeIf { customEnabled }
                ?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, null)
        val resolvedModelId = modelId
            ?: prefs?.takeIf { customEnabled }
                ?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID, null)
        val resolved = if (resolvedProviderId.isNullOrBlank() || resolvedModelId.isNullOrBlank()) {
            fallback
        } else {
            val assistant = fallback?.assistantId?.takeIf { it.isNotBlank() }?.let {
                runCatching { AssistantRepository.currentProfile(it) }.getOrNull()
            }
            RuntimeConfigRepository.configForProviderAndModel(resolvedProviderId, resolvedModelId, assistant)
                ?.let { model ->
                    fallback?.let { source ->
                        model.copy(assistantId = source.assistantId, systemPrompt = source.systemPrompt)
                    } ?: model
                }
                ?: fallback
        }
        val compressed = resolved?.let(AgentRuntimePolicy::forCompression) ?: return null
        val endpointKey = if (manual) {
            Prefs.Keys.AGENT_MANUAL_COMPRESS_ENDPOINT_MODE
        } else {
            Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE
        }
        return AgentCompressionEndpoint.apply(compressed, prefs?.getString(endpointKey, null))
    }

    private fun launchConversationRun(
        conversationId: String,
        runId: String,
        prompt: String,
        images: List<PendingImageUi>,
        history: List<AgentModelClient.ConversationMessage>,
        userHistoryMessage: AgentModelClient.ConversationMessage,
        messages: List<AgentChatMessageUi>,
        state: AgentChatHomeUiState,
        reasoningEffort: ReasoningEffort,
        skipAutoCompress: Boolean = false,
        logicalTurnId: String = runId,
        consumeDraft: Boolean = false,
    ) {
        val runProvider = selectionProviders.firstOrNull { it.id == state.providerId && it.isEnabled }
        val runModel = runProvider?.models?.firstOrNull { it.id == state.modelId && it.isEnabled }
        if (runProvider == null || runModel == null) {
            Toast.makeText(appContext, "绑定模型已不可用，未发送。请重新选择。", Toast.LENGTH_LONG).show()
            return
        }
        if (runModel.supportsSpeechSynthesis) {
            Toast.makeText(appContext, "语音合成模型请在朗读设置中使用，不能执行对话任务", Toast.LENGTH_LONG).show()
            return
        }
        if (consumeDraft) conversationDrafts.replace(conversationId, "")
        val runAssistant = AssistantRepository.active()
        val runConfig = RuntimeConfigRepository.buildRuntimeConfig(runProvider, runModel, runAssistant)
        val runModelOption = AgentModelPickerProjector.project(listOf(runProvider), runProvider.id, runModel.id).selectedModel
        val runOverhead = requestOverheadTokens
        val runBilledOverhead = billedOverheadTokens
        val taggedUserHistoryMessage = userHistoryMessage.copy(turnId = logicalTurnId)
        bindRunConversation(runId, conversationId)
        runCloudUsage[runId] = CloudContextUsageState(ContextUsageScope(conversationId, state.providerId, state.modelId, 0))
        runOverheadTokens[runId] = requestOverheadTokens
        val generateVideo = runModel.supportsVideoGeneration
        val generateImage = !generateVideo && runModel.supportsImageGeneration
        val mediaController = if (generateImage || generateVideo) {
            imageGenerationRunIds += runId
            directMediaRuns.start(runId)
        } else null

        val willCompress = !generateImage && !generateVideo && !skipAutoCompress && shouldAutoCompress(
            history = history,
            contextWindow = runConfig.contextWindow,
            estimatedTokens = liveContextUsage(
                history = history,
                currentInput = prompt,
                pendingImages = images,
                selectedModel = runModelOption,
                billedContextTokens = if (history == state.history) billedPromptTokens(state) else null,
                requestOverheadTokens = runOverhead,
                billedOverheadTokens = runBilledOverhead,
            ).contextTokens,
        )
        val runMessages = if (generateImage || generateVideo) {
            messages + AgentMessageUi(
                id = "assistant-$runId-1",
                content = "",
                isStreaming = true,
            )
        } else {
            messages
        }
        updateConversation(
            conversationId,
            state.copy(
                isStreaming = true,
                isPaused = false,
                livePromptTokens = null,
                isCompressingContext = willCompress,
                history = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(history) + taggedUserHistoryMessage,
                messages = runMessages,
                messageEdit = null,
            )
        )
        refreshConversationSummaries()
        val initialPersistence = persistConversations()

        val preparationJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            // write-ahead：用户消息未提交前不把可能产生副作用的 run 交给 Runtime。
            if (!initialPersistence.await()) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = lastConversationPersistenceError
                                ?.takeIf { it.isNotBlank() }
                                ?.let { "${appContext.getString(R.string.conversation_persistence_failed)}（$it）" }
                                ?: appContext.getString(R.string.conversation_persistence_failed),
                        )
                    )
                }
                return@launch
            }
            if (stoppingRuns.containsKey(runId)) {
                withContext(Dispatchers.Main) {
                    applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "已停止"))
                }
                return@launch
            }
            val permittedReasoningEffort = if (
                agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
            ) {
                ConversationReasoningPolicy.resolve(reasoningEffort, runConfig.reasoningCapabilities)
            } else {
                ReasoningEffort.OFF
            }
            val config = runConfig.copy(
                terminalTools = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                browserTools = agentBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
                deviceDirectTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                deviceSensitiveReadTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                deviceSensitiveActionTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                thinkingEnabled = permittedReasoningEffort.enablesReasoning,
                reasoningEffort = permittedReasoningEffort,
            )
            if (mediaController != null) {
                directMediaRuns.execute(mediaController) {
                    if (generateVideo) executeVideoGeneration(runId, conversationId, config, prompt, images, mediaController)
                    else executeImageGeneration(runId, conversationId, config, prompt, images, mediaController)
                }
                return@launch
            }
            val supportsVideo = config.supportsVideo
            val modelImages = images.map { p ->
                p.toOutboundModelImage(supportsVideo).copy(source = "user_attach")
            }
            val compressModelConfig = resolveCompressModelConfig(config)
            val estimatedTokens = liveContextUsage(
                history = history,
                currentInput = prompt,
                pendingImages = images,
                selectedModel = runModelOption,
                billedContextTokens = if (history == state.history) billedPromptTokens(state) else null,
                requestOverheadTokens = runOverhead,
                billedOverheadTokens = runBilledOverhead,
            ).contextTokens
            val pendingInRunCompact = withContext(Dispatchers.Main) {
                pendingInRunCompactConversationIds.remove(conversationId)
            }
            val shouldCompress = !skipAutoCompress && (
                pendingInRunCompact ||
                    shouldAutoCompress(
                        history,
                        config.contextWindow,
                        estimatedTokens,
                    )
            )
            if (shouldCompress != willCompress) {
                withContext(Dispatchers.Main) {
                    setConversationCompressing(conversationId, shouldCompress)
                }
            }
            val historyToSend = if (shouldCompress) {
                val compressed = tryCompressHistory(
                    history = history,
                    compressModelConfig = compressModelConfig,
                    conversationId = conversationId,
                    contextWindow = config.contextWindow,
                )
                withContext(Dispatchers.Main) {
                    applyCompressedHistoryToConversation(
                        conversationId = conversationId,
                        originalHistory = history,
                        compressedHistory = compressed,
                        userHistoryMessage = taggedUserHistoryMessage,
                        compressorLabel = compressorLabel(compressModelConfig),
                    )
                    setConversationCompressing(conversationId, false)
                }
                compressed
            } else {
                history
            }
            val result = runInterruptible {
                AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                    request = AgentRuntimeWire.RunRequest(
                        runId = runId,
                        turnId = logicalTurnId,
                        assistantId = runAssistant.id,
                        prompt = prompt,
                        config = config,
                        images = modelImages,
                        history = historyToSend,
                        // UI owns context via auto-compress / 99% send block; Runtime must not trimHistory.
                        historyAlreadyCompacted = true,
                        modelSessionId = conversationId,
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = runId,
                            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                            payload = conversationId,
                        ),
                    ),
                    onEvent = { event -> enqueueRunEvent(runId, event) },
                    isStopRequested = { stoppingRuns.containsKey(runId) },
                )
            }
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result, acknowledgeRuntimeResult = true)
            }
        }
        runJobs[runId] = preparationJob
        if (!RootAccess.isGranted) {
            val leaseId = "prepare:$runId"
            val acquired = AgentExecutionService.acquire(appContext, leaseId) {
                scope.launch(Dispatchers.Main.immediate) {
                    if (runId in runJobs) stopRun(runId)
                }
            }
            if (!acquired) {
                preparationJob.cancel()
                applyRunResult(runId, AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = appContext.getString(R.string.capability_background_failed),
                ))
                return
            }
            preparationJob.invokeOnCompletion { AgentExecutionService.release(leaseId) }
        }
        if (mediaController != null) preparationJob.invokeOnCompletion { directMediaRuns.cancel(runId) }
        preparationJob.start()
        if (history.isEmpty() && !generateImage && !generateVideo) {
            requestConversationTitle(conversationId, state, prompt, messages.lastOrNull()?.id,
                initialPersistence)
        }
    }

    private fun requestConversationTitle(
        conversationId: String,
        state: AgentChatHomeUiState,
        prompt: String,
        firstMessageId: String?,
        persisted: Deferred<Boolean>,
    ) {
        if (firstMessageId == null || conversationId in manuallyRenamedDuringTitleRequest) return
        val expectedTitle = conversationTitles[conversationId] ?: return
        val selection = io.github.mangi.eta.agent.model.ModelFeaturePreferences.selection(
            io.github.mangi.eta.agent.model.ModelFeature.TITLE)
        // Only send the visible question, never hidden tool snapshots, file bytes or system prompts.
        val question = AgentFileReferencePromptCodec.parse(prompt).request.ifBlank { expectedTitle }
        scope.launch {
            try {
                if (!persisted.await()) return@launch
                val title = withContext(Dispatchers.IO) {
                    val current = runtimeConfigForBoundModel(state, assistant = null) ?: return@withContext null
                    val config = io.github.mangi.eta.agent.model.ConversationTitleModel.resolve(selection, current)
                    kotlinx.coroutines.runInterruptible {
                        io.github.mangi.eta.agent.model.ConversationTitleModel.generate(
                            config, question, io.github.mangi.eta.agent.runtime.AgentRunController(), conversationId)
                    }
                } ?: return@launch
                val current = conversationsById[conversationId]
                if (conversationArchiveBusy || !io.github.mangi.eta.agent.model.ConversationTitleModel.mayApply(
                        current != null, conversationId in manuallyRenamedDuringTitleRequest,
                        conversationTitles[conversationId], expectedTitle,
                        current?.messages?.firstOrNull()?.id, firstMessageId)) return@launch
                conversationTitles = conversationTitles + (conversationId to title)
                refreshConversationSummaries()
                persistConversations()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Title requests are optional: preserve the local title and the chat run.
                AndroidAgentLogger.warn("标题生成失败，保留本地标题")
            }
        }
    }

    private fun selectedModelGeneratesImages(): Boolean =
        modelPickerState.selectedModel?.supportsImageGeneration == true

    private fun selectedModelGeneratesVideos(): Boolean =
        modelPickerState.selectedModel?.supportsVideoGeneration == true

    private suspend fun executeImageGeneration(
        runId: String,
        conversationId: String,
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<PendingImageUi>,
        controller: io.github.mangi.eta.agent.runtime.AgentRunController,
    ) {
        try {
            controller.throwIfCancelled()
            val apiPrompt = AgentFileReferencePromptCodec.parse(prompt).request.trim()
            if (apiPrompt.isBlank()) {
                error(appContext.getString(R.string.chat_image_prompt_required))
            }
            if (config.providerType == ProviderTypes.ANTHROPIC) {
                error(appContext.getString(R.string.chat_image_generation_unsupported))
            }
            val inputImages = images.filter { !it.isVideo }.mapNotNull { image ->
                val bytes = AgentChatImageCache.readBytes(image.uri)
                    ?: AgentChatImageCache.readBytes(image.dataUrl)
                    ?: return@mapNotNull null
                AgentImageGenerationClient.InputImage(
                    bytes = bytes,
                    mimeType = image.mimeType.ifBlank { "image/jpeg" },
                )
            }
            val generated = AgentImageGenerationClient(runController = controller).generate(
                config = config,
                prompt = apiPrompt,
                images = inputImages,
            )
            if (generated.images.isEmpty()) {
                error(appContext.getString(R.string.chat_image_generation_empty))
            }
            controller.throwIfCancelled()
            val staged = generated.images.mapIndexedNotNull { index, image ->
                controller.throwIfCancelled()
                val ext = AgentImageGenerationParser.extensionForMime(image.mimeType)
                chatImageCache.stage(conversationId, image.bytes, "generated-${index + 1}.$ext")
            }
            if (staged.isEmpty()) {
                error(appContext.getString(R.string.chat_image_generation_empty))
            }
            val markdown = AgentImageGenerationParser.markdown(
                paths = staged.map { it.absolutePath },
                text = generated.text,
            )
            withContext(Dispatchers.Main) {
                if (!controller.isCancelled) finishImageGenerationRun(runId, markdown)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (controller.isCancelled) return
            val message = failure.message?.trim().orEmpty().ifBlank {
                appContext.getString(R.string.chat_image_generation_empty)
            }
            withContext(Dispatchers.Main) {
                if (!controller.isCancelled) failImageGenerationRun(runId, message)
            }
        }
    }

    private suspend fun executeVideoGeneration(
        runId: String,
        conversationId: String,
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<PendingImageUi>,
        controller: io.github.mangi.eta.agent.runtime.AgentRunController,
    ) {
        try {
            controller.throwIfCancelled()
            val apiPrompt = AgentFileReferencePromptCodec.parse(prompt).request.trim()
            if (apiPrompt.isBlank()) {
                error(appContext.getString(R.string.chat_video_prompt_required))
            }
            if (config.providerType == ProviderTypes.ANTHROPIC) {
                error(appContext.getString(R.string.chat_video_generation_unsupported))
            }
            val inputImages = images.filter { !it.isVideo }.mapNotNull { image ->
                val bytes = AgentChatImageCache.readBytes(image.uri)
                    ?: AgentChatImageCache.readBytes(image.dataUrl)
                    ?: return@mapNotNull null
                AgentVideoGenerationClient.InputImage(
                    bytes = bytes,
                    mimeType = image.mimeType.ifBlank { "image/jpeg" },
                )
            }
            val generated = AgentVideoGenerationClient(runController = controller).generate(
                config = config,
                prompt = apiPrompt,
                images = inputImages,
            )
            if (generated.videos.isEmpty()) {
                error(appContext.getString(R.string.chat_video_generation_empty))
            }
            controller.throwIfCancelled()
            val staged = generated.videos.mapIndexedNotNull { index, video ->
                controller.throwIfCancelled()
                val ext = AgentVideoGenerationParser.extensionForMime(video.mimeType)
                chatImageCache.stage(
                    conversationId,
                    video.bytes,
                    "generated-${index + 1}.$ext",
                    maxBytes = AgentVideoGenerationClient.MAX_GENERATED_VIDEO_BYTES,
                )
            }
            if (staged.isEmpty()) {
                error(appContext.getString(R.string.chat_video_generation_empty))
            }
            val markdown = AgentVideoGenerationParser.markdown(
                paths = staged.map { it.absolutePath },
                text = generated.text,
            )
            withContext(Dispatchers.Main) {
                if (!controller.isCancelled) finishImageGenerationRun(runId, markdown)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (controller.isCancelled) return
            val message = failure.message?.trim().orEmpty().ifBlank {
                appContext.getString(R.string.chat_video_generation_empty)
            }
            withContext(Dispatchers.Main) {
                if (!controller.isCancelled) failImageGenerationRun(runId, message)
            }
        }
    }

    private fun finishImageGenerationRun(runId: String, content: String) {
        if (runId !in imageGenerationRunIds) return
        runJobs.remove(runId)
        directMediaRuns.cancel(runId)
        imageGenerationRunIds.remove(runId)
        completeLatestAssistantMessage(runId, content, generatedAtMillis = System.currentTimeMillis())
        snapshotPartialAssistantToHistory(runId)
        setConversationStreaming(runId, false)
        val conversationId = conversationIdForRun(runId)
        runGeneratedAtMillis.remove(runId)
        runConversationIds.remove(runId)
        runCloudUsage.remove(runId)
        runUsageResumeRounds.remove(runId)
        runOverheadTokens.remove(runId)
        runCompressedDuringRun.remove(runId)
        refreshConversationSummaries()
        persistConversations()
        if (conversationId != null) {
            onConversationRunSettled(conversationId)
        }
    }

    private fun failImageGenerationRun(runId: String, error: String) {
        if (runId !in imageGenerationRunIds) return
        runJobs.remove(runId)
        directMediaRuns.cancel(runId)
        imageGenerationRunIds.remove(runId)
        replaceLatestAssistantWithNotice(runId, SystemNoticeCode.RuntimeFailed, error)
        setConversationStreaming(runId, false)
        val conversationId = conversationIdForRun(runId)
        runGeneratedAtMillis.remove(runId)
        runConversationIds.remove(runId)
        runCloudUsage.remove(runId)
        runUsageResumeRounds.remove(runId)
        runOverheadTokens.remove(runId)
        runCompressedDuringRun.remove(runId)
        refreshConversationSummaries()
        persistConversations()
        if (conversationId != null) {
            onConversationRunSettled(conversationId)
        }
    }

    private fun List<PendingImageUi>.toHistoryImages(): List<AgentModelClient.ModelImage> {
        val supportsVideo = modelPickerState.selectedModel?.supportsVideo == true
        return map { it.toOutboundModelImage(supportsVideo) }
    }

    private fun mimeTypeForFileName(name: String): String = when {
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".webp", ignoreCase = true) -> "image/webp"
        name.endsWith(".gif", ignoreCase = true) -> "image/gif"
        name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        name.endsWith(".webm", ignoreCase = true) -> "video/webm"
        name.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
        name.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
        name.endsWith(".3gp", ignoreCase = true) -> "video/3gpp"
        else -> "image/jpeg"
    }

    private fun String.imageMimeType(): String =
        takeIf { startsWith("data:") }
            ?.substringAfter("data:")
            ?.substringBefore(';')
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"

    private fun String.defaultConversationTitle(): String =
        lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS)

    private fun defaultConversationTitle(
        request: String,
        references: List<AgentFileReference>,
    ): String = AgentFileReferencePolicy
        .titleSource(request, references)
        .defaultConversationTitle()

    private fun String.defaultConversationTitleFromMessage(): String {
        val parsed = AgentFileReferencePromptCodec.parse(this)
        return defaultConversationTitle(parsed.request.ifBlank { parsed.conversations.firstOrNull()?.let { "@${it.title}" }.orEmpty() }, parsed.references)
    }

    /**
     * 压缩成功后只补丁当前会话 history，避免用发送前快照覆盖 isStreaming / messages。
     * 失败或原样返回时不提示、不落盘。
     */
    private fun compressorLabel(config: AgentModelClient.ModelConfig?): String {
        val provider = config?.providerName?.trim().orEmpty()
        val model = config?.modelDisplayName?.trim().orEmpty()
            .ifBlank { config?.model?.trim().orEmpty() }
        return when {
            provider.isNotBlank() && model.isNotBlank() -> "$provider · $model"
            model.isNotBlank() -> model
            provider.isNotBlank() -> provider
            else -> ""
        }
    }

    private fun applyCompressedHistoryToConversation(
        conversationId: String,
        originalHistory: List<AgentModelClient.ConversationMessage>,
        compressedHistory: List<AgentModelClient.ConversationMessage>,
        userHistoryMessage: AgentModelClient.ConversationMessage,
        compressorLabel: String = "",
    ) {
        if (compressedHistory == originalHistory) return
        val current = conversationsById[conversationId] ?: return
        val expected = originalHistory + userHistoryMessage
        if (current.history.map { it.copy(turnId = "") } != expected.map { it.copy(turnId = "") }) return
        updateConversation(
            conversationId,
            current.copy(
                isCompressingContext = false,
                isWaitingForCompression = false,
                history = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(compressedHistory) + userHistoryMessage,
                livePromptTokens = null,
                messages = AgentContextCompactionUi.applyMarker(
                    messages = current.messages,
                    originalHistory = originalHistory,
                    compressedHistory = compressedHistory,
                    extraKeptUserMessages = 1,
                    compressorLabel = compressorLabel,
                    baselineTokens = (compressedHistory + userHistoryMessage).sumOf {
                        AgentContextBudget.countMessage(it)
                    },
                    resumeRound = 1,
                ),
            ),
        )
        if (conversationId == selectedConversationId) {
            billedOverheadConversationId = conversationId
            billedOverheadTokens = null
        }
        persistConversations()
        showCompressionCompletedToast(conversationId, originalHistory, compressedHistory, compressorLabel)
    }

    /** Called on Main only after an accepted history update, never from replay/projection. */
    private fun showCompressionCompletedToast(
        conversationId: String?,
        originalHistory: List<AgentModelClient.ConversationMessage>,
        compressedHistory: List<AgentModelClient.ConversationMessage>,
        compressorLabel: String,
    ) {
        if (conversationId != selectedConversationId) return
        val count = AgentContextCompactionUi.completedMessageCount(
            originalHistory, compressedHistory, compressorLabel,
        )
        if (count <= 0) return
        Toast.makeText(
            appContext,
            appContext.resources.getQuantityString(R.plurals.context_compacted_messages, count, count),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun showRevisionHistoryUnavailableNotice() {
        Toast.makeText(appContext, R.string.revision_history_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun showCompactedRevisionNotice() {
        Toast.makeText(
            appContext,
            R.string.state_ui_the_earlier_context_has_been_compressed_and_will_cf6c86,
            Toast.LENGTH_LONG,
        ).show()
    }

    fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val image = AgentImageCodec.fromReference(
                    context = appContext,
                    value = uri,
                    source = "user_attach",
                )
                if (image == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.state_ui_unable_to_read_this_image_please_try_again_or_us_d94978),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val preview = AgentImageCodec.previewFromReference(appContext, image) ?: image
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    // 后续发送使用首次读取后的稳定引用，不再依赖 ROM Photo Picker URI 的授权生命周期。
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                )
                withContext(Dispatchers.Main) {
                    updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun attachVideo(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val parsed = Uri.parse(uri)
                val attachment = AgentVideoCodec.importFromUri(appContext, parsed)
                if (attachment == null) {
                    withContext(Dispatchers.Main) {
                        val tooLarge = runCatching {
                            appContext.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { it.length }
                        }.getOrNull()?.let { it > MAX_AGENT_VIDEO_BYTES.toLong() } == true
                        Toast.makeText(
                            appContext,
                            if (tooLarge) {
                                appContext.getString(
                                    R.string.state_ui_video_exceeds_limit,
                                    MAX_AGENT_VIDEO_BYTES / 1024 / 1024,
                                )
                            } else {
                                appContext.getString(R.string.state_ui_unable_to_read_this_video)
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val pending = PendingImageUi(
                    id = "vid-${UUID.randomUUID()}",
                    uri = attachment.file.absolutePath,
                    dataUrl = attachment.thumbnail.reference,
                    mimeType = attachment.mimeType,
                    isVideo = true,
                    durationMs = attachment.durationMs.takeIf { it > 0L },
                    byteSize = attachment.bytes,
                )
                withContext(Dispatchers.Main) {
                    updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun removePendingImage(id: String) {
        updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages.filterNot { it.id == id }))
    }

    fun attachSharedUris(uris: List<String>) {
        if (uris.isEmpty()) return
        val images = mutableListOf<String>()
        val videos = mutableListOf<String>()
        val files = mutableListOf<String>()
        uris.forEach { raw ->
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@forEach
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val mime = appContext.contentResolver.getType(uri).orEmpty()
            val name = uri.lastPathSegment.orEmpty()
            when {
                mime.startsWith("image/") || name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
                    name.endsWith(".jpeg", true) || name.endsWith(".webp", true) || name.endsWith(".gif", true) ->
                    images += raw
                mime.startsWith("video/") || name.endsWith(".mp4", true) || name.endsWith(".webm", true) ||
                    name.endsWith(".mov", true) || name.endsWith(".mkv", true) || name.endsWith(".3gp", true) ->
                    videos += raw
                else -> files += raw
            }
        }
        images.forEach(::attachImage)
        videos.forEach(::attachVideo)
        if (files.isNotEmpty()) attachFiles(files)
    }

    fun attachFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            uris.map { uri ->
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.File,
                )
            }
        }
    }

    fun attachFolder(uri: String) {
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            listOf(
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.Directory,
                )
            )
        }
    }

    fun attachFilePath(path: String) {
        resolveAndAttachFileReferences {
            listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path))
        }
    }

    fun attachConversationMention(conversationId: String): Boolean {
        val pending = homeState.pendingConversationMentions
        if (conversationId == selectedConversationId || pending.any { it.conversationId == conversationId }) return false
        val source = conversationsById[conversationId] ?: return false
        val budget = minOf(ConversationMention.MAX_TRANSCRIPT_CHARS, ConversationMention.remainingTranscriptBudget(pending))
        if (pending.size >= ConversationMention.MAX_ATTACHED || budget < 128) {
            Toast.makeText(appContext, "最多引用 3 个会话，总内容过大时会省略中间记录。", Toast.LENGTH_SHORT).show()
            return false
        }
        val status = if (source.isStreaming) "[选择时快照：来源会话仍在运行，未包含后续输出]\n" else ""
        val mentionId = "mention-${UUID.randomUUID()}"
        val ownerVersion = fileAttachmentOwnerVersion
        preparingConversationMentions += mentionId
        updateCurrentConversation(homeState.copy(pendingConversationMentions = pending + PendingConversationMentionUi(
            id = mentionId, conversationId = conversationId,
            title = conversationTitles[conversationId].orEmpty().ifBlank { "未命名会话" },
            transcript = "[正在准备会话原始工具记录]",
        )))
        scope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) {
                    val evidence = io.github.mangi.eta.ui.model.ConversationToolEvidence(source.messages)
                    evidence.add(source.history, "source conversation model history")
                    io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, conversationId)
                        .visitForConversationMention(evidence::add)
                    status + ConversationMention.transcript(
                        source.messages, budget - status.length, appContext.filesDir, conversationId, evidence,
                    )
                }
                if (ownerVersion != fileAttachmentOwnerVersion) return@launch
                if (conversationId !in conversationsById) {
                    removeConversationMention(mentionId)
                    return@launch
                }
                val current = homeState.pendingConversationMentions
                if (current.none { it.id == mentionId }) return@launch
                val remaining = ConversationMention.remainingTranscriptBudget(current.filterNot { it.id == mentionId })
                if (transcript.isBlank() || transcript.length > remaining) {
                    removeConversationMention(mentionId)
                    Toast.makeText(appContext, "会话引用为空或超过总长度限制，请重新选择。", Toast.LENGTH_SHORT).show()
                } else {
                    updateCurrentConversation(homeState.copy(pendingConversationMentions = current.map {
                        if (it.id == mentionId) it.copy(transcript = transcript) else it
                    }))
                }
            } catch (failure: Exception) {
                if (ownerVersion == fileAttachmentOwnerVersion) {
                    removeConversationMention(mentionId)
                    Toast.makeText(appContext, "会话引用准备失败，请重试。", Toast.LENGTH_SHORT).show()
                }
                if (failure is kotlinx.coroutines.CancellationException) throw failure
            } finally {
                preparingConversationMentions -= mentionId
                // Switching conversations must not leave a sendable "preparing" placeholder.
                fun isUnfinished(item: PendingConversationMentionUi) =
                    item.id == mentionId && item.transcript == "[正在准备会话原始工具记录]"
                conversationsById.toMap().forEach { (id, state) ->
                    if (state.pendingConversationMentions.any(::isUnfinished)) {
                        updateConversation(id, state.copy(pendingConversationMentions =
                            state.pendingConversationMentions.filterNot(::isUnfinished)), updateTimestamp = false)
                    }
                }
                if (selectedConversationId == null && homeState.pendingConversationMentions.any(::isUnfinished)) {
                    homeState = homeState.copy(pendingConversationMentions =
                        homeState.pendingConversationMentions.filterNot(::isUnfinished))
                }
            }
        }
        return true
    }

    fun removeConversationMention(id: String) {
        updateCurrentConversation(homeState.copy(
            pendingConversationMentions = homeState.pendingConversationMentions.filterNot { it.id == id },
        ))
    }

    fun removePendingFileReference(id: String) {
        updateCurrentConversation(
            homeState.copy(
                pendingFileReferences = homeState.pendingFileReferences.filterNot { it.id == id }
            )
        )
    }

    private fun resolveAndAttachFileReferences(
        resolver: () -> List<AgentFileReferenceGateway.Resolution>,
    ) {
        val ownerVersion = fileAttachmentOwnerVersion
        scope.launch(Dispatchers.IO) {
            val resolutions = resolver()
            val references = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Success)?.reference
            }
            val failures = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Failure)?.error
            }
            withContext(Dispatchers.Main) {
                if (ownerVersion != fileAttachmentOwnerVersion) {
                    Toast.makeText(appContext, appContext.getString(R.string.state_ui_conversation_switched_selected_path_not_added_5bf91e), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val existingPaths = homeState.pendingFileReferences
                    .mapTo(mutableSetOf()) { it.reference.absolutePath }
                val additions = references
                    .distinctBy { it.absolutePath }
                    .filter { existingPaths.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(
                            id = "file-${UUID.randomUUID()}",
                            reference = reference,
                        )
                    }
                if (additions.isNotEmpty()) {
                    updateCurrentConversation(
                        homeState.copy(
                            pendingFileReferences = homeState.pendingFileReferences + additions
                        )
                    )
                }
                val message = when {
                    failures.size == 1 && references.isEmpty() -> failures.single().userMessage
                    failures.isNotEmpty() -> appContext.resources.getQuantityString(
                        R.plurals.file_references_added_with_failures,
                        failures.size,
                        additions.size,
                        failures.size,
                    )
                    additions.isEmpty() -> appContext.getString(R.string.state_ui_the_selected_path_has_been_added_42b432)
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val AgentFileReferenceGateway.Error.userMessage: String
        get() = when (this) {
            AgentFileReferenceGateway.Error.UnsupportedDocumentProvider ->
                appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.InvalidPath -> appContext.getString(R.string.state_ui_please_enter_a_valid_absolute_path_6afeb4)
            AgentFileReferenceGateway.Error.PathNotFound -> appContext.getString(R.string.state_ui_the_path_does_not_exist_or_is_no_longer_accessib_a9776e)
            AgentFileReferenceGateway.Error.UnsupportedFileType -> appContext.getString(R.string.state_ui_only_supports_normal_files_and_folders_4adea0)
            AgentFileReferenceGateway.Error.TypeMismatch -> appContext.getString(R.string.state_ui_the_selected_project_type_does_not_match_3a5c49)
            AgentFileReferenceGateway.Error.RootUnavailable -> appContext.getString(R.string.state_ui_root_is_not_available_and_the_path_cannot_be_ver_fc4c81)
            AgentFileReferenceGateway.Error.AccessDenied -> appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.ImportFailed -> appContext.getString(R.string.capability_import_failed)
            AgentFileReferenceGateway.Error.ImportTooLarge -> appContext.getString(R.string.capability_import_too_large)
            AgentFileReferenceGateway.Error.ValidationTimedOut -> appContext.getString(R.string.state_ui_path_verification_timed_out_please_try_again_703687)
        }

    fun stopCurrentRun() {
        val runId = activeRunIdForSelectedConversation() ?: return
        stopRun(runId)
    }

    private fun stopRun(runId: String) {
        if (activeRunIdForSelectedConversation() == runId) io.github.mangi.eta.agent.voice.tts.SpeechPlayback.stop()
        if (stoppingRuns.containsKey(runId)) return
        val imageGen = imageGenerationRunIds.remove(runId)
        if (imageGen) directMediaRuns.cancel(runId)
        flushPendingRunDelta(runId)
        val retrying = modelRetryState.isWaiting(runId)
        if (!imageGen) {
            stoppingRuns[runId] = retrying
            scope.launch(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
            }
            updateRunTrace(runId) { messages ->
                val thinking = runMessageProjector.finalizeThinking(runId, messages)
                runMessageProjector.failRunningTools(
                    SYNTHETIC_STATUS_STOPPED, runMessageProjector.finalizeText(runId, thinking),
                )
            }
        } else {
            runJobs.remove(runId)?.cancel()
        }
        replaceLatestAssistantWithNotice(
            runId,
            if (retrying) SystemNoticeCode.RuntimeFailed else SystemNoticeCode.Stopped,
            detail = when {
                imageGen -> "已停止本地生成等待并取消网络请求；服务端任务可能仍在处理或计费，不会自动重发。"
                retrying -> "已停止等待接口重试"
                else -> null
            },
        )
        // Immediate UI feedback, without cancelling the result subscriber or losing history.
        setConversationStreaming(runId, false)
        if (imageGen) {
            runMessageProjector.clearRun(runId)
            runGeneratedAtMillis.remove(runId)
            runConversationIds.remove(runId)
            runCloudUsage.remove(runId)
            runUsageResumeRounds.remove(runId)
            runOverheadTokens.remove(runId)
            runCompressedDuringRun.remove(runId)
        }
        refreshConversationSummaries()
        persistConversations()
    }

    fun pauseCurrentRun() {
        val runId = activeRunIdForSelectedConversation() ?: return
        if (stoppingRuns.containsKey(runId)) return
        if (runId in imageGenerationRunIds) {
            // Media APIs have no resumable pause contract. Stop local I/O instead of ignoring the button.
            stopRun(runId)
            return
        }
        if (homeState.isPaused) return
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).pauseRun(runId)
        }
        // 只标记暂停，不把消息冻成 isStreaming=false。否则 StreamingMarkdown 会当成
        // 生成结束切到整段 Text；继续后每个 token 都整段重组，流式输出会明显卡顿。
        updateCurrentConversation(homeState.copy(isPaused = true))
    }

    fun abandonPausedRun() {
        if (rejectConversationArchiveMutation()) return
        if (!homeState.isPaused) return
        stopCurrentRun()
        Toast.makeText(
            appContext,
            appContext.getString(R.string.chat_paused_run_abandoned),
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun selectAssistant(id: String) {
        if (rejectConversationArchiveMutation()) return
        if (homeState.isStreaming && !homeState.isPaused) return
        if (AssistantRepository.profile(id) == null) return
        if (homeState.isPaused) {
            val conversationId = selectedConversationId
            val job = activeRunIdForSelectedConversation()?.let(runJobs::get)
            abandonPausedRun()
            scope.launch {
                job?.join()
                if (selectedConversationId == conversationId) selectAssistant(id)
            }
            return
        }
        val discarded = memoryState.hasUnsavedChanges &&
            memoryState.assistantId.isNotBlank() &&
            memoryState.assistantId != id
        applyConversationAssistant(id, persist = true)
        if (discarded) {
            Toast.makeText(appContext, "已切换助手，未保存的记忆草稿未写入。", Toast.LENGTH_LONG).show()
        }
    }

    private fun abortActiveRunForRevision() {
        abortConversationRun(
            conversationId = selectedConversationId,
            startPendingCompress = pendingManualCompress != null,
        )
    }

    private fun abortConversationRun(conversationId: String?, startPendingCompress: Boolean) {
        contextBudgetBlockedRuns.entries.removeAll { it.value.conversationId == conversationId }
        if (contextBudgetPrompt?.conversationId == conversationId) contextBudgetPrompt = null
        val runId = runIdForConversation(conversationId)
        if (runId != null) {
            directMediaRuns.cancel(runId)
            imageGenerationRunIds.remove(runId)
            runJobs.remove(runId)?.cancel()
            scope.launch(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
            }
            runMessageProjector.clearRun(runId)
            runGeneratedAtMillis.remove(runId)
            runConversationIds.remove(runId)
            runCloudUsage.remove(runId)
            runUsageResumeRounds.remove(runId)
            runOverheadTokens.remove(runId)
            runCompressedDuringRun.remove(runId)
        }
        if (conversationId == null) {
            val frozen = runMessageProjector.failRunningTools(
                SYNTHETIC_STATUS_STOPPED,
                freezeStreamingMessages(homeState.messages),
            )
            homeState = homeState.copy(
                isStreaming = false,
                isPaused = false,
                isCompressingContext = shouldKeepCompressingIndicator(null),
                isWaitingForCompression = false,
                messages = frozen,
                history = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
                    homeState.history,
                    frozen,
                ),
            )
        } else {
            val state = conversationsById[conversationId]
            if (state != null) {
                val frozen = runMessageProjector.failRunningTools(
                    SYNTHETIC_STATUS_STOPPED,
                    freezeStreamingMessages(state.messages),
                )
                updateConversation(
                    conversationId,
                    state.copy(
                        isStreaming = false,
                        isPaused = false,
                        isCompressingContext = shouldKeepCompressingIndicator(conversationId),
                        isWaitingForCompression = false,
                        messages = frozen,
                        history = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
                            state.history,
                            frozen,
                        ),
                    ),
                )
            }
        }
        persistConversations()
        if (startPendingCompress && pendingManualCompress != null) {
            conversationId?.let(::onConversationRunSettled)
                ?: startPendingManualCompress()
        }
    }

    fun continuePausedGeneration() {
        if (rejectConversationArchiveMutation()) return
        if (rejectSendIfCompressing()) return
        if (!homeState.isPaused) {
            continueDisconnectedGeneration()
            return
        }
        val runId = activeRunIdForSelectedConversation() ?: return
        contextBudgetBlockedRuns[runId]?.let { contextBudgetPrompt = it; return }
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).resumeRun(runId)
        }
        updateCurrentConversation(homeState.copy(isPaused = false))
    }

    private fun continueDisconnectedGeneration() {
        if (homeState.isStreaming || homeState.isPaused) return
        if (rejectSendIfModelUnavailable()) return
        if (!canContinueDisconnectedRun(homeState.messages)) return
        val conversationId = selectedConversationId ?: return
        val state = conversationsById[conversationId] ?: return
        val committed = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
            state.history,
            state.messages,
        )
        val continuedHistory = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(committed)
        val prompt = RESUME_AFTER_COMPRESS_PROMPT
        val runId = "run-${UUID.randomUUID()}"
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = prompt,
            images = emptyList(),
            history = continuedHistory,
            userHistoryMessage = AgentModelClient.ConversationMessage(
                role = "user",
                content = prompt,
            ),
            messages = state.messages,
            state = state,
            reasoningEffort = state.reasoningEffort,
            skipAutoCompress = true,
            // 失败那一轮已经结束并保留。点击重试是新开一轮继续，不再复用旧 turnId。
            logicalTurnId = runId,
        )
    }

    fun steerCurrentRun(text: String) {
        if (rejectConversationArchiveMutation()) return
        val runId = activeRunIdForSelectedConversation() ?: return
        if (runId in imageGenerationRunIds) return
        if (rejectSendIfCompressing()) return
        val prompt = text.trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        val pendingMentions = homeState.pendingConversationMentions
        if (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty() && pendingMentions.isEmpty()) return
        val fileReferences = pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.state_ui_file_path_reference_requires_opening_the_termina_deca4c),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val conversationId = selectedConversationId
        if (pendingSteerDrafts.values.any { it.conversationId == conversationId }) return
        val requestId = UUID.randomUUID().toString()
        pendingSteerDrafts[requestId] = PendingSteerDraft(conversationId,
            pendingImages.map { it.id }.toSet(), pendingFileReferences.map { it.id }.toSet(),
            pendingMentions.map { it.id }.toSet(), submittedText = text)
        scope.launch(Dispatchers.IO) {
          try {
            // Preserve position and reject partial staging, rather than silently shifting sources.
            val staged = conversationId?.let { stageChatImages(it, pendingImages) }.orEmpty()
            if (staged.size != pendingImages.size || staged.any { it == null }) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "附件保存失败，追问未发送，附件仍保留。", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val references = staged.filterNotNull()
            val previews = pendingImages.mapIndexed { index, image ->
                if (image.isVideo) {
                    val bytes = AgentChatImageCache.readBytes(image.dataUrl)
                    val preview = bytes?.let { chatImageCache.stage(conversationId!!, it, "preview-${image.id}.jpg") }
                    preview?.absolutePath ?: image.dataUrl
                } else references[index].absolutePath
            }
            val imagesJson = io.github.mangi.eta.ui.model.encodeUserMessageImages(
                previews, references.map { it.absolutePath },
                pendingImages.map { it.isVideo }, pendingImages.map { it.durationMs },
            )
            // Never put a large base64 preview into Binder; leave the draft intact on failure.
            if (imagesJson.length > 64_000) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "附件预览保存失败，追问未发送。", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val steerText = AgentFileReferencePromptCodec.format(prompt, fileReferences + references, pendingMentions.toMentionedConversations())
                .ifBlank { "请查看我补充的附件。" }
            val sent = AgentRuntimeClient(appContext, AndroidAgentLogger)
                .steerRun(runId, steerText, requestId, imagesJson)
            withContext(Dispatchers.Main) {
                if (!sent) {
                    Toast.makeText(appContext, "追问未送达，附件仍保留，请重试。", Toast.LENGTH_LONG).show()
                }
            }
            if (sent) {
                // Only the accepted event clears a draft, not a successful Binder send.
                kotlinx.coroutines.delay(15_000)
                withContext(Dispatchers.Main) {
                    if (requestId in pendingSteerDrafts) {
                        Toast.makeText(appContext, "尚未收到追问确认，附件仍保留。请先检查会话，避免重复发送。", Toast.LENGTH_LONG).show()
                    }
                }
            }
          } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, "追问发送失败，附件仍保留。", Toast.LENGTH_LONG).show()
            }
          } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                pendingSteerDrafts.remove(requestId)
            }
          }
        }
    }

    private fun snapshotPartialAssistantToHistory(runId: String) {
        val conversationId = conversationIdForRun(runId) ?: selectedConversationId ?: return
        val state = conversationsById[conversationId] ?: return
        val assistant = state.messages.lastOrNull { message ->
            message is AgentMessageUi && message.content.isNotBlank()
        } as? AgentMessageUi ?: return
        val taggedHistory = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(state.history)
        val last = taggedHistory.lastOrNull()
        if (last?.role == "assistant" && last.content == assistant.content) return
        updateConversation(
            conversationId,
            state.copy(
                history = taggedHistory + AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = assistant.content,
                    turnId = taggedHistory.lastOrNull { it.turnId.isNotBlank() }?.turnId ?: runId,
                ),
            ),
        )
    }

    private var permissionRefreshJob: Job? = null

    fun refreshPermissionHealth() {
        permissionRefreshJob?.cancel()
        permissionRefreshJob = scope.launch(Dispatchers.IO) {
            val refreshed = buildPermissionHealthState(appContext)
            withContext(Dispatchers.Main) { permissionHealthState = refreshed }
        }
    }

    fun refreshSkills() {
        scope.launch(Dispatchers.IO) {
            val entries = runCatching {
                SkillRuntime.createIndexService(appContext)
                    .listSkillsForManagement(forceRefresh = true)
            }.getOrElse {
                withContext(Dispatchers.Main) {
                    skillsState = skillsState.copy(
                        isLoading = false,
                        notice = skillsState.notice ?: newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_read_skills_599082),
                            message = appContext.getString(R.string.state_the_skill_list_is_temporarily_unavailable_please_try_29b0be),
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            val items = entries.map { entry ->
                val capabilities = buildList {
                    if (entry.hasScripts) add("scripts")
                    if (entry.hasReferences) add("references")
                    if (entry.hasAssets) add("assets")
                    if (entry.hasEvals) add("evals")
                }
                SkillItemUi(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    source = entry.source,
                    enabled = entry.enabled,
                    installed = entry.installed,
                    capabilities = capabilities,
                )
            }
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(skills = items, isLoading = false)
            }
            refreshRequestOverhead()
        }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).setSkillEnabled(skillId, enabled)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_update_skills_04e56c),
                            message = appContext.getString(R.string.state_the_skill_switch_has_not_changed_please_try_again_la_fa262f),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun deleteSkill(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val skill = skillsState.skills.firstOrNull { it.id == skillId }
            ?.takeIf { it.canDeleteUserSkill }
            ?: return
        val skillName = skill.name.safeSkillDisplayName()
        skillsState = skillsState.copy(busySkillId = skillId, notice = null)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).deleteSkill(skillId)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_has_been_deleted_34c29b),
                            message = appContext.getString(R.string.skill_deleted_message, skillName),
                            isError = false,
                        )
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_delete_skill_1583c9),
                            message = appContext.getString(R.string.state_deletion_is_not_complete_eta_will_try_to_recover_whe_c4297e),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun importSkillZip(uriValue: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull()
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        if (uri == null) {
            skillsState = skillsState.copy(
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_read_skill_pack_a53563),
                    message = appContext.getString(R.string.state_please_select_the_zip_file_provided_by_the_system_fi_fea145),
                    isError = true,
                ),
            )
            return
        }
        pendingSkillZipUri = uri
        pendingSkillZipSha256 = null
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = false,
            expectedReplacementId = null,
            expectedArchiveSha256 = null,
        )
    }

    fun confirmSkillZipReplacement() {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = pendingSkillZipUri
        if (uri == null) {
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_skill_pack_is_no_longer_available_please_select_the__7cdfb4),
                    isError = true,
                ),
            )
            return
        }
        val replacementId = skillsState.replacement?.id
        val archiveSha256 = pendingSkillZipSha256
        if (replacementId == null || archiveSha256 == null) {
            pendingSkillZipUri = null
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_replacement_confirmation_has_expired_please_select_t_fce9f2),
                    isError = true,
                ),
            )
            return
        }
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = true,
            expectedReplacementId = replacementId,
            expectedArchiveSha256 = archiveSha256,
        )
    }

    fun cancelSkillZipReplacement() {
        if (skillsState.isImporting) return
        pendingSkillZipUri = null
        pendingSkillZipSha256 = null
        skillsState = skillsState.copy(replacement = null)
    }

    private fun launchSkillZipImport(
        uri: Uri,
        replaceUserSkill: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ) {
        skillsState = skillsState.copy(
            isImporting = true,
            replacement = null,
            notice = null,
        )
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                skillZipImportGateway.installLocalZip(
                    openStream = {
                        appContext.contentResolver.openInputStream(uri)
                            ?: error(appContext.getString(R.string.state_ui_unable_to_open_selection_9f0004))
                    },
                    replaceUserSkill = replaceUserSkill,
                    expectedReplacementId = expectedReplacementId,
                    expectedArchiveSha256 = expectedArchiveSha256,
                )
            }.getOrElse {
                SkillZipImportOutcome.Failure(SkillZipImportOutcome.FailureCode.READ_FAILED)
            }
            withContext(Dispatchers.Main) {
                applySkillZipImportOutcome(outcome)
            }
        }
    }

    private fun restoreRunEvents(runId: String, events: List<AgentEvent>) {
        // 恢复是完整快照：先清除同一 run 的旧投影，再一次发布，避免历史增量重复追加
        // 或中途的 Running 状态使已结束的思考重新展开、播放动画。
        Snapshot.withMutableSnapshot {
            flushPendingRunDelta(runId)
            updateMessages(runId, updateTimestamp = false) { messages ->
                runMessageProjector.resetForReplay(
                    runId = runId,
                    messages = messages,
                    replaySupplementIndexes = events.filterIsInstance<AgentEvent.UserSupplementReceived>()
                        .mapTo(mutableSetOf()) { it.index },
                )
            }
            events.forEach { event -> applyRunEvent(runId, event, persistSupplement = false, replaying = true) }
        }
    }

    private fun enqueueRunEvent(runId: String, event: AgentEvent) {
        if (event is AgentEvent.AssistantBlockDelta) {
            StreamPerformanceDiagnostics.record("ui.delta.received", value = event.delta.length.toLong())
        }
        if (stoppingRuns.containsKey(runId) && event !is AgentEvent.ContextCompacted &&
            event !is AgentEvent.UserSupplementReceived && event !is AgentEvent.UsageReceived && event !is AgentEvent.ChildContextUpdated) return
        if (runMessageProjector.isSealed(runId) && !event.allowedAfterSeal()) {
            runEventFlushJobs.remove(runId)?.cancel()
            runEventCoalescer.flush(runId)
            return
        }
        if (event is AgentEvent.AssistantBlockDelta) {
            if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL || event.delta.isEmpty()) return
            runEventCoalescer.append(runId, event)?.let { ready ->
                applyRunEvent(runId, ready)
            }
            scheduleRunDeltaFlush(runId)
            return
        }

        flushPendingRunDelta(runId)
        applyRunEvent(runId, event)
    }

    private fun AgentEvent.allowedAfterSeal(): Boolean =
        this is AgentEvent.UsageReceived || this is AgentEvent.ChildContextUpdated

    private fun scheduleRunDeltaFlush(runId: String) {
        if (runEventFlushJobs[runId]?.isActive == true) return
        val scheduledAtNs = System.nanoTime()
        runEventFlushJobs[runId] = scope.launch {
            delay(STREAM_UI_UPDATE_INTERVAL_MS)
            StreamPerformanceDiagnostics.record("ui.flushDelay", System.nanoTime() - scheduledAtNs)
            runEventFlushJobs.remove(runId)
            flushPendingRunDelta(runId)
        }
    }

    private fun flushPendingRunDelta(runId: String) {
        runEventFlushJobs.remove(runId)?.cancel()
        runEventCoalescer.flush(runId)?.let { event ->
            applyRunEvent(runId, event)
        }
    }

    private fun applySkillZipImportOutcome(outcome: SkillZipImportOutcome) {
        when (outcome) {
            is SkillZipImportOutcome.Success -> {
                val installed = outcome.skills.singleOrNull()
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = if (installed == null) {
                        skillZipFailureNotice(SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS)
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_installed_b07e54),
                            message = appContext.getString(
                                R.string.skill_enabled_message,
                                installed.name.safeSkillDisplayName(),
                            ),
                            isError = false,
                        )
                    },
                )
                if (installed != null) refreshSkills()
            }

            is SkillZipImportOutcome.Conflict -> {
                val conflict = outcome.skills.singleOrNull()
                val archiveSha256 = outcome.archiveSha256
                if (
                    conflict != null &&
                    conflict.source == "user" &&
                    conflict.replaceAllowed &&
                    archiveSha256 != null
                ) {
                    val existingName = skillsState.skills
                        .firstOrNull { it.id == conflict.id && it.installed }
                        ?.name
                        .orEmpty()
                        .ifBlank { conflict.name }
                    pendingSkillZipSha256 = archiveSha256
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = SkillReplacementUi(
                            id = conflict.id,
                            name = existingName.safeSkillDisplayName(),
                        ),
                        notice = null,
                    )
                } else {
                    pendingSkillZipUri = null
                    pendingSkillZipSha256 = null
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = null,
                        notice = skillZipFailureNotice(
                            if (conflict?.source == "builtin") {
                                SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT
                            } else if (conflict != null && conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED
                            } else if (conflict != null && !conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE
                            } else {
                                SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS
                            },
                        ),
                    )
                }
            }

            is SkillZipImportOutcome.Failure -> {
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = skillZipFailureNotice(outcome.code),
                )
                if (outcome.code == SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED) {
                    refreshSkills()
                }
            }
        }
    }

    private fun skillZipFailureNotice(code: SkillZipImportOutcome.FailureCode): SkillNoticeUi {
        val message = when (code) {
            SkillZipImportOutcome.FailureCode.INVALID_ARCHIVE -> appContext.getString(R.string.state_ui_the_selected_file_is_not_a_valid_zip_package_bff052)
            SkillZipImportOutcome.FailureCode.ARCHIVE_LIMIT_EXCEEDED -> appContext.getString(R.string.state_ui_the_skill_pack_exceeds_the_safe_size_or_file_num_42e151)
            SkillZipImportOutcome.FailureCode.UNSAFE_ARCHIVE -> appContext.getString(R.string.state_ui_the_skill_pack_contains_an_unsafe_file_path_and__d8cfc0)
            SkillZipImportOutcome.FailureCode.NO_SKILL -> appContext.getString(R.string.state_ui_skill_md_not_found_in_zip_a57975)
            SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS -> appContext.getString(R.string.state_ui_the_local_zip_must_contain_only_one_skill_b89daf)
            SkillZipImportOutcome.FailureCode.INVALID_SKILL -> appContext.getString(R.string.state_ui_skill_md_is_missing_required_information_or_is_i_debe6e)
            SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED -> appContext.getString(R.string.state_ui_the_zip_content_has_changed_please_reselect_and__ab8b12)
            SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT -> appContext.getString(R.string.state_ui_built_in_skills_with_the_same_name_are_protected_446ad3)
            SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE ->
                appContext.getString(R.string.state_ui_the_target_with_the_same_name_is_not_a_user_skil_00474b)
            SkillZipImportOutcome.FailureCode.READ_FAILED -> appContext.getString(R.string.state_ui_the_selected_file_cannot_be_read_please_select_a_7265b9)
            SkillZipImportOutcome.FailureCode.STORAGE_FAILED -> appContext.getString(R.string.state_ui_unable_to_save_skills_original_skills_have_been__9d4748)
            SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED ->
                appContext.getString(R.string.state_ui_the_installation_failed_and_automatic_recovery_d_0d9e01)
        }
        return newSkillNotice(
            title = appContext.getString(R.string.state_unable_to_install_skill_0ec70b),
            message = message,
            isError = true,
        )
    }

    fun reinstallBuiltin(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).installBuiltinSkill(skillId)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_restore_skills_6b3d23),
                            message = appContext.getString(R.string.state_the_built_in_skills_have_not_changed_please_try_agai_34e5e3),
                            isError = true,
                        )
                    },
                )
            }
            if (succeeded) refreshSkills()
        }
    }

    fun dismissSkillNotice() {
        skillsState = skillsState.copy(notice = null)
    }

    private fun newSkillNotice(
        title: String,
        message: String,
        isError: Boolean,
    ): SkillNoticeUi = SkillNoticeUi(
        id = ++skillNoticeSequence,
        title = title,
        message = message,
        isError = isError,
    )

    private fun String.safeSkillDisplayName(): String =
        lineSequence().firstOrNull().orEmpty().trim().ifBlank { appContext.getString(R.string.state_ui_unnamed_skill_a58008) }.take(80)

    private fun applyRunEvent(
        runId: String,
        event: AgentEvent,
        persistSupplement: Boolean = true,
        replaying: Boolean = false,
    ) {
        modelRetryState.accept(runId, event)
        when (event) {
            is AgentEvent.AssistantBlockStart -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.startAssistantBlock(runId, event, messages)
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                updateMessages(runId, updateTimestamp = false) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.appendTextDelta(
                                runId,
                                event.round,
                                event.index,
                                event.delta,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.appendReasoningDelta(
                                runId,
                                event.round,
                                event.index,
                                event.delta,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.AssistantBlockEnd -> {
                updateRunTrace(runId) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.finalizeTextBlock(
                                runId,
                                event.round,
                                event.index,
                                event.replacementContent,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.finalizeThinkingBlock(
                                runId,
                                event.round,
                                event.index,
                                event.replacementContent,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.ChildContextUpdated -> {
                conversationIdForRun(runId)?.let { id ->
                    conversationsById[id]?.let conversation@ { current ->
                        if (current.childContextRunId.isNotBlank() && current.childContextRunId != runId) return@conversation
                        if (!timedOutChildVisibility.observe(runId, event.stats.taskId, event.stats.status)) return@conversation
                        val existing = if (current.childContextRunId == runId) current.childContexts else emptyList()
                        val updated = existing.toMutableList()
                        val index = updated.indexOfFirst { it.taskId == event.stats.taskId }
                        val previousManual = existing.getOrNull(index)?.manualCompactionState
                        if (event.stats.manualCompactionState == "ended" && previousManual != "ended") {
                            Toast.makeText(appContext, "子任务已结束，压缩请求已收束；结果保留，不会重启任务或压缩主代理。", Toast.LENGTH_LONG).show()
                        }
                        if (index < 0) updated += event.stats else updated[index] = event.stats
                        updateConversation(id, current.copy(childContextRunId = runId, childContexts = updated), updateTimestamp = false)
                        val hideStatus = event.stats.status
                        if (timedOutChildVisibility.schedulesHide(hideStatus) && existing.getOrNull(index)?.status != hideStatus) {
                            scope.launch {
                                delay(timedOutChildVisibility.remaining(runId, event.stats.taskId))
                                val latest = conversationsById[id] ?: return@launch
                                if (latest.childContextRunId != runId) return@launch
                                val taskId = event.stats.taskId
                                if (latest.childContexts.none { it.taskId == taskId && it.status == hideStatus }) return@launch
                                updateConversation(id, latest.copy(
                                    childContexts = latest.childContexts.filterNot { it.taskId == taskId },
                                    selectedContextTaskId = latest.selectedContextTaskId.takeUnless { it == taskId },
                                ), updateTimestamp = false)
                            }
                        }
                    }
                }
            }

            is AgentEvent.UsageReceived -> {
                // Projected events from older runtimes never change cloud occupancy.
                if (!event.projected && !isStaleUsageAfterCompact(runId, event.round)) {
                    val occupancy = event.usage.occupancyTokens()
                    updateAssistantUsage(runId, event.round, event.usage.toUi())
                    updateLivePromptTokens(runId, occupancy)
                }
            }

            is AgentEvent.UserSupplementReceived -> {
                conversationIdForRun(runId)?.let { id ->
                    conversationsById[id]?.takeIf { it.isPaused }?.let { state ->
                        updateConversation(id, state.copy(isPaused = false))
                    }
                }
                pendingSteerDrafts.remove(event.requestId)?.let { draft ->
                    val id = draft.conversationId
                    if (conversationDrafts.field(id).text.toString() == draft.submittedText) {
                        conversationDrafts.replace(id, "")
                    }
                    val state = id?.let(conversationsById::get)
                    if (id != null && state != null) {
                        updateConversation(id, state.copy(
                            pendingImages = state.pendingImages.filterNot { it.id in draft.imageIds },
                            pendingFileReferences = state.pendingFileReferences.filterNot { it.id in draft.fileIds },
                            pendingConversationMentions = state.pendingConversationMentions.filterNot { it.id in draft.mentionIds },
                        ))
                    }
                }
                insertSupplementMessage(runId, event.index, event.text, persist = persistSupplement,
                    requestId = event.requestId, imagesJson = event.imagesJson)
            }

            is AgentEvent.ToolStarted -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking =
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.ToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishTool(runId, event, messages)
                }
            }

            is AgentEvent.HostedToolStarted -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking =
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startHostedTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.HostedToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishHostedTool(runId, event, messages)
                }
            }

            is AgentEvent.ModelRetryScheduled -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.scheduleModelRetry(runId, event, messages)
                }
            }

            is AgentEvent.RunFailed -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
                    runMessageProjector.failRunningTools(event.reason, finalizedText)
                }
                runMessageProjector.seal(runId)
            }

            is AgentEvent.AssistantReceived -> {
                if (event.reasoningContent.isNotBlank()) {
                    updateRunTrace(runId) { messages ->
                        runMessageProjector.ensureCompletedThinking(
                            runId = runId,
                            round = event.round,
                            content = event.reasoningContent,
                            messages = messages,
                        )
                    }
                }
            }

            is AgentEvent.RunFinished -> {
                event.generatedAtMillis?.takeIf { it > 0 }?.let { runGeneratedAtMillis[runId] = it }
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalized = runMessageProjector.finalizeText(runId, finalizedThinking)
                    stampCompletedReply(finalized, runId, event.generatedAtMillis)
                }
                runMessageProjector.seal(runId)
            }

            is AgentEvent.ContextCompactionStarted -> {
                conversationIdForRun(runId)?.let { setConversationCompressing(it, true, event.modelName) }
            }

            is AgentEvent.ContextCompacted -> {
                applyRuntimeCompactedHistory(runId, event, notifyCompletion = !replaying)
            }

            is AgentEvent.ProviderRequestStarted -> {
                resetLiveUsageForRequest(runId)
                if (contextBudgetBlockedRuns.remove(runId) != null) {
                    if (contextBudgetPrompt?.runId == runId) contextBudgetPrompt = null
                    conversationIdForRun(runId)?.let { id -> conversationsById[id]?.let { updateConversation(id, it.copy(isPaused = false)) } }
                }
            }

            is AgentEvent.RunStarted -> {
                conversationIdForRun(runId)?.let { id -> conversationsById[id]?.let { current ->
                    if (current.childContextRunId != runId) updateConversation(id,
                        current.copy(childContexts = emptyList(), childContextRunId = runId, selectedContextTaskId = null), updateTimestamp = false)
                } }
            }
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
            is AgentEvent.RoundStarted,
            -> Unit
        }
    }

    private fun applyRuntimeCompactedHistory(
        runId: String,
        event: AgentEvent.ContextCompacted,
        notifyCompletion: Boolean,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        if (!event.applied || event.history.isEmpty()) {
            setConversationCompressing(conversationId, false)
            pendingInRunCompactConversationIds.remove(conversationId)
            if (event.blocked) {
                val prompt = ContextBudgetPrompt(runId, conversationId, event.reason)
                contextBudgetBlockedRuns[runId] = prompt
                if (selectedConversationId == conversationId) contextBudgetPrompt = prompt
                conversationsById[conversationId]?.let { updateConversation(conversationId, it.copy(isPaused = true)) }
            } else if (event.reason.isNotBlank() && selectedConversationId == conversationId) {
                Toast.makeText(appContext, event.reason, Toast.LENGTH_LONG).show()
            }
            return
        }
        runCompressedDuringRun.add(runId)
        runUsageResumeRounds[runId] = event.round
        runCloudUsage[runId]?.let { runCloudUsage[runId] = it.invalidate() }
        val current = conversationsById[conversationId] ?: return
        if (conversationId in pendingInRunCompactConversationIds &&
            AgentContextCompactionUi.isPruningOnly(current.history, event.history, event.compressorLabel)) {
            updateConversation(conversationId, current.copy(
                history = event.history,
                livePromptTokens = null,
            ))
            // Pruning committed a different context; wait for a new provider measurement.
            persistConversations()
            return
        }
        updateConversation(
            conversationId,
            current.copy(
                isCompressingContext = false,
                isWaitingForCompression = false,
                history = event.history,
                livePromptTokens = null,
                messages = AgentContextCompactionUi.applyMarker(
                    messages = current.messages,
                    originalHistory = current.history,
                    compressedHistory = event.history,
                    extraKeptUserMessages = 0,
                    compressorLabel = event.compressorLabel,
                    baselineTokens = event.history.sumOf { AgentContextBudget.countMessage(it) },
                    resumeRound = event.round,
                ),
            ),
        )
        pendingInRunCompactConversationIds.remove(conversationId)
        billedOverheadConversationId = conversationId
        billedOverheadTokens = null
        persistConversations()
        if (notifyCompletion) {
            showCompressionCompletedToast(conversationId, current.history, event.history, event.compressorLabel)
        }
    }

    /**
     * 用量达到自动压缩阈值时压缩已提交的历史。
     * 运行中由 Runtime 在安全边界统一处理；结束后按已提交历史检查。
     */
    private fun scheduleAutoCompress(
        conversationId: String,
        allowRepeat: Boolean,
        runId: String? = null,
    ) {
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED)) return
        if (!allowRepeat && runId != null && runId in runCompressedDuringRun) return
        val state = conversationsById[conversationId] ?: return
        if (state.isStreaming || state.isPaused) {
            return
        }
        val boundModel = AgentModelPickerProjector.project(selectionProviders, state.providerId, state.modelId).selectedModel
            ?.takeIf { it.providerId == state.providerId && it.id == state.modelId } ?: return
        val contextWindow = boundModel.contextWindow
        val estimatedTokens = liveContextUsage(
            history = state.history,
            currentInput = "",
            pendingImages = emptyList(),
            selectedModel = boundModel,
            billedContextTokens = billedPromptTokens(state),
            requestOverheadTokens = requestOverheadTokens,
            billedOverheadTokens = billedOverheadTokens,
        ).contextTokens
        if (!shouldAutoCompress(state.history, contextWindow, estimatedTokens)) return
        if (runId != null && !allowRepeat) {
            runCompressedDuringRun.add(runId)
        }
        setConversationCompressing(conversationId, true)
        val previous = compressionJob
        compressionJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                compressSubmittedHistory(conversationId)
            } finally {
                withContext(Dispatchers.Main) {
                    if (pendingManualCompress == null) {
                        setConversationCompressing(conversationId, false)
                    }
                }
            }
        }
    }

    private suspend fun compressSubmittedHistory(conversationId: String) {
        val snapshot = withContext(Dispatchers.Main) {
            conversationsById[conversationId]
        } ?: return
        val originalHistory = snapshot.history
        val fallback = runtimeConfigForBoundModel(snapshot, assistant = null)
            ?: return
        val compressModelConfig = resolveCompressModelConfig(fallback)
        val compressed = tryCompressHistory(originalHistory, compressModelConfig,
            conversationId = conversationId, contextWindow = fallback.contextWindow)
        if (compressed == originalHistory) return
        withContext(Dispatchers.Main) {
            val latest = conversationsById[conversationId] ?: return@withContext
            if (latest.history != originalHistory) return@withContext
            updateConversation(
                conversationId,
                latest.copy(
                    isCompressingContext = false,
                    isWaitingForCompression = false,
                    history = compressed,
                    livePromptTokens = null,
                    messages = AgentContextCompactionUi.applyMarker(
                        messages = latest.messages,
                        originalHistory = originalHistory,
                        compressedHistory = compressed,
                        extraKeptUserMessages = 0,
                        compressorLabel = compressorLabel(compressModelConfig),
                    ),
                ),
            )
            billedOverheadConversationId = conversationId
            billedOverheadTokens = null
            persistConversations()
            showCompressionCompletedToast(conversationId, originalHistory, compressed, compressorLabel(compressModelConfig))
        }
    }

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
    ) {
        val stoppedDuringRetry = stoppingRuns.remove(runId)
        modelRetryState.clear(runId)
        contextBudgetBlockedRuns.remove(runId)
        if (contextBudgetPrompt?.runId == runId) contextBudgetPrompt = null
        flushPendingRunDelta(runId)
        runJobs.remove(runId)
        directMediaRuns.cancel(runId)
        imageGenerationRunIds.remove(runId)
        updateRunTrace(runId) { messages -> runMessageProjector.finalizeRun(runId, messages) }
        applyConversationHistoryResult(runId, result.transcript)
        when {
            stoppedDuringRetry != null -> replaceLatestAssistantWithNotice(
                runId,
                if (stoppedDuringRetry) SystemNoticeCode.RuntimeFailed else SystemNoticeCode.Stopped,
                detail = if (stoppedDuringRetry) "已停止等待接口重试" else null,
            )
            result.ok && result.content.isNotBlank() -> completeLatestAssistantMessage(
                runId,
                fallbackContent = result.content,
            )
            result.ok -> replaceLatestAssistantWithNotice(runId, SystemNoticeCode.EmptyResult)
            result.error == LEGACY_STOPPED_ERROR || result.error == SYNTHETIC_STATUS_STOPPED ->
                replaceLatestAssistantWithNotice(runId, SystemNoticeCode.Stopped)
            else -> replaceLatestAssistantWithNotice(
                runId,
                SystemNoticeCode.RuntimeFailed,
                result.error,
            )
        }
        setConversationStreaming(runId, false)
        val conversationId = conversationIdForRun(runId)
        conversationId?.let(pendingInRunCompactConversationIds::remove)
        runMessageProjector.clearRun(runId)
        runGeneratedAtMillis.remove(runId)
        runConversationIds.remove(runId)
        runCloudUsage.remove(runId)
        runUsageResumeRounds.remove(runId)
        runOverheadTokens.remove(runId)
        runCompressedDuringRun.remove(runId)
        refreshConversationSummaries()
        persistConversations(
            onSaved = if (acknowledgeRuntimeResult) {
                {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId)
                }
            } else {
                null
            }
        )
        if (conversationId != null) {
            onConversationRunSettled(conversationId)
        }
    }

    private fun updateRunTrace(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        updateMessages(runId, transform = transform)
        refreshConversationSummaries()
    }

    private fun isStaleUsageAfterCompact(runId: String, round: Int): Boolean =
        runUsageResumeRounds[runId]?.let { round < it } ?: false

    private fun updateAssistantUsage(runId: String, round: Int, usage: TokenUsageUi) {
        if (usage.isEmpty) return
        if (isStaleUsageAfterCompact(runId, round)) return
        // 只补充 token 用量。不能触碰 isStreaming：Usage 事件紧跟在文本块结束之后，
        // 若把 isStreaming 改回 true，流式渲染会在流式/静态两种视图间反复切换，整段重渲染。
        val overhead = runOverheadTokens[runId] ?: requestOverheadTokens
        val conversationId = conversationIdForRun(runId)
        updateMessages(runId) { messages ->
            val textIndex = messages.indexOfLast { message ->
                message is AgentMessageUi &&
                    isAssistantMessageForRound(message.id, runId, round) &&
                    message.content.isNotBlank()
            }
            val holderIndex = messages.indexOfLast { message ->
                message is AgentMessageUi && isAssistantMessageForRound(message.id, runId, round)
            }
            val targetIndex = if (textIndex >= 0) textIndex else holderIndex
            if (targetIndex < 0) {
                messages + AgentMessageUi(
                    id = "${assistantMessagePrefix(runId)}$round-usage",
                    content = "",
                    isStreaming = false,
                    usage = usage,
                )
            } else {
                messages.mapIndexed { index, message ->
                    if (index == targetIndex && message is AgentMessageUi) {
                        message.copy(usage = usage)
                    } else {
                        message
                    }
                }
            }
        }
        billedOverheadConversationId = conversationId
        billedOverheadTokens = overhead
    }

    private fun bindRunConversation(runId: String, conversationId: String) {
        runConversationIds[runId] = conversationId
        runUsageResumeRounds.remove(runId)
        conversationsById[conversationId]?.let { state ->
            runCloudUsage[runId] = CloudContextUsageState(
                ContextUsageScope(conversationId, state.providerId, state.modelId, 0))
        }
    }

    private fun resetLiveUsageForRequest(runId: String) {
        if (stoppingRuns.containsKey(runId)) return
        val id = conversationIdForRun(runId) ?: return
        val state = conversationsById[id] ?: return
        val previous = runCloudUsage[runId] ?: CloudContextUsageState(
            ContextUsageScope(id, state.providerId, state.modelId, 0))
        if (previous.scope.providerId != state.providerId || previous.scope.modelId != state.modelId) return
        runCloudUsage[runId] = previous.invalidate()
        runUsageResumeRounds.remove(runId)
        if (state.livePromptTokens != null) updateConversation(id, state.copy(livePromptTokens = null), updateTimestamp = false)
    }

    private fun updateLivePromptTokens(runId: String, tokens: Int?) {
        if (stoppingRuns.containsKey(runId)) return
        val id = conversationIdForRun(runId) ?: return
        val state = conversationsById[id] ?: return
        val previous = runCloudUsage[runId] ?: return
        if (previous.scope.conversationId != id || previous.scope.providerId != state.providerId ||
            previous.scope.modelId != state.modelId) return
        val next = previous.receive(previous.scope, TokenUsageUi(inputTokens = tokens))
        runCloudUsage[runId] = next
        if (state.livePromptTokens == next.inputTokens) return
        updateConversation(id, state.copy(livePromptTokens = next.inputTokens), updateTimestamp = false)
    }

    private fun insertSupplementMessage(
        runId: String,
        index: Int,
        text: String,
        persist: Boolean = true,
        requestId: String = "",
        imagesJson: String = "[]",
    ) {
        updateMessages(runId) { messages ->
            AgentPendingResultRecovery.mergeSupplements(
                runId = runId,
                supplements = listOf(
                    AgentUiHandoffPayload.Supplement(
                        index = index,
                        text = text,
                        requestId = requestId,
                        imagesJson = imagesJson,
                        createdAt = System.currentTimeMillis(),
                    )
                ),
                messages = messages,
            )
        }
        refreshConversationSummaries()
        if (persist) persistConversations()
    }

    private fun completeLatestAssistantMessage(
        runId: String,
        fallbackContent: String,
        generatedAtMillis: Long? = runGeneratedAtMillis[runId],
    ) {
        updateMessages(runId) { messages ->
            val targetIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages)
            if (targetIndex < 0) {
                messages + AgentMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    content = fallbackContent,
                    isStreaming = false,
                    renderMarkdown = true,
                    generatedAtMillis = generatedAtMillis,
                )
            } else {
                val targetRound = (messages[targetIndex] as AgentMessageUi).id
                    .assistantRound(runId)
                val sameRoundBlocks = targetRound?.let { round ->
                    messages.count { message ->
                        message is AgentMessageUi && message.id.assistantRound(runId) == round
                    }
                } ?: 0
                messages.mapIndexed { index, message ->
                    if (index == targetIndex && message is AgentMessageUi) {
                        message.copy(
                            content = mergeCompletedAssistantContent(message.content, fallbackContent, sameRoundBlocks),
                            isStreaming = false,
                            renderMarkdown = true,
                            generatedAtMillis = message.generatedAtMillis ?: generatedAtMillis,
                        )
                    } else {
                        message
                    }
                }
            }
        }
    }

    private fun replaceLatestAssistantWithNotice(
        runId: String,
        code: SystemNoticeCode,
        detail: String? = null,
    ) {
        updateMessages(runId) { messages ->
            val existing = messages.indexOfLast {
                it is SystemNoticeMessageUi && it.code != SystemNoticeCode.ModelRetry &&
                    (it.id == "interrupted-$runId" || it.id.startsWith("assistant-$runId-"))
            }
            if (existing >= 0) return@updateMessages messages.toMutableList().also {
                it[existing] = SystemNoticeMessageUi(messages[existing].id, code, detail)
            }
            val targetIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages)
            if (targetIndex < 0) {
                messages + SystemNoticeMessageUi(AgentRunMessageProjector.resultFallbackId(runId, messages), code, detail)
            } else {
                val target = messages[targetIndex]
                // 已完成的回答不能被停止通知覆盖。重试/新一轮生成失败时，
                // 否则会把上一轮完整回复替换成「已停止」，看起来像对话消失。
                if (target is AgentMessageUi && !target.isStreaming && target.content.isNotBlank()) {
                    val noticeId = "interrupted-$runId"
                    if (messages.any { it.id == noticeId }) messages
                    else messages + SystemNoticeMessageUi(noticeId, code, detail)
                } else {
                    messages.mapIndexed { index, message ->
                        if (index == targetIndex && message is AgentMessageUi) {
                            SystemNoticeMessageUi(message.id, code, detail)
                        } else {
                            message
                        }
                    }
                }
            }
        }
    }

    private fun assistantMessagePrefix(runId: String): String =
        "assistant-$runId-"

    private fun assistantFallbackMessageId(runId: String): String =
        "${assistantMessagePrefix(runId)}1"

    private fun isAssistantMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val legacyId = "${assistantMessagePrefix(runId)}$round"
        return messageId == legacyId || messageId.startsWith("$legacyId-")
    }

    private fun String.assistantRound(runId: String): Int? =
        removePrefix(assistantMessagePrefix(runId))
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()

    private fun updateMessages(
        runId: String,
        updateTimestamp: Boolean = true,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        StreamPerformanceDiagnostics.measure("ui.messages.apply", state.messages.size.toLong()) {
            val nextMessages = transform(state.messages)
            updateConversation(
                conversationId = conversationId,
                state = state.copy(messages = nextMessages),
                updateTimestamp = updateTimestamp,
            )
        }
    }

    private fun applyConversationHistoryResult(
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        val outcome = AgentRuntimeHistoryReducer.apply(state, runId, additions)
        if (!outcome.alreadyApplied) updateConversation(conversationId, outcome.state)
    }

    private fun updateCurrentConversation(state: AgentChatHomeUiState) {
        val conversationId = selectedConversationId
        if (conversationId == null) {
            homeState = state
        } else {
            updateConversation(conversationId, state)
        }
    }

    private fun moveCurrentDraftToNewConversation() {
        val draft = homeState.copy(input = currentDraftField().text.toString())
        conversationDrafts.replace(null, draft.input)
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled).copy(
            input = draft.input,
            thinkingEnabled = draft.reasoningEffort.enablesReasoning,
            reasoningEffort = draft.reasoningEffort,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
            pendingImages = draft.pendingImages,
            pendingFileReferences = draft.pendingFileReferences,
            pendingConversationMentions = draft.pendingConversationMentions,
            providerId = currentBoundProviderId(),
            modelId = currentBoundModelId(),
            assistantId = currentBoundAssistantId(),
        )
        conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
    }

    private fun updateConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) {
        val previous = conversationsById[conversationId]
        conversationsById = conversationsById + (conversationId to state)
        if (updateTimestamp) {
            conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        }
        if (conversationId == selectedConversationId) {
            homeState = state
        }
        if (previous?.isStreaming != state.isStreaming) {
            refreshConversationSummaries()
        }
    }

    private fun setConversationWaitingForCompression(conversationId: String?, waiting: Boolean) {
        if (conversationId == null) {
            homeState = homeState.copy(isWaitingForCompression = waiting)
            return
        }
        val current = conversationsById[conversationId] ?: return
        updateConversation(conversationId, current.copy(isWaitingForCompression = waiting))
    }

    private fun setConversationCompressing(conversationId: String?, compressing: Boolean, modelName: String = "") {
        if (conversationId == null) {
            homeState = homeState.copy(isCompressingContext = compressing, isWaitingForCompression = false, compactingModelName = if (compressing) modelName else "")
            return
        }
        val current = conversationsById[conversationId] ?: return
        updateConversation(conversationId, current.copy(isCompressingContext = compressing, isWaitingForCompression = false, compactingModelName = if (compressing) modelName else ""))
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        if (state.isStreaming && !isStreaming) {
            ProviderBalanceStore.requestRefresh(scope)
        }
        updateConversation(
            conversationId,
            state.copy(
                isStreaming = isStreaming,
                childContexts = if (isStreaming) state.childContexts else state.childContexts.map {
                    it.copy(status = if (it.status in setOf("queued", "running")) "cancelled" else it.status,
                        isCompacting = false, manualCompactionState = if (it.manualCompactionState in setOf("pending", "compressing")) "ended" else it.manualCompactionState)
                },
                isWaitingForCompression = isStreaming && state.isWaitingForCompression,
                isPaused = if (isStreaming) state.isPaused else false,
                isCompressingContext = when {
                    isStreaming -> state.isCompressingContext
                    shouldKeepCompressingIndicator(conversationId) -> true
                    else -> false
                },
            ),
        )
    }

    private fun conversationIdForRun(runId: String): String? = runConversationIds[runId]

    private fun activeRunIdForSelectedConversation(): String? {
        val conversationId = selectedConversationId ?: return null
        return runConversationIds.entries.firstOrNull { it.value == conversationId }?.key
    }

    private fun conversationStateForRun(runId: String): AgentChatHomeUiState {
        val conversationId = conversationIdForRun(runId) ?: return emptyChatState(defaultThinkingEnabled)
        return conversationsById[conversationId] ?: emptyChatState(defaultThinkingEnabled)
    }

    private fun refreshConversationSummaries() {
        val summaries = conversationsById.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, AgentChatHomeUiState>> { (id, _) ->
                    id in conversationPinned
                }.thenByDescending { (id, _) ->
                    conversationUpdatedAt[id] ?: 0L
                },
            )
            .map { (id, state) ->
                val lastMessage = state.messages.lastOrNull()
                ConversationSummaryUi(
                    id = id,
                    title = conversationTitles[id].orEmpty().ifBlank {
                        appContext.getString(R.string.conversation_unnamed)
                    },
                    preview = when (lastMessage) {
                        is UserMessageUi -> AgentFileReferencePromptCodec
                            .parse(lastMessage.content)
                            .let { parsed ->
                                AgentFileReferencePolicy.titleSource(
                                    request = parsed.request,
                                    references = parsed.references,
                                )
                            }
                        is AgentMessageUi -> lastMessage.content.ifBlank {
                            appContext.getString(R.string.conversation_preview_reasoning)
                        }
                        is SystemNoticeMessageUi -> appContext.getString(
                            when (lastMessage.code) {
                                SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                                SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                                SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                                SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                                SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                            },
                        )
                        is ThinkingMessageUi -> appContext.getString(R.string.conversation_preview_reasoning)
                        is ToolActivityMessageUi -> appContext.getString(
                            R.string.conversation_preview_tool_call,
                            lastMessage.toolName,
                        )
                        else -> appContext.getString(R.string.conversation_preview_empty)
                    }.take(MAX_PREVIEW_CHARS),
                    timeLabel = if (state.isStreaming) {
                        appContext.getString(R.string.time_now)
                    } else {
                        conversationUpdatedAt[id]?.let { timestamp ->
                            ConversationTimeLabels.label(
                                timestampMillis = timestamp,
                                locale = appContext.resources.configuration.locales[0],
                                use24HourClock = DateFormat.is24HourFormat(appContext),
                                yesterdayLabel = appContext.getString(R.string.time_yesterday),
                                recentLabel = appContext.getString(R.string.time_recent),
                            )
                        } ?: appContext.getString(R.string.time_recent)
                    },
                    updatedAtMillis = conversationUpdatedAt[id] ?: 0L,
                    mode = ConversationModeUi.Chat,
                    isPinned = id in conversationPinned,
                    isActiveRun = state.isStreaming,
                    folderId = conversationFolderIds[id],
                )
            }
        val folderVisible = summaries.filterForFolder(selectedFolderId)
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            // Keep the complete folder source. The pane derives search results on
            // every edit; storing a filtered subset makes deletions unable to restore chats.
            conversations = folderVisible,
            folders = conversationFolders,
            selectedFolderId = selectedFolderId,
            historyConversations = summaries,
        )
    }

    private fun retainDeletedConversation(
        messages: List<AgentChatMessageUi>,
        timestampMillis: Long?,
    ) {
        val usage = conversationTokenUsage(messages)
        pendingRetiredUsage = ConversationTokenUsageUi(
            inputTokens = pendingRetiredUsage.inputTokens + usage.inputTokens,
            outputTokens = pendingRetiredUsage.outputTokens + usage.outputTokens,
            cachedTokens = pendingRetiredUsage.cachedTokens + usage.cachedTokens,
        )
        pendingRetiredConversations += 1
        pendingRetiredMessages += messages.count { it is UserMessageUi || it is AgentMessageUi }
        val dayMillis = timestampMillis ?: 0L
        if (dayMillis <= 0L) return
        val day = java.time.Instant.ofEpochMilli(dayMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        pendingRetiredHeatmap = pendingRetiredHeatmap + (day to ((pendingRetiredHeatmap[day] ?: 0) + 1))
    }

    private fun persistConversations(allowArchive: Boolean = false, onSaved: (() -> Unit)? = null): Deferred<Boolean> {
        if (conversationArchiveBusy && !allowArchive) {
            return kotlinx.coroutines.CompletableDeferred<Boolean>().also { deferredArchiveSaves += it to onSaved }
        }
        if (io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance) {
            return kotlinx.coroutines.CompletableDeferred(false)
        }
        val selected = selectedConversationId
        val conversations = conversationsById
        val titles = conversationTitles
        val timestamps = conversationUpdatedAt
        val folderIds = conversationFolderIds
        val pinnedIds = conversationPinned
        val folders = conversationFolders
        return synchronized(persistenceLock) {
            val retired = pendingRetiredUsage
            val retiredConversations = pendingRetiredConversations
            val retiredMessages = pendingRetiredMessages
            val retiredHeatmap = pendingRetiredHeatmap
            pendingRetiredUsage = ConversationTokenUsageUi()
            pendingRetiredConversations = 0
            pendingRetiredMessages = 0
            pendingRetiredHeatmap = emptyMap()
            val previous = persistenceJob
            scope.async(Dispatchers.IO) {
                try {
                    previous?.join()
                    conversationPersistenceMutex.withLock {
                        AgentConversationStore.save(
                            context = appContext,
                            selectedConversationId = selected,
                            conversationsById = conversations,
                            titles = titles,
                            updatedAt = timestamps,
                            folderIds = folderIds,
                            pinnedIds = pinnedIds,
                            folders = folders,
                        )
                        SettingsDataStore.addRetiredUsage(
                            inputTokens = retired.inputTokens,
                            outputTokens = retired.outputTokens,
                            cachedTokens = retired.cachedTokens,
                            conversations = retiredConversations,
                            messages = retiredMessages,
                            heatmap = retiredHeatmap,
                        )
                    }
                    onSaved?.invoke()
                    lastConversationPersistenceError = null
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    withContext(Dispatchers.Main) {
                        pendingRetiredUsage = ConversationTokenUsageUi(
                            inputTokens = pendingRetiredUsage.inputTokens + retired.inputTokens,
                            outputTokens = pendingRetiredUsage.outputTokens + retired.outputTokens,
                            cachedTokens = pendingRetiredUsage.cachedTokens + retired.cachedTokens,
                        )
                        pendingRetiredConversations += retiredConversations
                        pendingRetiredMessages += retiredMessages
                        retiredHeatmap.forEach { (day, count) ->
                            pendingRetiredHeatmap =
                                pendingRetiredHeatmap + (day to ((pendingRetiredHeatmap[day] ?: 0) + count))
                        }
                    }
                    AndroidAgentLogger.error(
                        "Agent conversation persistence failed: type=${throwable.safeLogType()} message=${throwable.message}"
                    )
                    lastConversationPersistenceError = throwable.message
                    false
                }
            }.also { persistenceJob = it }
        }
    }

    private fun currentBoundProviderId(): String =
        modelPickerState.selectedModel?.providerId.orEmpty()

    private fun currentBoundModelId(): String =
        modelPickerState.selectedModel?.id.orEmpty()

    private fun currentBoundAssistantId(): String =
        AssistantRepository.active().id

    private fun resolvedAssistantId(state: AgentChatHomeUiState): String {
        val stored = state.assistantId
        if (stored.isNotBlank() && AssistantRepository.profile(stored) != null) return stored
        return currentBoundAssistantId()
    }

    private fun applyConversationAssistant(assistantId: String, persist: Boolean) {
        if (homeState.assistantId != assistantId) {
            updateCurrentConversation(homeState.copy(assistantId = assistantId))
            if (persist) persistConversations()
        }
        if (AssistantRepository.activeId.value == assistantId) {
            refreshMemory()
            refreshRequestOverhead()
            return
        }
        scope.launch(Dispatchers.IO) {
            AssistantRepository.select(assistantId)
            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            withContext(Dispatchers.Main) {
                if (resolvedAssistantId(homeState) != assistantId) return@withContext
                refreshMemory()
                refreshRequestOverhead()
            }
        }
    }

    // Copy the active conversation once; later edits remain local to each conversation.
    private fun newDraftChatState(): AgentChatHomeUiState =
        emptyChatState(false)
            .copy(
                providerId = currentBoundProviderId(),
                modelId = currentBoundModelId(),
                assistantId = currentBoundAssistantId(),
                reasoningEffort = homeState.reasoningEffort,
                thinkingEnabled = homeState.reasoningEffort.enablesReasoning,
                availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
            )

    private fun restoreConversationRuntimeModel() {
        modelBindingGeneration++
        refreshBoundModelPicker()
        val assistantId = resolvedAssistantId(homeState)
        applyConversationAssistant(assistantId, persist = homeState.assistantId != assistantId)
    }

    private suspend fun runtimeConfigForBoundModel(
        state: AgentChatHomeUiState,
        assistant: io.github.mangi.eta.data.model.AssistantProfile? = null,
    ): AgentModelClient.ModelConfig? {
        if (state.providerId.isBlank() || state.modelId.isBlank()) return null
        return RuntimeConfigRepository.configForProviderAndModel(state.providerId, state.modelId, assistant)
    }

    private companion object {
        const val MAX_TITLE_CHARS = 24
        const val MAX_PREVIEW_CHARS = 48
        const val LEGACY_STOPPED_ERROR = "已停止"
        const val SYNTHETIC_STATUS_STOPPED = "eta_status:stopped"
        // 数据状态以较粗粒度发布，文字显现由独立的帧时钟连续推进。
        // 这与 Kimi 将流式数据和视觉动画分层的做法一致。
        const val STREAM_UI_UPDATE_INTERVAL_MS = 150L

        fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
            AgentChatHomeUiState(
                messages = emptyList(),
                history = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = thinkingEnabled,
            )

        fun newConversationId(): String = "conv-${UUID.randomUUID()}"
    }

    private fun assignPendingFolder(conversationId: String) {
        val folderId = pendingNewConversationFolderId
            ?.takeIf { id -> conversationFolders.any { it.id == id } }
            ?: return
        conversationFolderIds = conversationFolderIds + (conversationId to folderId)
    }

    fun updateAutoCompressEnabled(enabled: Boolean) {
        autoCompressEnabled = enabled
        Prefs.putBoolean(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED, enabled)
    }

    fun selectContextTask(taskId: String?) {
        if (taskId != null && homeState.childContexts.none { it.taskId == taskId }) return
        val id = selectedConversationId
        if (id == null) homeState = homeState.copy(selectedContextTaskId = taskId)
        else conversationsById[id]?.let { updateConversation(id, it.copy(selectedContextTaskId = taskId), updateTimestamp = false) }
    }

    /** Capture the selected task at click time; later selection changes cannot retarget this request. */
    fun compressCurrentConversation(
        providerId: String?,
        modelId: String?,
        onFinished: (Boolean) -> Unit,
    ) {
        if (rejectConversationArchiveMutation()) {
            onFinished(false)
            return
        }
        val targetId = homeState.selectedContextTaskId
        if (targetId != null) {
            val child = homeState.childContexts.firstOrNull { it.taskId == targetId }
            val runId = homeState.childContextRunId
            if (child == null || child.status != "running" || child.role in setOf("image_generation", "video_generation") || runId.isBlank()) {
                Toast.makeText(appContext, "该子任务已结束、尚未执行或不支持对话压缩；不会改为压缩主代理。", Toast.LENGTH_LONG).show()
                onFinished(false)
                return
            }
            onFinished(true)
            scope.launch(Dispatchers.IO) {
                val sent = runCatching {
                    val config = if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) null else
                        resolveCompressModelConfig(null, providerId, modelId, manual = true)
                    AgentRuntimeClient(appContext, AndroidAgentLogger).compactRun(runId, keepRecentFor(), config, targetId)
                }.getOrDefault(false)
                if (!sent) withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "未确认子任务接受压缩请求，它可能已结束；不会改为压缩其它代理。", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        persistCompressPreferences(providerId, modelId)
        val runInFlight = homeState.isStreaming || homeState.isPaused
        if (compressionJob?.isActive == true) {
            onFinished(true)
            return
        }
        if (homeState.isCompressingContext || homeState.isWaitingForCompression) {
            onFinished(true)
            return
        }
        val keepRecentMessages = keepRecentFor()
        if (!runInFlight &&
            io.github.mangi.eta.agent.model.AgentCompressionBoundary.selectStart(homeState.history, compressionContextWindow() ?: 0) <= 0
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.compress_conversation_nothing_to_compress),
                Toast.LENGTH_SHORT,
            ).show()
            onFinished(false)
            return
        }
        val conversationId = selectedConversationId
        onFinished(true)
        if (runInFlight) {
            requestInRunCompress(
                conversationId,
                keepRecentMessages,
                providerId,
                modelId,
            )
            return
        }
        setConversationWaitingForCompression(conversationId, true)
        pendingManualCompress = PendingManualCompress(
            conversationId = conversationId,
            providerId = providerId,
            modelId = modelId,
            keepRecent = keepRecentMessages,

            resumeAfter = false,
        )
        startPendingManualCompress()
    }


    private fun requestInRunCompress(
        conversationId: String?,
        keepRecent: Int,
        providerId: String? = null,
        modelId: String? = null,
    ) {
        val runId = runIdForConversation(conversationId) ?: run {
            Toast.makeText(appContext, "当前任务已结束，请重新发起压缩。", Toast.LENGTH_LONG).show()
            return
        }
        if (conversationId != null && !pendingInRunCompactConversationIds.add(conversationId)) return
        // Show queued maintenance alongside live output until Runtime reaches a safe boundary.
        setConversationWaitingForCompression(conversationId, true)
        val snapshot = conversationsById[conversationId] ?: homeState
        scope.launch(Dispatchers.IO) {
            val sent = try {
                val model = resolveCompressModelConfig(
                    fallback = runtimeConfigForBoundModel(snapshot, assistant = null),
                    providerId = providerId,
                    modelId = modelId,
                    manual = true,
                )
                model != null && AgentRuntimeClient(appContext, AndroidAgentLogger).compactRun(
                    runId = runId,
                    keepRecent = keepRecent,
                    compressModelConfig = model,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                false
            }
            if (!sent) withContext(Dispatchers.Main) {
                conversationId?.let(pendingInRunCompactConversationIds::remove)
                setConversationWaitingForCompression(conversationId, false)
                Toast.makeText(appContext, "压缩请求未送达 Runtime，当前输出未中断。", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runIdForConversation(conversationId: String?): String? {
        if (conversationId == null) return activeRunIdForSelectedConversation()
        return runConversationIds.entries.firstOrNull { it.value == conversationId }?.key
    }

    private fun onConversationRunSettled(conversationId: String) {
        val pending = pendingManualCompress
        if (pending != null && (pending.conversationId == null || pending.conversationId == conversationId)) {
            startPendingManualCompress()
        } else {
            scheduleAutoCompress(conversationId, allowRepeat = true)
        }
    }

    private fun isCompressionBlockingSend(): Boolean {
        if (compressionJob?.isActive == true || homeState.isCompressingContext) return true
        val pending = pendingManualCompress ?: return false
        return pending.conversationId == null || pending.conversationId == selectedConversationId
    }

    private fun rejectSendIfCompressing(): Boolean {
        if (!isCompressionBlockingSend()) return false
        Toast.makeText(
            appContext,
            appContext.getString(R.string.compress_conversation_in_progress),
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    private fun shouldKeepCompressingIndicator(conversationId: String?): Boolean {
        if (compressionJob?.isActive == true) return true
        val pending = pendingManualCompress ?: return false
        return conversationId == null ||
            pending.conversationId == null ||
            pending.conversationId == conversationId
    }

    private fun startPendingManualCompress() {
        val request = pendingManualCompress ?: return
        if (compressionJob?.isActive == true) return
        pendingManualCompress = null
        val resumeAfter = request.resumeAfter
        val previous = compressionJob
        compressionJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                val snapshot = withContext(Dispatchers.Main) {
                    request.conversationId?.let { conversationsById[it] }
                        ?: homeState.takeIf { selectedConversationId == request.conversationId }
                } ?: return@launch
                if (snapshot.isStreaming || snapshot.isPaused) {
                    withContext(Dispatchers.Main) {
                        pendingManualCompress = request
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    setConversationCompressing(request.conversationId, true)
                }
                val originalHistory = snapshot.history
                val fallback = runtimeConfigForBoundModel(snapshot, assistant = null)
                    ?: if (request.providerId.isNullOrBlank() || request.modelId.isNullOrBlank()) null else
                        RuntimeConfigRepository.configForProviderAndModel(request.providerId, request.modelId, assistant = null)
                val modelConfig = resolveCompressModelConfig(
                    fallback = fallback,
                    providerId = request.providerId,
                    modelId = request.modelId,
                    manual = true,
                )
                if (modelConfig == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.compress_conversation_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                if (io.github.mangi.eta.agent.model.AgentCompressionBoundary.selectStart(originalHistory,
                        fallback?.contextWindow ?: 0) <= 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, appContext.getString(
                            R.string.compress_conversation_nothing_to_compress), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val compressed = tryCompressHistory(
                    history = originalHistory,
                    compressModelConfig = modelConfig,
                    keepRecent = request.keepRecent,
                    conversationId = request.conversationId,
                    contextWindow = fallback?.contextWindow,
                )
                withContext(Dispatchers.Main) {
                    if (compressed == originalHistory) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.compress_conversation_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@withContext
                    }
                    applyManualCompressedHistory(
                        request.conversationId,
                        originalHistory,
                        compressed,
                        compressorLabel(modelConfig),
                    )
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (pendingManualCompress == null) {
                        if (resumeAfter) {
                            resumeLastTurnAfterCompress(request.conversationId)
                        }
                        val resumed = request.conversationId?.let { conversationsById[it]?.isStreaming } == true
                        if (!resumed) {
                            setConversationCompressing(request.conversationId, false)
                        }
                    }
                }
            }
        }
    }

    private fun resumeLastTurnAfterCompress(conversationId: String?) {
        if (conversationId != selectedConversationId) return
        if (homeState.isStreaming || homeState.isPaused) return
        if (homeState.hasPartialAssistantAfterLastUser()) {
            continuePartialTurnAfterCompress(conversationId)
            return
        }
        val lastUser = homeState.messages.lastOrNull { message ->
            message is UserMessageUi && !message.isSteerSupplement()
        } as? UserMessageUi ?: return
        regenerateMessage(lastUser.id, ignoreCompression = true)
    }

    private fun continuePartialTurnAfterCompress(conversationId: String?) {
        val id = conversationId ?: return
        val state = conversationsById[id] ?: return
        val partial = state.messages.lastOrNull { message ->
            message is AgentMessageUi && message.content.isNotBlank()
        } as? AgentMessageUi ?: return
        val continuedHistory = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(
            AgentConversationRevisionReducer.historyWithTrailingPartial(state.history, partial))
        val prompt = RESUME_AFTER_COMPRESS_PROMPT
        val runId = "run-${UUID.randomUUID()}"
        val userMessage = UserMessageUi(
            id = "user-$runId-supplement-resume",
            content = prompt,
        )
        launchConversationRun(
            conversationId = id,
            runId = runId,
            prompt = prompt,
            images = emptyList(),
            history = continuedHistory,
            userHistoryMessage = AgentModelClient.ConversationMessage(
                role = "user",
                content = prompt,
            ),
            messages = state.messages + userMessage,
            state = state,
            reasoningEffort = state.reasoningEffort,
            skipAutoCompress = true,
            logicalTurnId = continuedHistory.lastOrNull { it.turnId.isNotBlank() }?.turnId ?: runId,
        )
    }

    private fun freezeStreamingMessages(
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> = messages.map { message ->
        when (message) {
            is AgentMessageUi -> if (message.isStreaming) message.copy(isStreaming = false) else message
            is ThinkingMessageUi -> if (message.isStreaming) message.copy(isStreaming = false) else message
            else -> message
        }
    }

    private fun persistCompressPreferences(
        providerId: String?,
        modelId: String?,
    ) {
        if (!providerId.isNullOrBlank() && !modelId.isNullOrBlank()) {
            Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_PROVIDER_ID, providerId)
            Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_ID, modelId)
        }
    }

    private fun applyManualCompressedHistory(
        conversationId: String?,
        originalHistory: List<AgentModelClient.ConversationMessage>,
        compressedHistory: List<AgentModelClient.ConversationMessage>,
        compressorLabel: String = "",
    ) {
        if (conversationId != null) {
            val current = conversationsById[conversationId] ?: return
            if (current.isStreaming || current.isPaused) return
            if (current.history != originalHistory) return
            updateConversation(
                conversationId,
                current.copy(
                    history = compressedHistory,
                    livePromptTokens = null,
                    messages = AgentContextCompactionUi.applyMarker(
                        messages = current.messages,
                        originalHistory = originalHistory,
                        compressedHistory = compressedHistory,
                        compressorLabel = compressorLabel,
                    ),
                ),
            )
        } else if (
            selectedConversationId == null &&
            !homeState.isStreaming &&
            !homeState.isPaused &&
            homeState.history == originalHistory
        ) {
            homeState = homeState.copy(
                history = compressedHistory,
                livePromptTokens = null,
                messages = AgentContextCompactionUi.applyMarker(
                    messages = homeState.messages,
                    originalHistory = originalHistory,
                    compressedHistory = compressedHistory,
                    compressorLabel = compressorLabel,
                ),
            )
        } else {
            return // No accepted update: do not persist or announce a stale result.
        }
        billedOverheadConversationId = conversationId
        billedOverheadTokens = null
        persistConversations()
        showCompressionCompletedToast(conversationId, originalHistory, compressedHistory, compressorLabel)
    }
}

private val RESUME_AFTER_COMPRESS_PROMPT =
    AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT

private data class PendingManualCompress(
    val conversationId: String?,
    val providerId: String?,
    val modelId: String?,
    val keepRecent: Int,

    val resumeAfter: Boolean = false,
)

internal data class MessageRevisionImpact(
    val laterTurnCount: Int,
)

private const val EXTERNAL_ARCHIVE_CONVERSATION_PREFIX = "archive-"

private fun String.isReadOnlyExternalArchiveConversation(): Boolean =
    startsWith(EXTERNAL_ARCHIVE_CONVERSATION_PREFIX)

private fun archiveConversationId(source: String, conversationKey: String): String {
    val prefix = if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) {
        ASSISTANT_CONVERSATION_PREFIX
    } else {
        EXTERNAL_ARCHIVE_CONVERSATION_PREFIX
    }
    return prefix + stableArchiveId("$source:$conversationKey")
}

private const val ASSISTANT_CONVERSATION_PREFIX = "assistant-"

private fun stableArchiveId(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun buildToolsState(context: Context): AgentToolsUiState =
    AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = context.getString(R.string.state_screens_and_controls_3f095b),
                tools = listOf(
                    ToolItemUi("observe_screen", context.getString(R.string.tool_ui_watch_the_screen_e70f2a), context.getString(R.string.tool_ui_read_the_current_node_and_attach_the_original_im_df1fec)),
                    ToolItemUi("tap_element", context.getString(R.string.tool_ui_click_element_7a3d91), context.getString(R.string.tool_ui_click_on_the_most_recently_observed_node_b4cf5a)),
                    ToolItemUi("tap_area", context.getString(R.string.tool_ui_click_area_cbaa08), context.getString(R.string.tool_ui_click_by_coordinate_area_2ad961)),
                    ToolItemUi("long_press", context.getString(R.string.tool_ui_long_press_f7a417), context.getString(R.string.tool_ui_long_press_on_coordinates_or_elements_796384)),
                    ToolItemUi("swipe", context.getString(R.string.tool_ui_slide_3723aa), context.getString(R.string.tool_ui_perform_up_down_left_and_right_swipe_gestures_3ef0de)),
                    ToolItemUi("scroll", context.getString(R.string.tool_ui_scroll_220e68), context.getString(R.string.tool_ui_scroll_the_page_or_specify_a_node_83ab24)),
                ),
            ),
            ToolGroupUi(
                id = "text",
                title = context.getString(R.string.state_text_and_clipboard_3a7340),
                tools = listOf(
                    ToolItemUi("input_text", context.getString(R.string.tool_ui_enter_text_ae47ab), context.getString(R.string.tool_ui_append_or_paste_text_to_the_current_focus_1efdcc)),
                    ToolItemUi("replace_text", context.getString(R.string.tool_ui_replacement_text_1a5c8d), context.getString(R.string.tool_ui_replace_text_in_focus_or_node_30d332)),
                    ToolItemUi("clear_text", context.getString(R.string.tool_ui_clear_text_d4cb57), context.getString(R.string.tool_ui_clear_focus_or_node_text_3e754a)),
                    ToolItemUi("paste_text", context.getString(R.string.tool_ui_paste_text_791b85), context.getString(R.string.tool_ui_reliably_enter_long_text_with_the_clipboard_b6041e)),
                    ToolItemUi("wait_for_text", context.getString(R.string.tool_ui_wait_for_text_9e9a54), context.getString(R.string.tool_ui_wait_for_the_specified_text_to_appear_on_the_scr_43f9b0)),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = context.getString(R.string.state_web_browsing_e56105),
                tools = listOf(
                    ToolItemUi("browser_use", context.getString(R.string.tool_ui_agent_browser_a66bd5), context.getString(R.string.tool_ui_open_web_pages_off_screen_and_keep_a_takeover_br_72972e)),
                    ToolItemUi("browser_read", context.getString(R.string.tool_ui_read_web_pages_4f0bb9), context.getString(R.string.tool_ui_extract_rendered_text_lists_and_links_8bdcdd)),
                    ToolItemUi("browser_interact", context.getString(R.string.tool_ui_web_page_interaction_331b3f), context.getString(R.string.tool_ui_find_click_and_enter_page_elements_8f102d)),
                    ToolItemUi("browser_screenshot", context.getString(R.string.tool_ui_page_screenshot_c823a2), context.getString(R.string.tool_ui_give_the_current_web_page_viewport_to_the_visual_f62274)),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = context.getString(R.string.state_applications_and_systems_9624e6),
                tools = listOf(
                    ToolItemUi("search_apps", context.getString(R.string.tool_ui_search_apps_897fdf), context.getString(R.string.tool_ui_query_installed_applications_by_name_or_package__32b004)),
                    ToolItemUi("get_current_context", context.getString(R.string.tool_ui_time_and_location_693893), context.getString(R.string.tool_ui_read_system_time_and_recent_location_b9f4ae)),
                    ToolItemUi("launch_app", context.getString(R.string.tool_ui_open_app_7c65e7), context.getString(R.string.tool_ui_start_the_specified_package_name_or_application__beabff)),
                    ToolItemUi("open_uri", context.getString(R.string.tool_ui_open_with_app_32c24e), context.getString(R.string.tool_ui_explicitly_hand_over_links_or_deep_links_to_exte_35ff26)),
                    ToolItemUi("press_key", context.getString(R.string.tool_ui_button_02eafa), context.getString(R.string.tool_ui_system_buttons_such_as_return_homepage_recent_ta_1b4cf0)),
                    ToolItemUi("open_system_panel", context.getString(R.string.tool_ui_system_panel_b0f7a3), context.getString(R.string.tool_ui_open_the_notification_bar_quick_settings_and_oth_5e51cf)),
                ),
            ),
            ToolGroupUi(
                id = "device_direct",
                title = context.getString(R.string.state_direct_access_to_equipment_eda92c),
                tools = listOf(
                    ToolItemUi("set_alarm", context.getString(R.string.tool_ui_set_alarm_25ca3c), context.getString(R.string.tool_ui_create_a_system_alarm_directly_and_open_the_cloc_9aa214)),
                    ToolItemUi("set_timer", context.getString(R.string.tool_ui_set_timer_aee60c), context.getString(R.string.tool_ui_directly_create_system_timers_up_to_24_hours_87c476)),
                    ToolItemUi("device_status", context.getString(R.string.tool_ui_device_status_567a4c), context.getString(R.string.tool_ui_read_power_memory_storage_and_system_version_c501d5)),
                    ToolItemUi("network_info", context.getString(R.string.tool_ui_network_status_6bd556), context.getString(R.string.tool_ui_read_networking_method_and_current_wi_fi_status_68016a)),
                    ToolItemUi("media_control", context.getString(R.string.tool_ui_media_control_585edc), context.getString(R.string.tool_ui_play_pause_and_switch_songs_without_operating_th_311cb8)),
                    ToolItemUi("set_volume", context.getString(R.string.tool_ui_set_volume_85a691), context.getString(R.string.tool_ui_set_by_media_alarm_clock_ringtone_and_other_chan_3fcc3e)),
                    ToolItemUi("text_to_speech", context.getString(R.string.tool_ui_text_to_speech), context.getString(R.string.tool_ui_text_to_speech_summary)),
                    ToolItemUi("top_memory_apps", context.getString(R.string.tool_ui_memory_ranking_408ca1), context.getString(R.string.tool_ui_view_the_currently_most_occupied_processes_8646c4)),
                    ToolItemUi("top_storage_apps", context.getString(R.string.tool_ui_storage_ranking_86a16c), context.getString(R.string.tool_ui_check_application_data_and_cache_usage_837e9f)),
                ),
            ),
            ToolGroupUi(
                id = "device_sensitive",
                title = context.getString(R.string.state_sensitive_equipment_capabilities_fbdc4b),
                tools = listOf(
                    ToolItemUi("read_sms_code", context.getString(R.string.tool_ui_read_verification_code_7d1121), context.getString(R.string.tool_ui_only_extract_verification_codes_from_recent_sms__0fb8c1)),
                    ToolItemUi("recent_notifications", context.getString(R.string.tool_ui_read_notification_7fdc09), context.getString(R.string.tool_ui_read_the_current_notification_title_and_text_0faee7)),
                    ToolItemUi("search_notification_history", context.getString(R.string.tool_ui_notification_history_95d015), context.getString(R.string.tool_ui_retrieve_the_last_7_days_of_notifications_saved__643e43)),
                    ToolItemUi("recent_app_activity", context.getString(R.string.tool_ui_recently_applied_08f74c), context.getString(R.string.tool_ui_view_recently_opened_apps_and_times_bf9d50)),
                    ToolItemUi("app_usage_summary", context.getString(R.string.tool_ui_app_usage_statistics_ee20d3), context.getString(R.string.tool_ui_summarize_recent_app_usage_by_foreground_duratio_b346c8)),
                    ToolItemUi("get_current_location", context.getString(R.string.tool_ui_current_location_b458ea), context.getString(R.string.tool_ui_read_the_closest_location_the_system_already_has_255a6c)),
                    ToolItemUi("get_device_environment", context.getString(R.string.tool_ui_equipment_environment_1026ec), context.getString(R.string.tool_ui_read_lock_screen_do_not_disturb_audio_output_and_9260b8)),
                    ToolItemUi("list_alarms", context.getString(R.string.tool_ui_alarm_clock_schedule_acae32), context.getString(R.string.tool_ui_read_the_alarm_clock_that_has_been_created_in_th_2320d6)),
                    ToolItemUi("list_active_timers", context.getString(R.string.tool_ui_activity_timer_36f107), context.getString(R.string.tool_ui_read_running_or_paused_timers_3437c8)),
                    ToolItemUi("search_clipboard_history", context.getString(R.string.tool_ui_clipboard_history_b377bb), context.getString(R.string.tool_ui_retrieve_clipboard_contents_saved_by_system_inpu_1dc9db)),
                    ToolItemUi("get_health_summary", context.getString(R.string.tool_ui_health_summary_951c0b), context.getString(R.string.tool_ui_summarize_steps_sleep_exercise_and_body_metrics_6ff66f)),
                    ToolItemUi("wifi_credentials", context.getString(R.string.tool_ui_wi_fi_password_80e9a4), context.getString(R.string.tool_ui_read_the_network_credentials_saved_by_the_phone_96d43a)),
                    ToolItemUi("get_setting", context.getString(R.string.tool_ui_read_system_settings_d455ce), context.getString(R.string.tool_ui_read_the_specified_settings_key_496975)),
                    ToolItemUi("set_setting", context.getString(R.string.tool_ui_modify_system_settings_ae1f4c), context.getString(R.string.tool_ui_modify_android_settings_keys_91a37e)),
                    ToolItemUi("set_device_state", context.getString(R.string.tool_ui_network_switch_834347), context.getString(R.string.tool_ui_directly_control_wi_fi_or_bluetooth_4fa0b9)),
                    ToolItemUi("app_state_control", context.getString(R.string.tool_ui_application_status_930ff0), context.getString(R.string.tool_ui_stop_freeze_or_unfreeze_apps_a27438)),
                    ToolItemUi("get_logcat", context.getString(R.string.tool_ui_system_log_096733), context.getString(R.string.tool_ui_bounded_reading_and_filtering_of_recent_logs_0a268a)),
                ),
            ),
            ToolGroupUi(
                id = "personal_data",
                title = context.getString(R.string.state_direct_access_to_personal_data_387d7b),
                tools = listOf(
                    ToolItemUi("search_media", context.getString(R.string.tool_ui_album_pictures_23bcc2), context.getString(R.string.tool_ui_retrieve_pictures_by_file_name_or_album_path_c08236)),
                    ToolItemUi("search_audio", context.getString(R.string.tool_ui_audio_file_1ccf2e), context.getString(R.string.tool_ui_search_audio_by_title_filename_or_author_82e20d)),
                    ToolItemUi("search_recordings", context.getString(R.string.tool_ui_system_recording_15eb19), context.getString(R.string.tool_ui_retrieve_recording_files_from_system_media_libra_314c4d)),
                    ToolItemUi("search_files", context.getString(R.string.tool_ui_share_files_a3b376), context.getString(R.string.tool_ui_retrieve_documents_and_files_from_shared_storage_7d6193)),
                    ToolItemUi("search_calendar_events", context.getString(R.string.tool_ui_calendar_events_970349), context.getString(R.string.tool_ui_search_events_by_title_location_or_description_1afd77)),
                    ToolItemUi("search_contacts", context.getString(R.string.tool_ui_address_book_9070cb), context.getString(R.string.tool_ui_retrieve_contact_name_and_open_address_6dacc5)),
                    ToolItemUi("search_call_history", context.getString(R.string.tool_ui_call_history_88e57b), context.getString(R.string.tool_ui_retrieve_calls_by_number_or_contact_name_2ce431)),
                    ToolItemUi("search_messages", context.getString(R.string.tool_ui_short_message_17e1a4), context.getString(R.string.tool_ui_search_text_messages_by_sender_or_text_keywords_e14363)),
                    ToolItemUi("search_downloads", context.getString(R.string.tool_ui_download_history_8494d7), context.getString(R.string.tool_ui_retrieve_system_download_tasks_and_files_3301b9)),
                    ToolItemUi("search_coloros_notes", context.getString(R.string.tool_ui_coloros_notes_6c324c), context.getString(R.string.tool_ui_retrieve_notes_to_dos_and_text_content_e806d7)),
                    ToolItemUi("search_coloros_recordings", context.getString(R.string.tool_ui_coloros_recording_a4e425), context.getString(R.string.tool_ui_retrieve_normal_recordings_and_call_recordings_55c192)),
                    ToolItemUi("search_recording_summaries", context.getString(R.string.tool_ui_recording_summary_2fe550), context.getString(R.string.tool_ui_retrieve_transcribed_summaries_and_notes_associa_9cb00f)),
                    ToolItemUi("search_coloros_memories", context.getString(R.string.tool_ui_coloros_system_memory_eff961), context.getString(R.string.tool_ui_retrieve_collected_information_and_its_structure_9c1c71)),
                    ToolItemUi("search_saved_places", context.getString(R.string.tool_ui_save_location_c29782), context.getString(R.string.tool_ui_retrieve_location_information_from_system_memory_52ea48)),
                    ToolItemUi("search_personal_orders", context.getString(R.string.tool_ui_personal_order_25e4c9), context.getString(R.string.tool_ui_retrieve_takeout_shopping_express_delivery_ticke_f8d002)),
                    ToolItemUi("search_qq_chat_images", context.getString(R.string.tool_ui_qq_chat_pictures_e21bf9), context.getString(R.string.tool_ui_retrieve_recent_pictures_in_qq_chat_picture_cach_b8f009)),
                    ToolItemUi("search_wechat_chat_images", context.getString(R.string.tool_ui_wechat_chat_pictures_72b268), context.getString(R.string.tool_ui_retrieve_recent_pictures_in_wechat_chat_picture__ab66f7)),
                ),
            ),
            ToolGroupUi(
                id = "file_vision",
                title = context.getString(R.string.state_document_vision_6a65a7),
                tools = listOf(
                    ToolItemUi("read_image", context.getString(R.string.tool_ui_read_pictures_ae993b), context.getString(R.string.tool_ui_read_pictures_of_known_paths_and_hand_them_over__7f9569)),
                ),
            ),
            ToolGroupUi(
                id = "memory",
                title = context.getString(R.string.state_memory_b55ff5),
                tools = listOf(
                    ToolItemUi("memory_get", context.getString(R.string.tool_ui_read_memory_979135), context.getString(R.string.tool_ui_paged_to_read_or_retrieve_long_term_memory_in_me_88afc4)),
                    ToolItemUi("memory_write", context.getString(R.string.tool_ui_organize_memory_2b08eb), context.getString(R.string.tool_ui_partially_update_append_or_clear_long_term_memor_c1bab6)),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = context.getString(R.string.state_terminal_and_files_ae7c54),
                tools = listOf(
                    ToolItemUi("terminal", context.getString(R.string.tool_ui_session_terminal_09c6e6), context.getString(R.string.tool_ui_user_root_shell_conversational_execution_and_asy_13c2ab)),
                    ToolItemUi("run_command", context.getString(R.string.tool_ui_execute_command_bf1627), context.getString(R.string.tool_ui_directly_execute_a_single_shell_command_c40cef)),
                    ToolItemUi("read_file", context.getString(R.string.tool_ui_read_file_dc995c), context.getString(R.string.tool_ui_read_the_contents_of_mobile_phone_files_bf3066)),
                    ToolItemUi("write_file", context.getString(R.string.tool_ui_write_file_e620fd), context.getString(R.string.tool_ui_write_or_overwrite_mobile_files_29fae4)),
                    ToolItemUi("list_directory", context.getString(R.string.tool_ui_list_directory_96e765), context.getString(R.string.tool_ui_list_directory_contents_feff30)),
                ),
            ),
        )
    )

private fun buildPermissionHealthState(context: Context): PermissionHealthUiState {
    val backgroundRunningEnabled = isIgnoringBatteryOptimizations(context)
    val overlayEnabled = Settings.canDrawOverlays(context)
    val appListEnabled = hasAppListAccess(context)
    val accessibilityEnabled = isAgentAccessibilityEnabled(context) || AgentAccessibilityService.isAvailable()
    val rootEnabled = RootAccess.isGranted
    val notificationsEnabled = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    val locationAccess = DeviceLocationProvider.accessState(context)
    val notificationHistoryEnabled = io.github.mangi.eta.agent.device.AgentNotificationHistoryService.isEnabled(context)
    val usageAccessEnabled = io.github.mangi.eta.agent.tool.AgentPersonalContextTools.hasUsageAccess(context)

    return PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "background",
                title = context.getString(R.string.state_background_running_permission_dde21b),
                summary = "",
                status = if (backgroundRunningEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (backgroundRunningEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = context.getString(R.string.state_floating_window_permissions_076b77),
                summary = "",
                status = if (overlayEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (overlayEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "app_list",
                title = context.getString(R.string.state_application_list_reading_135f16),
                summary = "",
                status = if (appListEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (appListEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "location",
                title = context.getString(R.string.state_location_permissions_b53f9c),
                summary = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> context.getString(R.string.state_ui_used_to_understand_the_location_of_mobile_phones_af52e9)
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> context.getString(R.string.capability_location_foreground)
                    DeviceLocationProvider.AccessState.DISABLED -> context.getString(R.string.state_ui_system_location_service_is_turned_off_3902e7)
                    DeviceLocationProvider.AccessState.AVAILABLE -> context.getString(R.string.state_ui_only_read_when_the_agent_calls_the_tool_8cf77b)
                },
                status = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> PermissionStatusUi.Missing
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> PermissionStatusUi.Warning
                    DeviceLocationProvider.AccessState.DISABLED -> PermissionStatusUi.Disabled
                    DeviceLocationProvider.AccessState.AVAILABLE -> PermissionStatusUi.Available
                },
                primaryActionLabel = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> context.getString(R.string.state_ui_to_authorize_762ec4)
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> context.getString(R.string.state_ui_go_to_settings_1f2998)
                    DeviceLocationProvider.AccessState.DISABLED -> context.getString(R.string.state_ui_to_open_13ec17)
                    DeviceLocationProvider.AccessState.AVAILABLE -> null
                },
            ),
            PermissionHealthItemUi(
                id = "notification_history",
                title = context.getString(R.string.state_notice_of_use_rights_1ae29a),
                summary = if (notificationHistoryEnabled) {
                    context.getString(R.string.state_ui_natively_bounded_storage_of_last_7_days_of_notif_ca7f01)
                } else {
                    context.getString(R.string.state_ui_start_logging_searchable_notification_history_af_b36af6)
                },
                status = if (notificationHistoryEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (notificationHistoryEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "usage_access",
                title = context.getString(R.string.state_usage_access_20f1f8),
                summary = context.getString(R.string.state_used_to_read_recently_opened_applications_and_foregr_73e796),
                status = if (usageAccessEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (usageAccessEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "accessibility",
                title = context.getString(R.string.state_accessibility_permissions_f80103),
                summary = "",
                status = if (accessibilityEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (accessibilityEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "notifications",
                title = context.getString(R.string.capability_notifications_title),
                summary = context.getString(R.string.capability_notifications_summary),
                status = if (notificationsEnabled) PermissionStatusUi.Available else PermissionStatusUi.Disabled,
                primaryActionLabel = context.getString(R.string.state_ui_go_to_settings_1f2998),
            ),
            PermissionHealthItemUi(
                id = "root",
                title = context.getString(R.string.capability_enhancements),
                summary = context.getString(R.string.capability_optional_root),
                status = if (rootEnabled) PermissionStatusUi.Available else PermissionStatusUi.Disabled,
                primaryActionLabel = if (rootEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
        )
    )
}

private fun agentBooleanForUi(key: String): Boolean {
    return Prefs.isEnabled(key)
}

private fun AgentTokenUsage.toUi(): TokenUsageUi =
    TokenUsageUi(
        contextTokens = contextTokens,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedTokens,
    )

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java,
    ).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

private fun hasAppListAccess(context: Context): Boolean {
    return try {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        packages.size > 10
    } catch (e: Exception) {
        false
    }
}
