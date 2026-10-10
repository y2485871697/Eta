package io.github.mangi.eta.ui.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.IntentFilter
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.runtime.AgentChildTaskGroups
import io.github.mangi.eta.agent.runtime.AgentChildControlPolicy
import io.github.mangi.eta.ui.components.AgentStopSelection
import io.github.mangi.eta.ui.components.ConversationSubAgentEditor
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.PowerManager
import android.view.Choreographer
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
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionRequest
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
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
import io.github.mangi.eta.ui.model.RequestOverheadCalibration
import io.github.mangi.eta.ui.model.ContextEstimateDiagnostics
import io.github.mangi.eta.config.AutoCompressPreference
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.supportsGptSpeedBinding
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.EtaBackupExportOptions
import io.github.mangi.eta.data.repository.EtaBackupRepository
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.ProviderBalanceStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.MainAgentSpeedDefaultsRepository
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.RuntimeConfigRepository

import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.isSteerSupplement
import io.github.mangi.eta.ui.model.hasPartialAssistantAfterLastUser
import io.github.mangi.eta.ui.model.canContinueDisconnectedRun
import io.github.mangi.eta.ui.model.MessageSearchHit
import io.github.mangi.eta.ui.model.MessageSearchRoleLabels
import io.github.mangi.eta.ui.model.searchConversationMessages
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentOwnerContextState
import io.github.mangi.eta.ui.model.normalizeTerminalRunMessages
import io.github.mangi.eta.ui.model.withTerminalBodiesInOrder
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import io.github.mangi.eta.ui.model.incrementalSnapshot
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.AgentContextCompactionUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.conversationTokenUsage
import io.github.mangi.eta.ui.model.latestBilledContextTokens
import io.github.mangi.eta.ui.model.liveContextUsage
import io.github.mangi.eta.ui.model.compressionContextUsage
import io.github.mangi.eta.ui.model.cacheDisplayName
import io.github.mangi.eta.ui.model.toOutboundModelImage
import io.github.mangi.eta.ui.model.visibleFileReferences
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
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

    // Separate main-agent namespace; the existing full preferences backup includes this scalar.
    private val mainAgentSpeedDefaults by lazy {
        MainAgentSpeedDefaultsRepository(requireNotNull(Prefs.localAgentPreferences()) {
            "Agent preferences 未初始化"
        })
    }

    fun currentDraftField() = conversationDrafts.field(selectedConversationId)

    private val skillZipImportGateway = skillZipImportGateway ?: CoreSkillZipImportGateway(appContext)
    private val runConversationIds = mutableMapOf<String, String>()
    private val runUsageResumeRounds = mutableMapOf<String, Int>()
    private val runUsageOwners = mutableMapOf<String, Pair<String, String>>()
    private val runRequestRounds = mutableMapOf<String, Int>()
    private val runUsageRoutes = mutableMapOf<String, String>()
    private val usageRunByConversation = mutableMapOf<String, String>()
    private val contextEstimateDiagnostics = ContextEstimateDiagnostics()
    private val invalidatedUsageRuns = mutableSetOf<String>()
    private val runGeneratedAtMillis = mutableMapOf<String, Long>()
    // A stopped worker still owns its transcript until its terminal result is committed.
    private val stoppingRuns = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    // The UI must be able to unlock itself: a stop that never gets a RunResult cannot hold the screen.
    private val stopSealTimeout = RunStopSealTimeout()
    private val stopSealTerminalTimeout =
        RunStopSealTimeout(timeoutMillis = RunStopSealTimeout.TERMINAL_GRACE_MS)
    private val stopSealWatchdogJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val mainStopReasons = java.util.concurrent.ConcurrentHashMap<String, AgentChildControlPolicy.Reason>()
    private val modelRetryState = AgentRunRetryState()
    private val runOverheadTokens = mutableMapOf<String, Int>()

    /**
     * Window the run was actually launched with.
     *
     * A run keeps the config it snapshotted at send time, so changing the maximum
     * context mid-run does not affect the request already in flight. The picker,
     * however, immediately reports the new window, and judging an in-flight run
     * against it made the percentage jump for reasons the run never saw.
     */
    private val runContextWindows = mutableMapOf<String, Int>()
    private val runMessageProjector = AgentRunMessageProjector()
    private val runReplayBatch = AgentRunReplayBatch()
    private val runEventCoalescer = AgentRunEventCoalescer()
    private val conversationSummaryCache = ConversationSummaryCache()
    private val runEventFlushJobs = mutableMapOf<String, Job>()
    private val runEventBudgets = mutableMapOf<String, AgentRunEventBudget<AgentEvent>>()
    private val runEventBudgetCallbacks = linkedSetOf<String>()
    private val runEventDeferredFlushes = mutableMapOf<String, () -> Unit>()
    private val runFrameEventBudget = AgentFrameEventBudget(STREAM_EVENT_FRAME_BUDGET_NS)
    private var runEventBudgetFrameCallback: Choreographer.FrameCallback? = null
    private val drainingRunEventBudgets = mutableSetOf<String>()
    private var runEventFrameWorkDepth = 0
    private val runJobs = androidx.compose.runtime.mutableStateMapOf<String, Job>()
    private val imageGenerationRunIds = mutableSetOf<String>()
    private val directMediaRuns = DirectMediaRunControl()
    private val ownerContexts = mutableMapOf<String, AgentOwnerContextState>()
    private val contextHideJobs = mutableMapOf<AgentOwnerContextState.HideToken, Job>()
    private data class PendingSteerDraft(
        val conversationId: String?, val imageIds: Set<String>, val fileIds: Set<String>,
        val mentionIds: Set<String> = emptySet(),
        val submittedText: String = "",
    )
    private val pendingSteerDrafts = mutableMapOf<String, PendingSteerDraft>()
    private var compressionJob: Job? = null
    /** Conversation the active [compressionJob] belongs to; null for none/unknown. */
    private var compressionJobConversationId: String? = null
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
    private val persistenceQueue by lazy {
        LatestConversationSaveQueue<ConversationSaveSnapshot>(
            scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
            merge = { previous, latest -> latest.mergeRetiredFrom(previous) },
            write = { snapshot -> writeConversationSnapshot(snapshot) },
        )
    }
    private val conversationPersistenceMutex = Mutex()
    @Volatile private var lastConversationPersistenceError: String? = null
    private var conversationArchiveBusy = false
    private var runtimeRefreshAfterArchive = false
    private var bindingRefreshAfterArchive = false
    private val deferredArchiveSaves = mutableListOf<Pair<kotlinx.coroutines.CompletableDeferred<Boolean>, (() -> Unit)?>>()
    private val runtimeRecoveryInProgress = AtomicBoolean(false)
    // Main-thread cursor: bounded recovery passes rotate rather than dropping older owners.
    private var questionRecoveryQueryOffset = 0
    private val defaultThinkingEnabled = agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
    private var skillNoticeSequence = 0L
    private var pendingSkillZipUri: Uri? = null
    private var pendingSkillZipSha256: String? = null
    private var selectionProviders: List<io.github.mangi.eta.data.model.ProviderSetting> = emptyList()
    private var defaultProviderId: String? = null
    private var defaultModelId: String? = null
    private var modelBindingGeneration = 0L
    private var memoryEditGeneration = 0L
    private val overheadSelection = RequestOverheadSelection()
    private var overheadConfigurationGeneration = 0L

    private var currentReasoningCapabilities: ModelReasoningCapabilities? = null
    private var fileAttachmentOwnerVersion = 0L
    private var conversationSelectionVersion = 0L
    private var conversationSelectionJob: Job? = null
    private var deleteAllConversationsJob: Job? = null
    private val preparingConversationMentions = mutableSetOf<String>()
    private val chatImageCache = AgentChatImageCache(appContext)


    private var selectedConversationId: String?
    private var conversationsById: Map<String, AgentChatHomeUiState>
    private var conversationTitles: Map<String, String>
    private var conversationUpdatedAt: Map<String, Long>
    private var conversationCreatedAt: Map<String, Long>
    private var conversationFolderIds: Map<String, String>
    private var conversationPinned: Set<String>
    private var conversationCompletionMarkers: Set<String>
    private var conversationFolders: List<ConversationFolderUi>

    init {
        // A constructor-local snapshot must not pin the original transcript after
        // compression, deletion or backup reload replaces the live conversation map.
        val initialConversations = AgentConversationStore.load(appContext, selectedOnly = true)
        selectedConversationId = initialConversations.selectedConversationId
        conversationsById = initialConversations.conversationsById.mapValues { (_, state) -> orderedTerminalState(state) }
        conversationTitles = initialConversations.titles
        conversationUpdatedAt = initialConversations.updatedAt
        conversationCreatedAt = initialConversations.createdAt
        conversationFolderIds = initialConversations.folderIds
        conversationPinned = initialConversations.pinnedIds
        conversationCompletionMarkers = initialConversations.completionMarkerIds - listOfNotNull(initialConversations.selectedConversationId)
        conversationFolders = initialConversations.folders
    }

    // UI and Runtime use explicit owners. A selected UI owner is never a Runtime lookup key.
    val conversationSubAgentPreferences = ConversationSubAgentPreferences(canEdit = ::canEditSubAgentSettings)
    private val pendingSubAgentDraftBindings = mutableMapOf<String, SubAgentConfigKey.Draft>()
    private var subAgentDraftReady by mutableStateOf(true)
    private var subAgentDraftPointerReloadPending = false
    private var subAgentRestoreBlocked by mutableStateOf(false)
    private var subAgentConfigFailure by mutableStateOf<String?>(null)
    private var pendingSubAgentDraftSource: SubAgentConfigKey? = selectedConversationId?.let { SubAgentConfigKey.Conversation(it) }
    private var draftSubAgentOwner by mutableStateOf(initializeSubAgentDraft())

    private fun readSubAgentDraftPointer(source: SubAgentConfigKey?): SubAgentConfigKey.Draft {
        val pointer = Prefs.getString("agent_conversation_child_ui_draft_v1")
        if (pointer.isNotBlank()) {
            val existing = SubAgentConfigKey.Draft(pointer)
            if (conversationSubAgentPreferences.existingDraftOrNull(existing) != null) return existing
            // A promoted draft may have been retired, but its selected conversation is authoritative.
            check(source is SubAgentConfigKey.Conversation) { "原草稿配置缺失，不能替换为默认配置" }
        }
        val created = conversationSubAgentPreferences.createDraft(source)
        Prefs.putString("agent_conversation_child_ui_draft_v1", created.value)
        return created
    }

    private fun initializeSubAgentDraft(): SubAgentConfigKey.Draft = try {
        readSubAgentDraftPointer(pendingSubAgentDraftSource)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        subAgentDraftReady = false
        subAgentDraftPointerReloadPending = true
        subAgentConfigFailure = "草稿配置读取失败；原数据未覆盖，请在子代理设置中重试。"
        SubAgentConfigKey.Draft(java.util.UUID.randomUUID().toString())
    }

    val subAgentConfigOwner: SubAgentConfigKey
        get() = selectedConversationId?.let { SubAgentConfigKey.Conversation(it) } ?: draftSubAgentOwner

    fun canEditSubAgentSettings(owner: SubAgentConfigKey): Boolean {
        if (owner != subAgentConfigOwner || conversationArchiveBusy || subAgentRestoreBlocked) return false
        if (owner is SubAgentConfigKey.Draft && !subAgentDraftReady) return false
        // Paused/stopping parents still own their run. Children and other conversations do not lock this owner.
        if (homeState.isStreaming || homeState.isPaused) return false
        val id = selectedConversationId
        return runConversationIds.none { (runId, conversation) ->
            conversation == id && (runJobs[runId]?.isActive == true || stoppingRuns.containsKey(runId))
        }
    }

    fun subAgentEditor(owner: SubAgentConfigKey): ConversationSubAgentEditor =
        ConversationSubAgentEditor(owner, conversationSubAgentPreferences) { canEditSubAgentSettings(owner) }.also { editor ->
            editor.bindLifecycleState(
                reason = {
                    if (subAgentRestoreBlocked || (owner is SubAgentConfigKey.Draft && !subAgentDraftReady))
                        subAgentConfigFailure ?: "会话配置尚未恢复"
                    else null
                },
                recovery = { owner == subAgentConfigOwner && ensureSubAgentConfigurationReady() },
            )
        }

    private fun subAgentConfigFailed(failure: Exception) {
        if (failure is CancellationException) throw failure
        subAgentConfigFailure = "会话配置保存或读取失败；原配置已保留，请在子代理设置中重试。"
        AndroidAgentLogger.warn("Sub-agent configuration unavailable: type=${failure.safeLogType()}")
        Toast.makeText(appContext, subAgentConfigFailure, Toast.LENGTH_LONG).show()
    }

    private fun beginNewSubAgentDraft(
        source: SubAgentConfigKey? = subAgentConfigOwner,
        recoveringPending: Boolean = false,
    ): Boolean {
        // Never clone an unresolved placeholder or discard its original-pointer recovery intent.
        if (recoveringPending && source == null) return false
        if (source is SubAgentConfigKey.Draft && !subAgentDraftReady &&
            (!recoveringPending || subAgentDraftPointerReloadPending)) {
            Toast.makeText(appContext, "请先在子代理设置中重试恢复原草稿配置。", Toast.LENGTH_LONG).show()
            return false
        }
        return try {
            if (source is SubAgentConfigKey.Draft) {
                check(conversationSubAgentPreferences.existingDraftOrNull(source) != null) {
                    "原草稿配置不可读取，不能替换为默认配置"
                }
            }
            val next = conversationSubAgentPreferences.createDraft(source)
            Prefs.putString("agent_conversation_child_ui_draft_v1", next.value)
            draftSubAgentOwner = next
            subAgentDraftReady = true
            subAgentDraftPointerReloadPending = false
            pendingSubAgentDraftSource = null
            subAgentConfigFailure = null
            true
        } catch (failure: Exception) {
            pendingSubAgentDraftSource = source
            subAgentDraftReady = false
            subAgentConfigFailed(failure)
            false
        }
    }

    private fun ensureSubAgentConfigurationReady(): Boolean = try {
        if (subAgentRestoreBlocked) {
            conversationSubAgentPreferences.refreshAfterRestore()
            val next = readSubAgentDraftPointer(selectedConversationId?.let { SubAgentConfigKey.Conversation(it) })
            draftSubAgentOwner = next
            subAgentDraftReady = true
            subAgentRestoreBlocked = false
            Prefs.putString("agent_conversation_child_ui_draft_v1", next.value)
        }
        if (selectedConversationId == null && !subAgentDraftReady && subAgentDraftPointerReloadPending) {
            val recovered = readSubAgentDraftPointer(pendingSubAgentDraftSource)
            draftSubAgentOwner = recovered
            subAgentDraftPointerReloadPending = false
            subAgentDraftReady = true
            subAgentConfigFailure = null
        }
        // Only recovery of the same unselected draft resumes its saved source.
        if (selectedConversationId == null && !subAgentDraftReady && !beginNewSubAgentDraft(pendingSubAgentDraftSource, recoveringPending = true)) false
        else {
            conversationSubAgentPreferences.snapshot(subAgentConfigOwner)
            true
        }
    } catch (failure: Exception) { subAgentConfigFailed(failure); false }

    private fun bindSubAgentDraft(conversationId: String): Boolean = try {
        val draft = draftSubAgentOwner
        conversationSubAgentPreferences.bindDraft(draft, SubAgentConfigKey.Conversation(conversationId))
        pendingSubAgentDraftBindings[conversationId] = draft
        true
    } catch (failure: Exception) { subAgentConfigFailed(failure); false }

    data class StopRequest(
        val selection: AgentStopSelection<AgentChildTaskGroups.StopTarget>,
        val mainRunning: Boolean,
        val childrenRunning: Boolean,
    )

    fun captureStopRequest(): StopRequest? {
        val owner = selectedConversationId ?: return null
        val runId = activeRunIdForSelectedConversation()
        val children = AgentChildTaskGroups.captureStopTarget(owner)
        if (runId == null && children == null) return null
        return StopRequest(AgentStopSelection(owner, runId, children), runId != null, AgentChildTaskGroups.hasActive(owner))
    }

    private fun stopSelectionMatches(target: StopRequest, checkChildren: Boolean): Boolean {
        val saved = target.selection
        if (saved.ownerId != selectedConversationId || saved.runId != activeRunIdForSelectedConversation()) return false
        return !checkChildren || saved.groupTarget == AgentChildTaskGroups.captureStopTarget(saved.ownerId)
    }

    fun stopMainReply(target: StopRequest): Boolean {
        if (!stopSelectionMatches(target, checkChildren = false)) return false
        val runId = target.selection.runId ?: return false
        contextBudgetBlockedRuns.remove(runId)
        if (contextBudgetPrompt?.runId == runId) contextBudgetPrompt = null
        stopRun(runId, keepChildren = true)
        return true
    }

    fun stopEntireTask(target: StopRequest): Boolean {
        if (!stopSelectionMatches(target, checkChildren = true)) return false
        val selection = target.selection
        selection.groupTarget?.let { if (!AgentChildTaskGroups.stop(it)) return false }
        // Stop the captured group above, then only the captured parent. A late control message
        // must not cancel a new generation of children created after this confirmation.
        selection.runId?.let { runId ->
            contextBudgetBlockedRuns.remove(runId)
            if (contextBudgetPrompt?.runId == runId) contextBudgetPrompt = null
            stopRun(runId, keepChildren = true)
        }
        return true
    }

    fun canPauseStopRequest(target: StopRequest): Boolean =
        stopSelectionMatches(target, checkChildren = false) && target.selection.runId != null &&
            target.selection.runId !in imageGenerationRunIds && !homeState.isPaused

    fun pauseStopRequest(target: StopRequest): Boolean {
        if (!canPauseStopRequest(target)) return false
        pauseCurrentRun()
        return true
    }

    private var selectedFolderId: String? = null
    private var pendingNewConversationFolderId: String? = null

    var homeState by mutableStateOf(
        selectedConversationId?.let(::conversationState) ?: emptyChatState(false)
    )
        private set

    private val autoCompressPreference = AutoCompressPreference()
    var autoCompressEnabled by mutableStateOf(autoCompressPreference.enabled)
        private set

    var requestOverheadTokens by mutableStateOf(0)
        private set

    /** 有效回执判定：未在等待新回执 + 实际路由签名仍然一致。
     * 学习签名已随显示学习一起退场：custom gateway/headers 取不到 learning 签名，但
     * actual-local-v1 实际路由摘要能验证“同一 provider/模型/配置”，因此同样算已测。
     * 未测豁免、压缩锚点与预算 seed 必须共用这一个判定（否则 UI 认为已测而 runtime 没有 seed）。 */
    private fun hasEffectiveContextReceipt(state: AgentChatHomeUiState): Boolean =
        !state.contextAwaitingReceipt && state.cloudRouteSignature != null &&
            state.cloudRouteSignature == contextRouteSignature(state)

    /** 有效实测 helper：正数真实回执 + 有效实际路由/历史证据。 */
    private fun validMeasuredContextTokens(state: AgentChatHomeUiState): Int? =
        billedPromptTokens(state)?.takeIf { tokens -> tokens > 0 && hasEffectiveContextReceipt(state) }

    /** Runtime 未实测豁免接线：只有“摘要真正提交过”（history 已被摘要替换）或本次请求确实
     * 没有有效实测时才允许越界发送；known 仍由既有校准锚点/本地硬限约束。
     * prune-only 或摘要失败都不算已压缩，也不能靠“看起来清了历史”来解锁。 */
    private fun unmeasuredContextSendAllowed(
        contextState: AgentChatHomeUiState,
        summaryCommitted: Boolean,
    ): Boolean = summaryCommitted || validMeasuredContextTokens(contextState) == null

    /** 严格路由签名：customHeaders/customBody/sessionGatewayJson 一律 fail closed，
     * 凭据内容从不参与任何持久化比较。 */
    private fun strictRouteSignature(state: AgentChatHomeUiState): String? {
        val provider = selectionProviders.firstOrNull { it.id == state.providerId && it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == state.modelId && it.isEnabled } ?: return null
        return RequestOverheadCalibration.routeSignature(provider, model).takeIf { it.isNotBlank() }
    }

    /** Actual receipts need a verifiable local scope even when custom routing forbids learning.
     * Only a digest is checkpointed; custom configuration is never a calibration scope. */
    private fun contextRouteSignature(state: AgentChatHomeUiState): String? {
        val provider = selectionProviders.firstOrNull { it.id == state.providerId && it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == state.modelId && it.isEnabled } ?: return null
        strictRouteSignature(state)?.let { return it }
        val endpoint = when (provider) {
            is io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting -> provider.endpointMode
            is io.github.mangi.eta.data.model.CustomProviderSetting -> provider.endpointMode
            is io.github.mangi.eta.data.model.AnthropicProviderSetting -> provider.anthropicVersion
        }
        val fields = org.json.JSONArray().put(provider.id).put(provider.baseUrl).put(provider.sourceType)
            .put(endpoint).put(provider.systemPrompt).put(provider.authMode).put(provider.apiKey)
            .put(provider.responsesStripReasoningStatus).put(provider.hostedWebSearchEnabled)
            .put(provider.sessionGatewayJson)
            .put(org.json.JSONArray(provider.customHeaders.map { listOf(it.name, it.value) }))
            .put(org.json.JSONArray(provider.customBody.map { listOf(it.key, it.value.toString()) }))
            .put(kotlinx.serialization.json.Json.encodeToString(io.github.mangi.eta.data.model.Model.serializer(),
                model.copy(createdAt = 0, displayName = "", sortOrder = 0)))
        return "actual-local-v1:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(fields.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** UI 侧同一个“有效实测”：InputBar 只有在它为 null（真未知）时才因为本地估算解除发送禁用，
     * 已有实测的 known 状态保持原有 99% 拦截与 autoCompress 短路。 */
    val measuredContextTokens: Int?
        get() = if (homeState.messageEdit == null) validMeasuredContextTokens(homeState) else null

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
        observeConversationSummaryDates()
        observeRuntimeSelection()
        observeAutoCompressEnabled()
        scope.launch(Dispatchers.Main.immediate) {
            conversationSubAgentPreferences.revision.collectLatest { refreshRequestOverhead() }
        }
        observeOwnerContexts()
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

    private fun ownerContext(ownerId: String): AgentOwnerContextState =
        ownerContexts.getOrPut(ownerId) { AgentOwnerContextState(ownerId) }

    private fun observeOwnerContexts() {
        scope.launch(Dispatchers.Main.immediate) {
            AgentChildTaskGroups.revision.collect {
                val owners = ownerContexts.keys.toSet() + listOfNotNull(selectedConversationId)
                owners.filter { it in conversationsById }.forEach { refreshOwnerContext(it) }
            }
        }
    }

    private fun requestOwnerContext(ownerId: String) {
        scope.launch(Dispatchers.Main.immediate) { refreshOwnerContext(ownerId) }
    }

    private suspend fun refreshOwnerContext(ownerId: String) {
        if (ownerId !in conversationsById) return
        // The registry and UI share the application process. Reads have no control/handoff effects.
        val snapshot = withContext(Dispatchers.IO) {
            var result: Pair<Long, List<io.github.mangi.eta.agent.delegation.SubAgentContextStats>>? = null
            for (attempt in 0 until 3) {
                val before = AgentChildTaskGroups.revision.value
                val contexts = AgentChildTaskGroups.contextStats(ownerId)
                if (before == AgentChildTaskGroups.revision.value) {
                    result = before to contexts
                    break
                }
            }
            result
        } ?: return
        if (ownerId !in conversationsById) return
        val reducer = ownerContext(ownerId)
        val previous = reducer.projection().children.associateBy { it.taskId }
        reducer.refresh(ownerId, snapshot.second.map { stats ->
            AgentOwnerContextState.TaskSnapshot(
                stats, snapshot.first, stats.statusVersion,
                stats.statusChangedAtMs?.takeIf { it > 0L },
            )
        })
        if (ownerId == selectedConversationId && snapshot.second.any {
            it.manualCompactionState == "ended" && previous[it.taskId]?.manualCompactionState == "pending"
        }) Toast.makeText(appContext, "子任务已结束，压缩请求已收束；结果保留，不会重启任务或压缩主代理。", Toast.LENGTH_LONG).show()
        publishOwnerContext(ownerId)
        scheduleOwnerContextHides(ownerId)
    }

    private fun publishOwnerContext(ownerId: String) {
        val view = ownerContexts[ownerId]?.projection() ?: return
        val current = conversationsById[ownerId]?.takeIf { it.conversationContentLoaded } ?: return
        updateConversation(ownerId, current.copy(childContexts = view.children,
            selectedContextTaskId = view.selectedTaskId), updateTimestamp = false)
    }

    private fun scheduleOwnerContextHides(ownerId: String) {
        val state = ownerContexts[ownerId] ?: return
        val wanted = state.pendingHides().toSet()
        contextHideJobs.keys.filter { it.ownerId == ownerId && it !in wanted }.toList().forEach {
            contextHideJobs.remove(it)?.cancel()
        }
        wanted.forEach { token ->
            if (token !in contextHideJobs) {
                val job = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
                    try {
                        delay(state.remainingMs(token))
                        if (state.expire(token)) publishOwnerContext(ownerId)
                    } finally {
                        contextHideJobs.remove(token)
                    }
                }
                contextHideJobs[token] = job
                job.start()
            }
        }
    }

    private fun observeAutoCompressEnabled() {
        scope.launch(Dispatchers.Main.immediate) {
            val compression = autoCompressPreference.observe { autoCompressEnabled = it }
            val prefs = Prefs.localAgentPreferences()
            val overheadKeys = setOf(
                Prefs.Keys.AGENT_TERMINAL_TOOLS,
                Prefs.Keys.AGENT_BROWSER_TOOLS,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            )
            val overheadListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in overheadKeys) {
                    scope.launch(Dispatchers.Main.immediate) { refreshRequestOverhead() }
                }
            }
            prefs?.registerOnSharedPreferenceChangeListener(overheadListener)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                prefs?.unregisterOnSharedPreferenceChangeListener(overheadListener)
                compression.close()
            }
        }
    }

    private fun requestOverheadAssistant(state: AgentChatHomeUiState = homeState) =
        AssistantRepository.profile(resolvedAssistantId(state)) ?: AssistantRepository.active()

    private fun currentOverheadBinding() = RequestOverheadSelection.Binding(
        owner = when (val owner = subAgentConfigOwner) {
            is SubAgentConfigKey.Conversation -> "conversation:${owner.value}"
            is SubAgentConfigKey.Draft -> "draft:${owner.value}"
            is SubAgentConfigKey.Preset -> error("预设不能作为会话运行配置")
        },
        providerId = homeState.providerId,
        modelId = homeState.modelId,
        assistantId = requestOverheadAssistant().id,
        modelGeneration = modelBindingGeneration,
        configurationGeneration = overheadConfigurationGeneration,
    )

    fun refreshRequestOverhead(configurationChanged: Boolean = true) {
        // Skills, MCP schemas and capability snapshots lack one common revision. Existing
        // refresh callers signal a possible change, so invalidate conservatively before IO.
        // Only an explicitly known-same-configuration retry may retain a successful estimate.
        if (configurationChanged) overheadConfigurationGeneration++
        val binding = currentOverheadBinding()
        val overheadRequest = overheadSelection.begin(binding)
        // A previous binding's positive value is not a fallback for an unknown new binding.
        requestOverheadTokens = overheadSelection.tokensFor(binding) ?: 0
        val state = homeState
        val owner = subAgentConfigOwner
        val assistant = requestOverheadAssistant()
        val providers = selectionProviders.toList()
        scope.launch(Dispatchers.IO) {
            val tokens = try {
                val provider = providers.firstOrNull { it.id == state.providerId && it.isEnabled }
                val model = provider?.models?.firstOrNull { it.id == state.modelId && it.isEnabled }
                if (provider == null || model == null) null else {
                    // Preview must not call configForProviderAndModel: that can refresh OAuth.
                    val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model, assistant).copy(
                        terminalTools = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                        browserTools = agentBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
                        deviceDirectTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                        deviceSensitiveReadTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                        deviceSensitiveActionTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                    )
                    estimateRequestOverhead(config, assistant, owner, providers)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null // Unknown, not a successfully measured zero. Keep this binding's valid value.
            }
            withContext(Dispatchers.Main) {
                if (requestOverheadAssistant() != assistant ||
                    !overheadSelection.complete(overheadRequest, currentOverheadBinding(), tokens)) return@withContext
                requestOverheadTokens = requireNotNull(tokens)
                syncBilledOverhead(selectedConversationId, homeState.messages)
            }
        }
    }

    private suspend fun estimateRequestOverhead(
        config: AgentModelClient.ModelConfig,
        assistant: io.github.mangi.eta.data.model.AssistantProfile,
        owner: SubAgentConfigKey,
        providers: List<io.github.mangi.eta.data.model.ProviderSetting>,
    ): Int {
        val enabledSkillIds = assistant.enabledSkillIds.toSet()
        val skillContext = SkillContext(
            installedSkills = SkillRuntime.createIndexService(appContext)
                .listSkillsForManagement()
                .filter { it.installed && it.id in enabledSkillIds }
                .filter { SkillCompatibilityChecker.evaluate(it).available },
        )
        val memoryContext = if (assistant.memoryEnabled) {
            AgentMemoryContextBuilder.build(
                snapshot = AgentMemoryRepository.snapshot(assistant.id),
                contextWindow = config.contextWindow,
            )
        } else {
            AgentMemoryContext.DISABLED
        }
        val additionalTools = JSONArray()
        McpRunSnapshot.appendCachedModelTools(additionalTools, McpServerRepository.enabledServers())
        val childPrompt = io.github.mangi.eta.agent.delegation.SubAgentRequestPreview.appendTo(
            tools = additionalTools,
            owner = owner,
            config = conversationSubAgentPreferences.previewSnapshot(owner),
            providers = providers,
            workspaceEnabled = config.terminalTools,
        )
        return AgentRequestOverhead.estimate(
            config = config,
            skillContext = skillContext,
            memoryContext = memoryContext,
            capabilities = AgentToolCapabilities.capture(appContext),
            additionalTools = additionalTools,
        ) + AgentContextBudget.countTokens(childPrompt)
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
            billedOverheadTokens = overheadSelection.tokensFor(currentOverheadBinding())
        }
    }

    private fun updateSelectionProviders(providers: List<io.github.mangi.eta.data.model.ProviderSetting>) {
        if (selectionProviders != providers) modelBindingGeneration++
        selectionProviders = providers
        // Invalidate the old run permanently, including if settings are later changed back.
        runUsageRoutes.forEach { (runId, route) ->
            val state = conversationIdForRun(runId)?.let(::conversationState)
            if (state != null && route != contextRouteSignature(state)) invalidatedUsageRuns.add(runId)
        }
        // Project every loaded binding, including background conversations without a usage receipt.
        // Ineligible bindings display NORMAL without erasing their independent GPT memory.
        conversationsById.toList().forEach { (id, state) ->
            val next = state.withCurrentGptSpeedBinding()
            if (state.conversationContentLoaded && (next != state ||
                (state.cloudRouteSignature != null && state.cloudRouteSignature != contextRouteSignature(state)))) {
                updateConversation(id, next, updateTimestamp = false)
            }
        }
        if (selectedConversationId == null) homeState = homeState.withCurrentGptSpeedBinding()
    }

    private fun AgentChatHomeUiState.withCurrentGptSpeedBinding(): AgentChatHomeUiState = copy(
        gptSpeedMode = rememberedGptSpeedMode(providerId, modelId),
    )

    private fun rememberedGptSpeedMode(providerId: String, modelId: String): GptSpeedMode {
        val provider = selectionProviders.singleOrNull { it.id == providerId }?.takeIf { it.isEnabled }
        val model = provider?.models?.singleOrNull { it.id == modelId }?.takeIf { it.isEnabled }
        // Do not read/write memory for non-GPT or missing selections, including startup placeholders.
        if (!supportsGptSpeedBinding(provider, model)) return GptSpeedMode.NORMAL
        return mainAgentSpeedDefaults.modeFor(providerId, modelId)
    }

    private fun observeRuntimeSelection() {
        scope.launch {
            combine(SettingsDataStore.settingsFlow(), ProviderRepository.providersFlow()) { settings, providers ->
                Triple(settings.selectedProviderId, settings.selectedModelId, providers)
            }.distinctUntilChanged(::runtimeSelectionUnchanged)
                .collectLatest { (providerId, modelId, providers) ->
                    updateSelectionProviders(providers)
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
        val provider = selectionProviders.singleOrNull { it.id == homeState.providerId }?.takeIf { it.isEnabled }
        val model = provider?.models?.singleOrNull { it.id == homeState.modelId }?.takeIf { it.isEnabled }
        val projected = AgentModelPickerProjector.project(selectionProviders, homeState.providerId, homeState.modelId)
        // The shared picker deduplicates API aliases for listing. An already bound selection must
        // still project ITS exact model ID, not lose its speed action to another entry's alias.
        val boundOption = if (provider != null && model != null) {
            AgentModelPickerProjector.project(listOf(provider.withModels(listOf(model))), provider.id, model.id).selectedModel
        } else null
        modelPickerState = projected.copy(selectedModel = boundOption?.takeIf {
            provider != null && model != null && it.providerId == provider.id && it.id == model.id
        }, isChanging = false)
        currentReasoningCapabilities = if (provider != null && model != null)
            RuntimeConfigRepository.buildRuntimeConfig(provider, model, assistant = null).reasoningCapabilities else null
        // Display effective choices, but retain the user's saved preference until they explicitly change it.
        val next = if (initializingDraftBinding && model != null) {
            homeState.withPreferredReasoningEffort()
        } else homeState.copy(availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty())
        val scopedNext = if (provider != null && model != null && next.cloudRouteSignature != null &&
            next.cloudRouteSignature != contextRouteSignature(next)) next.copy(
                livePromptTokens = null, livePromptIsProjected = false, cloudHistoryTokens = null,
                contextBudgetReceiptTokens = null,
                cloudRequestOverheadTokens = null, cloudRouteSignature = null, cloudReceiptRequestId = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null, contextAwaitingReceipt = true,
                contextHasStarted = true) else next
        // Restore by provider ID + model selection ID, never copy the previous model's tier.
        // Unavailable/non-GPT projections are NORMAL and never overwrite remembered GPT choices.
        val speedNext = scopedNext.withCurrentGptSpeedBinding()
        if (speedNext != homeState) {
            val conversationId = selectedConversationId
            if (conversationId == null) homeState = speedNext else updateConversation(conversationId, speedNext, updateTimestamp = false)
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

    private data class BranchRequestBoundary(
        val runId: String,
        val round: Int,
        val snapshotId: String,
        val textBaseline: Map<String, String>,
    )
    private val branchRequestBoundaries = mutableMapOf<String, BranchRequestBoundary>()
    private var branchHistorySnapshotLoader: (String, String, String) -> io.github.mangi.eta.agent.runtime.AgentRuntimeSession.HistorySnapshot? =
        { owner, run, snapshot -> AgentRuntimeClient(appContext, AndroidAgentLogger).queryHistory(owner, run, snapshot) }

    private var conversationRevisionBusy = false

    private fun rejectConversationArchiveMutation(protectStoppingRun: Boolean = true): Boolean {
        if (conversationRevisionBusy) {
            Toast.makeText(appContext, "正在恢复修订历史，请稍候。", Toast.LENGTH_SHORT).show()
            return true
        }
        // Stop settlement owns the current transcript, not navigation, metadata or model choice.
        // Those safe operations still obey the independent archive/backup maintenance lock.
        if (protectStoppingRun && stoppingRuns.keys.any { runConversationIds[it] == selectedConversationId }) {
            Toast.makeText(appContext, StopSealNotices.PENDING, Toast.LENGTH_SHORT).show()
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
        check(!conversationArchiveBusy && !conversationRevisionBusy) { "已有对话归档或修订任务正在执行" }
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
                    try {
                        val saved = persistConversations().await()
                        withContext(Dispatchers.IO) {
                            deferred.forEach { (completion, callback) ->
                                try { if (saved) callback?.invoke() }
                                catch (_: Exception) { /* A callback cannot undo the committed save. */ }
                                finally { completion.complete(saved) }
                            }
                        }
                    } finally {
                        deferred.forEach { (completion, _) -> completion.complete(false) }
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
            AgentConversationStore.load(appContext, selectedOnly = true)
        }
        withContext(Dispatchers.Main.immediate) {
            try {
                conversationSubAgentPreferences.refreshAfterRestore()
                val next = readSubAgentDraftPointer(snapshot.selectedConversationId?.let { SubAgentConfigKey.Conversation(it) })
                Prefs.putString("agent_conversation_child_ui_draft_v1", next.value)
                draftSubAgentOwner = next
                subAgentDraftReady = true
                subAgentRestoreBlocked = false
            } catch (failure: Exception) {
                subAgentRestoreBlocked = true
                subAgentConfigFailed(failure)
            }
            pendingSubAgentDraftBindings.clear()
            // Always reload committed Room history, even when preferences cannot yet be read.
            selectedConversationId = snapshot.selectedConversationId
            conversationsById = snapshot.conversationsById.mapValues { (_, state) -> orderedTerminalState(state) }
            conversationTitles = snapshot.titles
            conversationUpdatedAt = snapshot.updatedAt
            conversationCreatedAt = snapshot.createdAt
            conversationFolderIds = snapshot.folderIds
            conversationPinned = snapshot.pinnedIds
            conversationCompletionMarkers = snapshot.completionMarkerIds - listOfNotNull(snapshot.selectedConversationId)
            conversationFolders = snapshot.folders
            selectedFolderId = null
            pendingNewConversationFolderId = null
            fileAttachmentOwnerVersion += 1
            homeState = selectedConversationId
                ?.let(::conversationState)
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
        // Room-only question recovery must run even with no checkpoint/outbox or unavailable
        // run indexes. Run absence is not proof that a question's answer was never consumed.
        val questionConversationIds = withContext(Dispatchers.IO) {
            AgentConversationStore.questionConversationIds(appContext)
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
                val state = conversationState(conversationId) ?: return@forEach
                // A degraded checkpoint may omit an event boundary; apply the authoritative
                // result but do not replay an incomplete trace into conversation history.
                recoveryPlan.checkpoint
                    ?.takeUnless { it.recoveryIncomplete }
                    ?.let { checkpoint ->
                        stateChanged = restoreCheckpointTrace(
                            checkpoint = checkpoint,
                            interrupted = false,
                        ) || stateChanged
                    }
                val result = completedRun.result
                val beforeRecovery = conversationState(conversationId) ?: state
                val recovery = AgentPendingResultRecovery.apply(
                    state = beforeRecovery,
                    runId = runId,
                    result = result,
                    promptSupplement = payload.promptSupplement,
                    supplements = payload.supplements,
                    generatedAtMillis = recoveryPlan.checkpoint?.events
                        ?.filterIsInstance<AgentEvent.RunFinished>()?.lastOrNull()?.generatedAtMillis,
                )
                val ordered = normalizeTerminalRunMessages(runId, recovery.state.messages)
                val next = AgentPendingResultRecovery.stateToPublish(beforeRecovery, recovery, ordered)
                if (next != null) {
                    updateConversation(
                        conversationId, next,
                        updateTimestamp = !recovery.alreadyApplied,
                    )
                    stateChanged = true
                    if (ConversationCompletionMarker.shouldMark(
                            result, isSelected = conversationId == selectedConversationId,
                            alreadyApplied = recovery.alreadyApplied,
                        )) {
                        markConversationCompleted(conversationId)
                    }
                    if (!recovery.alreadyApplied && VirtualCompletionNotice.confirmed(result)) {
                        Toast.makeText(appContext, "任务完成", Toast.LENGTH_SHORT).show()
                    }
                }
                acknowledgeAfterSave += runId
            }

            plan.interrupted.forEach { checkpoint ->
                removeAfterSave += checkpoint.runId
                // An interrupted run with an incomplete checkpoint is intentionally
                // abandoned rather than replayed from an unsafe partial boundary.
                if (!checkpoint.recoveryIncomplete) {
                    stateChanged = restoreCheckpointTrace(
                        checkpoint = checkpoint,
                        interrupted = true,
                    ) || stateChanged
                }
            }
            if (stateChanged) refreshConversationSummaries()
            stateChanged || acknowledgeAfterSave.isNotEmpty() || removeAfterSave.isNotEmpty()
        }

        // Query after local terminal projection, before the durable save/ACK boundary, so a
        // missed QuestionResolved can correct an Interrupted card even on alreadyApplied runs.
        val questionChanges = reconcileRecoveredQuestions(client, questionConversationIds,
            completedRuns.map { AgentUiHandoffPayload.from(it.handoff.payload).conversationId })
        if (changed || questionChanges) {
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

    /** One finite authoritative question pass shared by outbox and Room-only recovery. */
    private suspend fun reconcileRecoveredQuestions(
        client: AgentRuntimeClient,
        storedConversationIds: List<String>,
        completedConversationIds: List<String>,
    ): Boolean {
        val conversationIds = withContext(Dispatchers.Main) {
            (completedConversationIds + storedConversationIds + conversationsById.filterValues {
                it.conversationContentLoaded && it.messages.any { m ->
                    m is AgentQuestionMessageUi && m.status != AgentQuestionStatus.Answered
                }
            }.keys).distinct()
        }
        val owners = mutableListOf<AgentQuestionRequest>()
        conversationIds.forEach { id ->
            val cached = withContext(Dispatchers.Main) { conversationsById[id] } ?: return@forEach
            if (!cached.conversationContentLoaded) {
                val loaded = withContext(Dispatchers.IO) {
                    AgentConversationStore.loadConversation(appContext, id)?.let(::orderedTerminalState)
                } ?: return@forEach
                withContext(Dispatchers.Main) {
                    // Do not overwrite a concurrent edit/delete while Room was being read.
                    if (conversationsById[id] === cached) {
                        conversationsById = conversationsById + (id to loaded.withCurrentGptSpeedBinding())
                    }
                }
            }
            withContext(Dispatchers.Main) {
                conversationsById[id]?.takeIf { it.conversationContentLoaded }?.let { state ->
                    owners += AgentQuestionProjection.recoveryOwners(id, state.messages)
                }
            }
        }
        val batch = withContext(Dispatchers.Main) {
            AgentQuestionProjection.recoveryQueryBatch(owners, questionRecoveryQueryOffset).also {
                questionRecoveryQueryOffset = it.nextOffset
            }
        }
        // At most 8 reads, in groups of 4, each interrupted after 5s. There is no polling,
        // and neither a truncated batch nor null/timeout is used as evidence of death.
        val observations = batch.owners.chunked(AgentQuestionProjection.RECOVERY_QUERY_CONCURRENCY)
            .flatMap { group ->
                withContext(Dispatchers.IO) {
                    group.map { owner -> async {
                        owner to withTimeoutOrNull(AgentQuestionProjection.RECOVERY_QUERY_TIMEOUT_MILLIS) {
                            runInterruptible {
                                client.queryQuestion(owner.conversationId, owner.runId, owner.questionId, owner.toolCallId)
                            }
                        }
                    } }.map { it.await() }
                }
            }
        return withContext(Dispatchers.Main) {
            var changed = false
            observations.forEach { (owner, snapshot) ->
                // Persisted content may have been evicted/deleted while IPC was pending; never
                // reinsert a stale conversation. A subsequent bounded pass can retry it.
                val state = conversationsById[owner.conversationId]
                    ?.takeIf { it.conversationContentLoaded } ?: return@forEach
                val messages = AgentQuestionProjection.reconcileMessages(state.messages, owner, snapshot)
                if (messages != state.messages) {
                    updateConversation(owner.conversationId, state.copy(messages = messages), updateTimestamp = false)
                    changed = true
                }
            }
            if (changed) refreshConversationSummaries()
            changed
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
        val existing = conversationState(conversationId) ?: return false
        if (AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return false

        bindUsageRun(runId, conversationId)
        updateConversation(conversationId, existing.copy(isStreaming = true))
        restoreRunEvents(runId, checkpoint.events)
        flushPendingRunDelta(runId)
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            if (interrupted) {
                val interruptedTools = AgentQuestionProjection.interruptWaiting(runId, runMessageProjector.interruptRunningTools(
                    reason = appContext.getString(R.string.system_notice_interrupted),
                    messages = finalizedText,
                ))
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
        branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId); invalidatedUsageRuns.remove(runId)
        runOverheadTokens.remove(runId); runContextWindows.remove(runId)
        contextEstimateDiagnostics.clear(runId)
        conversationUpdatedAt = conversationUpdatedAt +
            (conversationId to checkpoint.updatedAt)
        return true
    }

    private fun startReattachedRun(checkpoint: AgentRunCheckpointStore.Checkpoint) {
        val runId = checkpoint.runId
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        val existing = conversationState(conversationId) ?: return
        if (runId in runJobs || AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return

        bindUsageRun(runId, conversationId)
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
            if (conversationState(conversationId) == null) {
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
        val existingState = conversationState(conversationId) ?: emptyChatState(
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
        bindUsageRun(runId, conversationId)
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
        val replacedRun = activeRunIdForSelectedConversation().takeIf { homeState.isPaused }
        updateCurrentConversation(
            homeState.copy(
                thinkingEnabled = normalized.enablesReasoning,
                reasoningEffort = normalized,
            )
        )
        rememberModelReasoningEffort(homeState.providerId, homeState.modelId, normalized)
        if (selectedConversationId != null) persistConversations()
        replacedRun?.let { stopRun(it, reason = AgentChildControlPolicy.Reason.SETTINGS_CHANGED) }
    }

    fun cycleGptSpeedMode() {
        if (rejectConversationArchiveMutation()) return
        if (homeState.isStreaming && !homeState.isPaused) return
        if (modelPickerState.isChanging) return
        val selected = modelPickerState.selectedModel ?: return
        if (selected.id != homeState.modelId || selected.providerId != homeState.providerId) return
        val provider = selectionProviders.singleOrNull { it.id == selected.providerId }?.takeIf { it.isEnabled } ?: return
        val model = provider.models.singleOrNull { it.id == selected.id }?.takeIf { it.isEnabled } ?: return
        val eligible = supportsGptSpeedBinding(provider, model)
        if (!eligible) return
        val next = GptSpeedModePolicy.cycle(rememberedGptSpeedMode(provider.id, model.id), model.modelId, eligible)
        val saved = runCatching { mainAgentSpeedDefaults.remember(provider, model, next) }.getOrElse {
            Toast.makeText(appContext, "主代理模型速度保存失败，原设置未更改。", Toast.LENGTH_LONG).show()
            return
        }
        if (!saved) return
        val replacedRun = activeRunIdForSelectedConversation().takeIf { homeState.isPaused }
        updateCurrentConversation(homeState.copy(gptSpeedMode = next))
        // Refresh other loaded conversations bound to this exact selection. Runtime configs remain frozen.
        conversationsById.toList().forEach { (id, state) ->
            if (id != selectedConversationId && state.conversationContentLoaded &&
                state.providerId == provider.id && state.modelId == model.id) {
                updateConversation(id, state.copy(gptSpeedMode = next), updateTimestamp = false)
            }
        }
        // Never rewrite provider custom bodies, reasoning preferences, child defaults or presets.
        replacedRun?.let { stopRun(it, reason = AgentChildControlPolicy.Reason.SETTINGS_CHANGED) }
    }

    fun selectModel(modelId: String, providerId: String = "") {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        if (homeState.isStreaming && !homeState.isPaused) return
        val provider = selectionProviders.filter { it.isEnabled && (providerId.isBlank() || it.id == providerId) && it.models.any { m -> m.id == modelId && m.isEnabled } }.singleOrNull() ?: return
        if (selectionProviders.count { it.id == provider.id } != 1) return
        val model = provider.models.singleOrNull { it.id == modelId }?.takeIf { it.isEnabled } ?: return
        if (homeState.providerId == provider.id && homeState.modelId == model.id) return
        val replacedRun = activeRunIdForSelectedConversation().takeIf { homeState.isPaused }
        modelBindingGeneration++
        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model, assistant = null)
        val requestedEffort = rememberedModelReasoningEffort(provider.id, model.id, model.preferredReasoningEffort)
        val nextEffort = ConversationReasoningPolicy.resolve(requestedEffort, config.reasoningCapabilities)
        updateCurrentConversation(homeState.copy(providerId = provider.id, modelId = model.id,
            gptSpeedMode = rememberedGptSpeedMode(provider.id, model.id),
            reasoningEffort = nextEffort, thinkingEnabled = nextEffort.enablesReasoning,
            livePromptTokens = null, livePromptIsProjected = false))
        billedOverheadTokens = null
        refreshBoundModelPicker()
        if (nextEffort != requestedEffort) Toast.makeText(appContext,
            "已按新模型支持的档位调整当前对话的思考深度", Toast.LENGTH_SHORT).show()
        if (selectedConversationId != null) persistConversations()
        replacedRun?.let { stopRun(it, reason = AgentChildControlPolicy.Reason.SETTINGS_CHANGED) }
    }

    fun updateSearchQuery(query: String) {
        conversationPaneState = conversationPaneState.copy(searchQuery = query)
    }

    suspend fun searchHistory(
        query: String,
        currentConversationOnly: Boolean = false,
    ): List<MessageSearchHit> {
        if (query.isBlank()) return emptyList()
        val roles = MessageSearchRoleLabels(
            user = appContext.getString(R.string.search_history_role_user),
            assistant = appContext.getString(R.string.search_history_role_assistant),
            thinking = appContext.getString(R.string.search_history_role_thinking),
            tool = appContext.getString(R.string.search_history_role_tool),
        )
        val unnamed = appContext.getString(R.string.conversation_unnamed)
        val source = if (currentConversationOnly) currentConversationSearchScope() else
            conversationsById.toMutableMap().also { all ->
                selectedConversationId?.let { all[it] = homeState }
                if (selectedConversationId == null && homeState.messages.isNotEmpty()) all[""] = homeState
            }
        val titles = conversationTitles
        val timestamps = conversationUpdatedAt
        return runInterruptible(Dispatchers.IO) {
        source.entries.sortedWith(compareBy<Map.Entry<String, AgentChatHomeUiState>> {
            timestamps[it.key] ?: 0L
        }.thenBy { it.key }).flatMap { (id, state) ->
            if (state.conversationContentLoaded) {
                searchConversationMessages(mapOf(id to state), titles, timestamps, query, unnamed, roles)
            } else {
                AgentConversationStore.searchStoredConversation(appContext, id, titles[id].orEmpty(),
                    timestamps[id] ?: 0L, query, unnamed, roles)
            }
        }
        }
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
            selectConversationContent(hit.conversationId) { pendingScrollToMessageId = hit.messageId }
        } else {
            pendingScrollToMessageId = hit.messageId
        }
    }

    fun consumePendingScrollToMessage() {
        pendingScrollToMessageId = null
    }

    fun selectConversation(conversationId: String) = selectConversationContent(conversationId) {}

    private fun selectConversationContent(conversationId: String, onSelected: () -> Unit) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        if (homeState.messageEdit != null) cancelMessageEdit()
        val version = ++conversationSelectionVersion
        conversationSelectionJob?.cancel()
        val state = conversationsById[conversationId] ?: return
        if (state.conversationContentLoaded) {
            applySelectedConversation(conversationId, state)
            onSelected()
            return
        }
        val ownerVersion = fileAttachmentOwnerVersion
        conversationSelectionJob = scope.launch {
            try {
                val loaded = runInterruptible(Dispatchers.IO) {
                    AgentConversationStore.loadConversation(appContext, conversationId)
                } ?: return@launch
                if (version != conversationSelectionVersion || ownerVersion != fileAttachmentOwnerVersion || conversationArchiveBusy) return@launch
                val latest = conversationsById[conversationId] ?: return@launch
                // Runtime recovery may have hydrated/updated it while the database read ran.
                applySelectedConversation(conversationId, if (latest.conversationContentLoaded) latest else loaded)
                onSelected()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Conversation load failed: ${failure.safeLogType()}")
                Toast.makeText(appContext, "对话加载失败，请重试。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applySelectedConversation(conversationId: String, state: AgentChatHomeUiState) {
        check(state.conversationContentLoaded)
        fileAttachmentOwnerVersion += 1
        selectedConversationId = conversationId
        if (conversationId in conversationCompletionMarkers) {
            conversationCompletionMarkers = conversationCompletionMarkers - conversationId
        }
        val owner = ownerContext(conversationId)
        val view = owner.projection()
        val ordered = orderedTerminalState(state.withCurrentGptSpeedBinding()).copy(
            childContexts = view.children, selectedContextTaskId = view.selectedTaskId,
            childStatusRoster = owner.roster(),
        )
        conversationsById = conversationsById + (conversationId to ordered)
        homeState = ordered
        billedOverheadConversationId = null
        billedOverheadTokens = null
        syncBilledOverhead(conversationId, ordered.messages)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        refreshConversationSummaries()
        persistConversations()
        restoreConversationRuntimeModel()
        requestOwnerContext(conversationId)
    }

    fun createConversation() {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        if (homeState.messageEdit != null) cancelMessageEdit()
        if (!beginNewSubAgentDraft()) return
        val conversationId = newConversationId()
        if (!bindSubAgentDraft(conversationId)) return
        // A pending lazy selection must not replace the explicitly created conversation.
        conversationSelectionVersion += 1
        conversationSelectionJob?.cancel()
        fileAttachmentOwnerVersion += 1
        selectedConversationId = conversationId
        pendingNewConversationFolderId = selectedFolderId
        homeState = newDraftChatState()
        rememberModelReasoningEffort(homeState.providerId, homeState.modelId, homeState.reasoningEffort)
        billedOverheadConversationId = null
        billedOverheadTokens = null
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = conversationId,
            searchQuery = "",
        )
        // Explicit new conversations exist before their first message. Startup/deletion
        // placeholders still use null IDs and are not automatically added to history.
        updateConversation(conversationId, homeState)
        assignPendingFolder(conversationId)
        restoreConversationRuntimeModel()
        refreshConversationSummaries()
        persistConversations()
    }

    fun selectFolder(folderId: String?) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        selectedFolderId = folderId
        refreshConversationSummaries()
    }

    fun createFolder(name: String) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
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
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        conversationFolders = conversationFolders.map { folder ->
            if (folder.id == folderId) folder.copy(name = trimmed) else folder
        }
        refreshConversationSummaries()
        persistConversations()
    }

    fun deleteFolder(folderId: String) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        conversationFolders = conversationFolders.filterNot { it.id == folderId }
        conversationFolderIds = conversationFolderIds.filterValues { it != folderId }
        if (selectedFolderId == folderId) selectedFolderId = null
        if (pendingNewConversationFolderId == folderId) pendingNewConversationFolderId = null
        refreshConversationSummaries()
        persistConversations()
    }

    fun moveConversationToFolder(conversationId: String, folderId: String?) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
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
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
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
        if (rejectConversationArchiveMutation() || deleteAllConversationsJob?.isActive == true || conversationsById.isEmpty()) return
        deleteAllConversationsJob = scope.launch {
            try {
                withConversationArchive {
                    val source = conversationsById
                    val timestamps = conversationUpdatedAt
                    val ids = source.keys.toList()
                    val aggregate = runInterruptible(Dispatchers.IO) {
                        var usage = ConversationTokenUsageUi()
                        var messageCount = 0
                        val heatmap = mutableMapOf<java.time.LocalDate, Int>()
                        source.forEach { (id, state) ->
                            val content = if (state.conversationContentLoaded) state else
                                requireNotNull(AgentConversationStore.loadConversation(appContext, id))
                            val amount = conversationTokenUsage(content.messages)
                            usage = ConversationTokenUsageUi(usage.inputTokens + amount.inputTokens,
                                usage.outputTokens + amount.outputTokens, usage.cachedTokens + amount.cachedTokens)
                            messageCount += content.messages.count { it is UserMessageUi || it is AgentMessageUi }
                            timestamps[id]?.takeIf { it > 0L }?.let { timestamp ->
                                val day = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                                heatmap[day] = (heatmap[day] ?: 0) + 1
                            }
                        }
                        Triple(usage, messageCount, heatmap.toMap())
                    }
                    check(conversationsById === source && conversationUpdatedAt === timestamps) { "会话已变化，请重试" }
                    // Commit before deleting drafts/images or changing UI. Once committed,
                    // cancellation must not leave UI representing rows that no longer exist.
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        AgentConversationStore.save(appContext, null, emptyMap(), emptyMap(), emptyMap(), folders = conversationFolders)
                        withContext(Dispatchers.Main.immediate) {
                            pendingRetiredUsage = ConversationTokenUsageUi(
                                pendingRetiredUsage.inputTokens + aggregate.first.inputTokens,
                                pendingRetiredUsage.outputTokens + aggregate.first.outputTokens,
                                pendingRetiredUsage.cachedTokens + aggregate.first.cachedTokens,
                            )
                            pendingRetiredConversations += ids.size
                            pendingRetiredMessages += aggregate.second
                            aggregate.third.forEach { (day, count) ->
                                pendingRetiredHeatmap = pendingRetiredHeatmap + (day to ((pendingRetiredHeatmap[day] ?: 0) + count))
                            }
                            conversationDrafts.clear()
                            conversationsById = emptyMap()
                            conversationTitles = emptyMap()
                            conversationUpdatedAt = emptyMap()
                            conversationCreatedAt = emptyMap()
                            conversationFolderIds = emptyMap()
                            conversationPinned = emptySet()
                            conversationCompletionMarkers = emptySet()
                            fileAttachmentOwnerVersion += 1
                            beginNewSubAgentDraft()
                            selectedConversationId = null
                            pendingNewConversationFolderId = selectedFolderId
                            homeState = newDraftChatState()
                            conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
                            refreshConversationSummaries()
                        }
                        ids.forEach { id ->
                            runCatching { chatImageCache.deleteConversation(id) }
                                .onFailure { AndroidAgentLogger.warn("图片缓存清理失败：${it.javaClass.simpleName}") }
                            runCatching { io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, id).delete() }
                                .onFailure { AndroidAgentLogger.warn("压缩原文清理失败：${it.javaClass.simpleName}") }
                        }
                    }
                }
                // Retired counters are written only after deletion has durably succeeded.
                persistConversations()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Delete all conversations failed: ${failure.safeLogType()}")
                Toast.makeText(appContext, "删除未完成，请等待任务结束或稍后重试。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        if (rejectConversationArchiveMutation()) return
        val wasSelected = selectedConversationId == conversationId
        conversationState(conversationId)?.let { state ->
            retainDeletedConversation(state.messages, conversationUpdatedAt[conversationId])
        }
        conversationDrafts.remove(conversationId)
        conversationsById = conversationsById - conversationId
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        conversationCreatedAt = conversationCreatedAt - conversationId
        conversationFolderIds = conversationFolderIds - conversationId
        conversationPinned = conversationPinned - conversationId
        conversationCompletionMarkers = conversationCompletionMarkers - conversationId
        scope.launch(Dispatchers.IO) { chatImageCache.deleteConversation(conversationId) }
        if (wasSelected) {
            fileAttachmentOwnerVersion += 1
            val nextId = conversationsById.keys.firstOrNull()
            if (nextId != null) {
                selectedConversationId = nextId
                conversationCompletionMarkers = conversationCompletionMarkers - nextId
                homeState = requireNotNull(conversationState(nextId))
                conversationsById = conversationsById + (nextId to homeState)
            } else {
                beginNewSubAgentDraft()
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
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
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
        if (!ensureSubAgentConfigurationReady()) return
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
            if (!subAgentDraftReady) return
        }

        val editBoundary = edit?.let {
            if (it.preparedFromHistory != null && it.preparedFromHistory != homeState.history) return@let null
            AgentConversationRevisionReducer.boundary(
                homeState.copy(history = it.preparedHistory ?: homeState.history), it.targetMessageId,
            )
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
            if (!bindSubAgentDraft(id)) return
            conversationDrafts.promote(id)
            selectedConversationId = id
            conversationPaneState = conversationPaneState.copy(selectedConversationId = id)
            // The pending draft callback is intentionally stale; replace it for the bound owner.
            refreshRequestOverhead()
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
                    // 视觉模型看得到画面，但也要拿到落盘路径：否则改图标、转存这类文件操作
                    // 只能去猜路径，曾因此在别的会话缓存里挑中旧截图。气泡按媒体路径去重，不会多出文件卡片。
                    val extraFiles = staged.filterNotNull()
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
            logicalTurnId = editBoundary?.logicalTurnId ?: runId,
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

    /** One unpublished transaction: IO may only produce a candidate, never mutate the source. */
    private fun launchConversationRevision(
        messageId: String,
        allowActiveSource: Boolean = false,
        publish: suspend (String, AgentChatHomeUiState, AgentChatHomeUiState, () -> Boolean) -> Unit,
    ) {
        if ((!allowActiveSource && (homeState.isStreaming || homeState.isPaused)) || homeState.messageEdit != null) return
        if (rejectConversationArchiveMutation() || rejectSendIfCompressing()) return
        if (modelPickerState.isChanging || runtimeRecoveryInProgress.get()) return
        val conversationId = selectedConversationId ?: return
        if (!allowActiveSource && runConversationIds.any { (run, owner) -> owner == conversationId && run in runJobs }) return
        val snapshot = homeState
        val runningBranch = allowActiveSource && (snapshot.isStreaming || snapshot.isPaused ||
            runConversationIds.any { (run, owner) -> owner == conversationId && run in runJobs })
        // Capture the request token and already-consumed TEXT with the click, before any suspension.
        val branchBoundary = if (runningBranch) runConversationIds.entries
            .singleOrNull { it.value == conversationId && it.key in runJobs }
            ?.key?.let { branchRequestBoundaries[it] } else null
        val selection = conversationSelectionVersion
        val modelGeneration = modelBindingGeneration
        val assistant = AssistantRepository.active().id
        val draft = currentDraftField().text.toString()
        val stillCurrent = {
            selectedConversationId == conversationId && conversationSelectionVersion == selection &&
                modelBindingGeneration == modelGeneration && AssistantRepository.active().id == assistant &&
                (runningBranch || (homeState == snapshot && conversationState(conversationId) == snapshot)) &&
                conversationState(conversationId) != null && currentDraftField().text.toString() == draft &&
                (allowActiveSource || (!homeState.isStreaming && !homeState.isPaused)) &&
                !modelPickerState.isChanging && !runtimeRecoveryInProgress.get() && !isCompressionBlockingSend() &&
                !conversationArchiveBusy && !io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance &&
                stoppingRuns.keys.none { runConversationIds[it] == conversationId } &&
                (allowActiveSource || runConversationIds.none { (run, owner) -> owner == conversationId && run in runJobs })
        }
        conversationRevisionBusy = true
        // UNDISPATCHED installs finally even if the owner scope is cancelled before IO starts.
        scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            try {
                yield()
                val prepared = runInterruptible(Dispatchers.IO) {
                    val archive = io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, conversationId)
                    val ordinary = AgentConversationRevisionReducer.prepareForRevision(snapshot, messageId, archive::restoreHistory)
                    if (ordinary != null || branchBoundary == null) ordinary else {
                        val runtime = branchHistorySnapshotLoader(conversationId, branchBoundary.runId, branchBoundary.snapshotId)
                        if (runtime == null || runtime.id != branchBoundary.snapshotId || runtime.round != branchBoundary.round) null else {
                            val candidate = AgentRunningBranchSnapshot.prepare(snapshot, messageId,
                                branchBoundary.runId, branchBoundary.round, runtime.history, branchBoundary.textBaseline)
                            candidate?.let { AgentConversationRevisionReducer.prepareForRevision(it, messageId, archive::restoreHistory) }
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (!stillCurrent()) return@launch
                if (prepared == null) {
                    showRevisionHistoryUnavailableNotice()
                    return@launch
                }
                publish(conversationId, snapshot, prepared, stillCurrent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                coroutineContext.ensureActive()
                val reason = (failure.cause ?: failure).message.orEmpty().takeIf {
                    it in setOf("分支归档引用层数超限", "分支归档存在循环引用", "分支根归档缺失或无效",
                        "分支归档数量超限", "旧分支归档扫描数量超限", "旧分支归档副本数量超限",
                        "旧分支归档副本不一致", "缺失的旧分支归档没有可验证副本")
                }.orEmpty()
                AndroidAgentLogger.warn("会话修订失败：${if (failure is BranchArchiveCopyException) "branch_archive_copy" else "revision_prepare"} ${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName} $reason")
                if (stillCurrent()) {
                    if (failure is BranchArchiveCopyException) Toast.makeText(appContext,
                        "分支所需的历史归档或附件未能完整复制，已取消分支；原会话未修改。", Toast.LENGTH_LONG).show()
                    else showRevisionHistoryUnavailableNotice()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { conversationRevisionBusy = false }
            }
        }
    }

    fun beginMessageEdit(messageId: String) {
        launchConversationRevision(messageId) { _, snapshot, prepared, _ ->
            val boundary = AgentConversationRevisionReducer.boundary(prepared, messageId) ?: run {
                showRevisionHistoryUnavailableNotice()
                return@launchConversationRevision
            }
            applyPreparedMessageEdit(snapshot, prepared, boundary)
        }
    }

    private fun applyPreparedMessageEdit(
        snapshot: AgentChatHomeUiState,
        prepared: AgentChatHomeUiState,
        boundary: AgentConversationRevisionReducer.Boundary,
    ) {
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
        // 图片自己的落盘路径也写在消息的文件段里（给模型用）。编辑时它们已经作为图片回填，
        // 再当文件卡片回填会重复，重发时还会发两次。
        val fileReferences = boundary.userMessage.visibleFileReferences(parsedPrompt.references).mapIndexed { index, reference ->
            PendingFileReferenceUi(
                id = "edit-${boundary.userMessage.id}-file-$index",
                reference = reference,
            )
        }
        updateCurrentConversation(
            snapshot.copy(
                input = parsedPrompt.request,
                pendingImages = images,
                pendingFileReferences = fileReferences,
                pendingConversationMentions = parsedPrompt.conversations.map { mentioned ->
                    PendingConversationMentionUi("mention-${UUID.randomUUID()}", mentioned.id, mentioned.title, "", mentioned.snapshotPath, mentioned.toolsIndexPath)
                },
                messageEdit = MessageEditUiState(
                    targetMessageId = boundary.userMessage.id,
                    previousInput = currentDraftField().text.toString(),
                    previousImages = homeState.pendingImages,
                    previousFileReferences = homeState.pendingFileReferences,
                    previousConversationMentions = homeState.pendingConversationMentions,
                    hasLaterTurns = boundary.laterTurnCount > 0,
                    preparedHistory = prepared.history,
                    preparedFromHistory = snapshot.history,
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

    fun messageRevisionImpact(messageId: String): MessageRevisionImpact? {
        // Confirmation is UI-only. A compacted target is validated/restored later on IO.
        val target = homeState.messages.indices.singleOrNull { homeState.messages[it].id == messageId } ?: return null
        val user = (target downTo 0).firstOrNull { homeState.messages[it] is UserMessageUi } ?: return null
        return MessageRevisionImpact(laterTurnCount = homeState.messages.drop(user + 1).count {
            it is UserMessageUi && !it.isSteerSupplement()
        })
    }

    /** 删除确认框：按操作栏分段，统计目标段下面会一起删掉的段数。 */
    fun messageDeleteImpact(messageId: String): MessageRevisionImpact? =
        AgentConversationRevisionReducer.laterSegmentCount(homeState, messageId)?.let { count ->
            MessageRevisionImpact(laterTurnCount = count)
        }

    fun deleteMessageTurn(messageId: String) {
        launchConversationRevision(messageId) { conversationId, snapshot, prepared, _ ->
            val revised = AgentConversationRevisionReducer.deleteFromTurn(prepared, messageId) ?: run {
                showRevisionHistoryUnavailableNotice()
                return@launchConversationRevision
            }
            applyPreparedMessageDeletion(conversationId, snapshot, revised)
        }
    }

    private fun applyPreparedMessageDeletion(
        conversationId: String,
        snapshot: AgentChatHomeUiState,
        revised: AgentChatHomeUiState,
    ) {
        if (revised.messages.isEmpty()) {
            retainDeletedConversation(snapshot.messages, conversationUpdatedAt[conversationId])
            conversationsById = conversationsById - conversationId
            conversationTitles = conversationTitles - conversationId
            conversationUpdatedAt = conversationUpdatedAt - conversationId
        conversationCreatedAt = conversationCreatedAt - conversationId
            conversationFolderIds = conversationFolderIds - conversationId
            conversationPinned = conversationPinned - conversationId
            conversationCompletionMarkers = conversationCompletionMarkers - conversationId
            scope.launch(Dispatchers.IO) { chatImageCache.deleteConversation(conversationId) }
            fileAttachmentOwnerVersion += 1
            beginNewSubAgentDraft()
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

    private class BranchArchiveCopyException(cause: Exception) : Exception(cause)

    /** Old completed replies may branch while a different run generates, but not that run's partial text. */
    private fun isUnfinishedAssistantBranchTarget(messageId: String): Boolean {
        val target = homeState.messages.singleOrNull { it.id == messageId } as? AgentMessageUi ?: return false
        if (target.isStreaming) return true
        val owner = selectedConversationId ?: return false
        val activeRuns = runConversationIds.filter { (run, conversation) -> conversation == owner && run in runJobs }.keys
        if (!homeState.isStreaming && !homeState.isPaused && activeRuns.isEmpty()) return false
        // A run can finish several text/provider rounds before the reply itself finishes.
        // Branch-copied message IDs retain the execution ID after their conversation prefix.
        val id = target.id.substringAfterLast(':')
        return activeRuns.any { run -> id == "assistant-$run" || id.startsWith("assistant-$run-") }
    }

    fun branchConversation(messageId: String) {
        if (isUnfinishedAssistantBranchTarget(messageId)) return
        launchConversationRevision(messageId, allowActiveSource = true) { sourceId, snapshot, prepared, stillCurrent ->
            val prefix = AgentConversationRevisionReducer.branchPrefix(prepared, messageId) ?: run {
                showRevisionHistoryUnavailableNotice()
                return@launchConversationRevision
            }
            if (prefix.messages.isEmpty()) return@launchConversationRevision
            val newId = newConversationId()
            var published = false
            try {
                runInterruptible(Dispatchers.IO) {
                    // Retained A and its tool/checkpoint closure must be independent of the source.
                    try {
                        io.github.mangi.eta.agent.model.AgentCompactionArchiveFork.copyReferenced(
                            appContext.filesDir, sourceId, newId, prefix.history,
                            rewriteAttachmentPath = { value -> chatImageCache.rewriteCachedPath(value, sourceId, newId) },
                            recoverLegacyDependencies = true,
                        )
                        chatImageCache.copyConversation(sourceId, newId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (interrupted: InterruptedException) {
                        throw interrupted
                    } catch (failure: Exception) {
                        throw BranchArchiveCopyException(failure)
                    }
                }
                coroutineContext.ensureActive()
                if (!stillCurrent()) return@launchConversationRevision
                publishPreparedBranch(sourceId, newId, snapshot, prefix) { published = true }
            } finally {
                if (!published) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, newId).delete() }
                        .onFailure { AndroidAgentLogger.warn("未发布分支归档清理失败：${it.javaClass.simpleName}") }
                    runCatching { chatImageCache.deleteConversation(newId) }
                        .onFailure { AndroidAgentLogger.warn("未发布分支附件清理失败：${it.javaClass.simpleName}") }
                }
            }
        }
    }

    private fun publishPreparedBranch(
        sourceId: String,
        newId: String,
        snapshot: AgentChatHomeUiState,
        prefix: AgentConversationRevisionReducer.BranchPrefix,
        onPublished: () -> Unit,
    ) {
        val rewrite = { value: String -> chatImageCache.rewriteCachedPath(value, sourceId, newId) }
        val branchMessages = runMessageProjector.interruptRunningTools(
            "分支保留点击时的工具状态，后续执行仍属于原会话。", freezeStreamingMessages(prefix.messages),
        ).map { message ->
            val frozen = message.withId("$newId:${message.id}").rewritePaths(rewrite)
            if (frozen is AgentQuestionMessageUi) AgentQuestionProjection.freezeForBranch(frozen, newId)
            else frozen
        }
        val branched = snapshot.copy(
            messages = branchMessages,
            isWaitingForAnswer = AgentQuestionProjection.hasWaiting(branchMessages),
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
            activeRunContextWindow = null,
            childContexts = emptyList(), childStatusRoster = emptyList(),
            childContextRunId = "", selectedContextTaskId = null,
            messageEdit = null,
            livePromptTokens = null,
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
        )
        val sourceTitle = conversationTitles[sourceId].orEmpty().ifBlank {
            appContext.getString(R.string.conversation_unnamed)
        }
        // Preferences may fail durably; finish them before making any branch visible.
        conversationSubAgentPreferences.createConversation(
            SubAgentConfigKey.Conversation(newId), SubAgentConfigKey.Conversation(sourceId),
        )
        conversationsById = conversationsById + (newId to branched)
        conversationTitles = conversationTitles + (
            newId to appContext.getString(R.string.conversation_branch_title, sourceTitle)
        )
        conversationUpdatedAt = conversationUpdatedAt + (newId to System.currentTimeMillis())
        conversationCreatedAt = conversationCreatedAt + (newId to System.currentTimeMillis())
        conversationFolderIds[sourceId]?.let { folderId ->
            conversationFolderIds = conversationFolderIds + (newId to folderId)
        }
        fileAttachmentOwnerVersion += 1
        selectedConversationId = newId
        homeState = branched
        billedOverheadConversationId = newId
        billedOverheadTokens = null
        conversationPaneState = conversationPaneState.copy(selectedConversationId = newId)
        onPublished() // No suspensions between the validated snapshot and this publication boundary.
        refreshConversationSummaries()
        persistConversations()
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
            }, parsed.conversations.map { PendingConversationMentionUi(it.id, it.id, it.title, "", it.snapshotPath, it.toolsIndexPath) })) {
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
                    parsed.references + extra,
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
                    // 这次补了落盘路径（旧消息的信封里没有），历史里的用户消息文本随之变化。
                    // 界面里的同一条消息也要改成同样的内容和路径，否则之后编辑、重生成、分支、删除
                    // 都按文本对不上历史，这一轮就改不动了。
                    val retainedMessages = homeState.messages.take(boundary.userMessageIndex + 1)
                    val messages = if (extra.isEmpty()) retainedMessages else {
                        retainedMessages.mapIndexed { index, message ->
                            if (index != boundary.userMessageIndex || message !is UserMessageUi) message
                            else message.copy(
                                content = runtimePrompt,
                                imageSources = if (extra.size == message.images.size) {
                                    extra.map { it.absolutePath }
                                } else message.imageSources,
                            )
                        }
                    }
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
                        messages = messages,
                        state = homeState,
                        reasoningEffort = homeState.reasoningEffort,
                        skipAutoCompress = ignoreCompression,
                        logicalTurnId = boundary.logicalTurnId,
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
            logicalTurnId = boundary.logicalTurnId,
        )
    }


    private fun rejectSendIfContextWindowExceeded(
        history: List<AgentModelClient.ConversationMessage>,
        prompt: String,
        images: List<PendingImageUi>,
        fileReferences: List<PendingFileReferenceUi> = emptyList(),
        conversationMentions: List<PendingConversationMentionUi> = homeState.pendingConversationMentions,
    ): Boolean {
        // 普通历史前缀增长、补齐可见 assistant、turnId 元数据差都复用同一次云端实测
        // （contextStateForRequestHistory）；只有编辑/改写历史或换路由才会退化成未实测。
        val requestState = contextStateForRequestHistory(homeState, history)
        val measured = if (homeState.messageEdit == null) validMeasuredContextTokens(requestState) else null
        val usage = compressionContextUsage(
            history = history,
            currentInput = prompt,
            pendingImages = images,
            selectedModel = modelPickerState.selectedModel,
            pendingFileReferences = fileReferences,
            pendingConversationMentions = conversationMentions,
            billedContextTokens = measured,
            requestOverheadTokens = requestOverheadTokens,
            billedOverheadTokens = requestState.cloudRequestOverheadTokens.takeIf { measured != null },
            billedHistoryTokens = requestState.cloudHistoryTokens.takeIf { measured != null },
        )
        // 真未知不能仅凭本地估算的 99% 拦截；known（有效实测）保留原有拦截与 autoCompress 短路。
        if (measured == null || !shouldBlockSendForContextWindow(autoCompressEnabled, usage)) {
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
        state.livePromptTokens.takeUnless { state.livePromptIsProjected }

    /** Ordinary commits keep the latest actual; edits/truncation open a new receipt epoch. */
    private fun contextStateForRequestHistory(
        state: AgentChatHomeUiState,
        history: List<AgentModelClient.ConversationMessage>,
    ): AgentChatHomeUiState {
        // turnId is local metadata (runtime drain can omit it), not provider request content.
        fun matchesRequestPrefix(prefix: List<AgentModelClient.ConversationMessage>): Boolean =
            prefix.size <= history.size && prefix.indices.all { index ->
                prefix[index].copy(turnId = "") == history[index].copy(turnId = "")
            }
        if (matchesRequestPrefix(state.history)) return state
        // Only the real send reducer's exact visible-assistant commit may complete old text.
        // Arbitrary content startsWith matches would incorrectly accept genuine history edits.
        val committedHistory = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
            state.history, state.messages)
        if (matchesRequestPrefix(committedHistory)) return state
        return state.copy(
            livePromptTokens = null, livePromptIsProjected = false,
            cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
            contextAwaitingReceipt = true, contextHasStarted = true,
            cloudReceiptRequestId = null, cloudRouteSignature = null,
            contextReceiptEvidence = null, receiptPredictionTokens = null,
        )
    }

    /** 预算 seed 与未测豁免共用同一个有效实际回执判定：custom gateway 的真实回执也能继续
     * 作为同一路由/模型的预算锚点，不再被已退场的 learning 签名拦成“未测”。 */
    private fun budgetReceiptTokens(state: AgentChatHomeUiState): Int? =
        (billedPromptTokens(state) ?: state.contextBudgetReceiptTokens)
            .takeIf { hasEffectiveContextReceipt(state) }

    private fun compressionContextWindow(fallback: Int? = null): Int? =
        modelPickerState.selectedModel?.contextWindow?.takeIf { it > 0 }
            ?: fallback?.takeIf { it > 0 }

    /** Window of the model the conversation is bound to; the picker may show another model. */
    private fun boundCompressionWindow(state: AgentChatHomeUiState): Int? =
        AgentModelPickerProjector.project(selectionProviders, state.providerId, state.modelId).selectedModel
            ?.takeIf { it.providerId == state.providerId && it.id == state.modelId }?.contextWindow?.takeIf { it > 0 }
            ?: compressionContextWindow()

    /** Same fallback AgentLoop uses when a model has no configured window. */
    private fun compressionBoundaryWindow(window: Int?): Int =
        window?.takeIf { it > 0 } ?: io.github.mangi.eta.agent.model.AgentLoop.CompactPolicy.Disabled.contextWindow

    private fun shouldAutoCompress(
        history: List<AgentModelClient.ConversationMessage>,
        contextWindow: Int?,
        estimatedTokens: Int?,
    ): Boolean {
        if (!autoCompressPreference.enabled) return false
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
        billedTokens: Int? = null,
        localTokens: Int? = null,
        sourceModelConfig: AgentModelClient.ModelConfig? = null,
        /** 仅当本次确实产生了可用的摘要并已 ready 成功时触发；prune-only/摘要失败不触发。 */
        onSummaryCommitted: (() -> Unit)? = null,
    ): List<AgentModelClient.ConversationMessage> {
        val resolvedKeepRecent = keepRecent ?: keepRecentFor()
        val archive = conversationId?.let {
            io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, it)
        }
        val window = compressionBoundaryWindow(contextWindow)
        val config = AgentContextCompactor.Config(
            keepRecentMessages = coerceKeepRecent(resolvedKeepRecent),
            compressModelConfig = compressModelConfig,
            compactionArchive = archive,
            usageConversationId = conversationId,
            sourceModelConfig = sourceModelConfig?.copy(contextWindow = window),
            summaryTokenBudget = io.github.mangi.eta.agent.model.AgentCompressionBoundary.summaryProgressBudget(window),
        )
        return try {
            var summaryFailure: String? = null
            val compressed = runInterruptible {
                // billed/local lets the 16% tail target the provider's bill, as in AgentLoop.
                val initialCut = io.github.mangi.eta.agent.model.AgentCompressionBoundary.selectStart(
                    history, window, billedTokens = billedTokens, localTokens = localTokens,
                    opaqueItems = config::replayedOpaqueItems)
                val working = AgentContextCompactor.pruneOversizedToolResults(history, archive, initialCut)
                val cut = io.github.mangi.eta.agent.model.AgentCompressionBoundary.selectStart(
                    working, window, billedTokens = billedTokens, localTokens = localTokens,
                    opaqueItems = config::replayedOpaqueItems)
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
                    require(io.github.mangi.eta.agent.model.AgentCompressionBoundary.compactionReduced(
                        history, result, working.size - cut, config.progressBudget(),
                        countsOpaque = { config.replayedOpaqueItems(it) > 0 },
                    )) { "摘要及索引未缩小上下文" }
                    boundArchive.record(id, "ready")
                    // attachReferences + compactionReduced + ready 全部成功之后才宣告摘要提交成功。
                    onSummaryCommitted?.invoke()
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
        return AgentCompressionEndpoint.apply(compressed, prefs?.getString(endpointKey, null)).copy(
            errorReconnectPolicy = fallback?.errorReconnectPolicy
                ?: SettingsDataStore.settings().errorReconnectPolicy.persistedValue,
        )
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
        val runProvider = selectionProviders.singleOrNull { it.id == state.providerId }?.takeIf { it.isEnabled }
        val runModel = runProvider?.models?.singleOrNull { it.id == state.modelId }?.takeIf { it.isEnabled }
        if (runProvider == null || runModel == null) {
            Toast.makeText(appContext, "绑定模型已不可用，未发送。请重新选择。", Toast.LENGTH_LONG).show()
            return
        }
        if (runModel.supportsSpeechSynthesis) {
            Toast.makeText(appContext, "语音合成模型请在朗读设置中使用，不能执行对话任务", Toast.LENGTH_LONG).show()
            return
        }
        if (consumeDraft) conversationDrafts.replace(conversationId, "")
        val runAssistant = requestOverheadAssistant(state)
        // Resolve and freeze the remembered mode for THIS submitted binding before any coroutine.
        // Later UI toggles must not mutate an in-flight request or affect a different model.
        val runConfig = GptSpeedModePolicy.snapshot(
            RuntimeConfigRepository.buildRuntimeConfig(runProvider, runModel, runAssistant),
            rememberedGptSpeedMode(state.providerId, state.modelId),
            supportsGptSpeedBinding(runProvider, runModel),
        )
        val runModelOption = AgentModelPickerProjector.project(listOf(runProvider), runProvider.id, runModel.id).selectedModel
        val runProviders = selectionProviders.toList()
        val taggedUserHistoryMessage = userHistoryMessage.copy(turnId = logicalTurnId)
        bindUsageRun(runId, conversationId)
        runConfig.contextWindow?.takeIf { it > 0 }?.let { runContextWindows[runId] = it }
        val generateVideo = runModel.supportsVideoGeneration
        val generateImage = !generateVideo && runModel.supportsImageGeneration
        val mediaController = if (generateImage || generateVideo) {
            imageGenerationRunIds += runId
            directMediaRuns.start(runId)
        } else null

        // 自动压缩只看圆环：同一段历史的云端实测。没有实测（首次请求、压缩后、换模型）不压缩。
        val willCompress = !generateImage && !generateVideo && !skipAutoCompress && shouldAutoCompress(
            history = history,
            contextWindow = runConfig.contextWindow,
            estimatedTokens = if (history == state.history) billedPromptTokens(state) else null,
        )
        val historyRewritten = history != state.history
        val relatedHistory = history == state.history.take(history.size) || state.history == history.take(state.history.size)
        // 同一有效实际回执判定：custom gateway 的真实回执同样可以作为压缩/预算锚点。
        val validReceiptRoute = hasEffectiveContextReceipt(state)
        val contextState = contextStateForRequestHistory(state, history)
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
            contextState.copy(
                isStreaming = true,
                isPaused = false,
                // Keep the latest actual until a new valid usage replaces it. Estimates are only
                // for contexts without a cloud receipt, never a delta replacement for an actual.
                receiptPredictionTokens = null,
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
                errorReconnectPolicy = SettingsDataStore.settings().errorReconnectPolicy.persistedValue,
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
            // Recompute from THIS run's frozen model/assistant, never a positive global UI value.
            val runOverhead = try {
                estimateRequestOverhead(config, runAssistant, SubAgentConfigKey.Conversation(conversationId), runProviders)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (runOverhead != null && runId in runJobs && !stoppingRuns.containsKey(runId)) {
                    runOverheadTokens[runId] = runOverhead
                }
            }
            val supportsVideo = config.supportsVideo
            val modelImages = images.map { p ->
                p.toOutboundModelImage(supportsVideo).copy(source = "user_attach")
            }
            val compressModelConfig = resolveCompressModelConfig(config)
            val billedForCompression = if (!historyRewritten && validReceiptRoute) billedPromptTokens(state) else null
            // Related same-route history may reuse an old receipt as a DELTA anchor, never as a new actual.
            // It does not become an 80% auto-compression trigger for a rewritten request.
            val receiptAnchorForDelta = budgetReceiptTokens(state).takeIf {
                validReceiptRoute && relatedHistory && !state.contextAwaitingReceipt
            }
            val calibratedForCompression = receiptAnchorForDelta != null && receiptAnchorForDelta > 0 &&
                runOverhead != null && state.cloudHistoryTokens != null && state.cloudRequestOverheadTokens != null
            val estimatedTokens = runOverhead?.let { overhead ->
                compressionContextUsage(
                    history = history,
                    currentInput = prompt,
                    pendingImages = images,
                    selectedModel = runModelOption,
                    billedContextTokens = receiptAnchorForDelta,
                    requestOverheadTokens = overhead,
                    billedOverheadTokens = state.cloudRequestOverheadTokens,
                    billedHistoryTokens = state.cloudHistoryTokens,
                ).contextTokens
            }
            // Same request in local units, so the retained tail can be scaled to the bill.
            // An unknown overhead cannot establish a calibration or overwrite a valid receipt.
            val localForCompression = runOverhead?.takeIf { billedForCompression != null }?.let { overhead ->
                compressionContextUsage(history = history, currentInput = prompt, pendingImages = images,
                    selectedModel = runModelOption, requestOverheadTokens = overhead).contextTokens
            }
            val pendingInRunCompact = withContext(Dispatchers.Main) {
                pendingInRunCompactConversationIds.remove(conversationId)
            }
            val shouldCompress = !skipAutoCompress && (
                pendingInRunCompact ||
                    shouldAutoCompress(
                        history,
                        config.contextWindow,
                        // 与圆环同一个数；estimatedTokens 只用于压缩时按账单缩放保留尾部。
                        billedForCompression,
                    )
            )
            if (shouldCompress != willCompress) {
                withContext(Dispatchers.Main) {
                    setConversationCompressing(conversationId, shouldCompress)
                }
            }
            var summaryCommitted = false
            val historyToSend = if (shouldCompress) {
                val compressed = tryCompressHistory(
                    history = history,
                    compressModelConfig = compressModelConfig,
                    sourceModelConfig = config,
                    conversationId = conversationId,
                    contextWindow = config.contextWindow,
                    billedTokens = estimatedTokens.takeIf { billedForCompression != null },
                    localTokens = localForCompression,
                    onSummaryCommitted = { summaryCommitted = true },
                )
                withContext(Dispatchers.Main) {
                    applyCompressedHistoryToConversation(
                        conversationId = conversationId,
                        originalHistory = history,
                        compressedHistory = compressed,
                        userHistoryMessage = taggedUserHistoryMessage,
                        compressorLabel = compressorLabel(compressModelConfig),
                        summaryCommitted = summaryCommitted,
                    )
                    setConversationCompressing(conversationId, false)
                }
                compressed
            } else {
                history
            }
            // 只有摘要真正成功才算“已预压缩”：prune-only/摘要失败返回 working 时历史也可能不等，
            // 不能拿 history != historyToSend 冒充压缩成功。
            val compactedBeforeSend = shouldCompress && summaryCommitted
            // Pre-send compaction can outlive the 20s stop seal: the watchdog then settles the
            // run and drops it from runJobs. Re-check before handing anything to Runtime, or a
            // stopped run would start executing tools with no UI left to stop it.
            val stillWanted = withContext(Dispatchers.Main.immediate) {
                when {
                    stoppingRuns.containsKey(runId) -> {
                        applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "已停止"))
                        false
                    }
                    runId !in runJobs -> false
                    else -> true
                }
            }
            if (!stillWanted) return@launch
            // Diagnostics only: numeric snapshot of the legacy basis the calibration is measured in.
            // Never on Main, never allowed to interrupt the send path.
            if (AppFileLogger.isEnabled()) {
                try {
                    val snapshotOverhead = runOverhead ?: 0
                    val hasReceiptDelta = !compactedBeforeSend && calibratedForCompression
                    val rawHistory = (historyToSend.sumOf { AgentContextBudget.countMessage(it).toLong() } +
                        AgentContextBudget.countCurrentTurn(prompt, modelImages))
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val estimate = if (hasReceiptDelta) estimatedTokens ?: 0 else
                        (rawHistory.toLong() + snapshotOverhead).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val uiActual = billedPromptTokens(contextState).takeIf {
                        !compactedBeforeSend && contextState.cloudRouteSignature != null &&
                            contextState.cloudRouteSignature == contextRouteSignature(contextState)
                    }
                    val uiTokens = if (contextState.contextAwaitingReceipt) null else uiActual
                    contextEstimateDiagnostics.capture(runId, ContextEstimateDiagnostics.Snapshot(
                        basis = if (hasReceiptDelta) ContextEstimateDiagnostics.Basis.RECEIPT_DELTA
                            else ContextEstimateDiagnostics.Basis.LOCAL_FALLBACK,
                        localEstimateTokens = estimate, overheadTokensEst = snapshotOverhead, historyTokensEst = rawHistory,
                        uiTokens = uiTokens,
                        uiState = when {
                            uiActual != null -> ContextEstimateDiagnostics.UiState.ACTUAL
                            !state.contextHasStarted -> ContextEstimateDiagnostics.UiState.NONE
                            else -> ContextEstimateDiagnostics.UiState.UNKNOWN
                        },
                    ))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A diagnostic failure must never block the run.
                }
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
                        // 只在估算确实以同一段历史的云端回执为底、且没有真正压缩掉这段历史时传。
                        // 摘要尝试失败/仅 prune 不清掉仍然有效的旧锚点。
                        calibratedInputTokens = estimatedTokens?.takeIf {
                            !compactedBeforeSend && calibratedForCompression && it > 0
                        },
                        // 只有实际未知（本次请求没有有效实测）或摘要真正提交过才放行；
                        // known 一律沿用既有校准锚点，不免除 Runtime 的本地硬限。
                        allowUnmeasuredContextSend = unmeasuredContextSendAllowed(contextState, compactedBeforeSend),
                        modelSessionId = conversationId,
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = runId,
                            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                            payload = conversationId,
                        ),
                    ),
                    onEvent = { event -> enqueueRunEvent(runId, event) },
                    isStopRequested = { stoppingRuns.containsKey(runId) },
                    mainStopReason = { mainStopReasons[runId] ?: AgentChildControlPolicy.Reason.USER_STOP },
                )
            }
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result, acknowledgeRuntimeResult = true, petReaction = true)
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
                val current = conversationState(conversationId)
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
        branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId); invalidatedUsageRuns.remove(runId)
        runOverheadTokens.remove(runId); runContextWindows.remove(runId)
        contextEstimateDiagnostics.clear(runId)
        runCompressedDuringRun.remove(runId)
        refreshConversationSummaries()
        persistConversations()
        if (conversationId != null) {
            val petTitle = conversationTitles[conversationId].orEmpty()
            val petContext = appContext
            scope.launch(Dispatchers.IO) {
                io.github.mangi.eta.agent.pet.WhaleMaidController.onWorkDone(petContext, petTitle)
            }
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
        branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId); invalidatedUsageRuns.remove(runId)
        runOverheadTokens.remove(runId); runContextWindows.remove(runId)
        contextEstimateDiagnostics.clear(runId)
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
        summaryCommitted: Boolean = false,
    ) {
        if (compressedHistory == originalHistory) return
        val current = conversationState(conversationId) ?: return
        val expected = originalHistory + userHistoryMessage
        if (current.history.map { it.copy(turnId = "") } != expected.map { it.copy(turnId = "") }) return
        if (!summaryCommitted) {
            // Pruning or a failed summary only changes the silent budget delta, not this receipt epoch.
            updateConversation(conversationId, current.copy(
                history = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(compressedHistory) + userHistoryMessage,
                isCompressingContext = false, isWaitingForCompression = false,
            ))
            persistConversations()
            return
        }
        updateConversation(
            conversationId,
            current.copy(
                isCompressingContext = false,
                isWaitingForCompression = false,
                history = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(compressedHistory) + userHistoryMessage,
                livePromptTokens = null,
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
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

    /** Called on Main only after an accepted history update, never from replay/projection.
     *  The notice belongs to the conversation that finished, even if the user has switched away. */
    private fun showCompressionCompletedToast(
        conversationId: String?,
        originalHistory: List<AgentModelClient.ConversationMessage>,
        compressedHistory: List<AgentModelClient.ConversationMessage>,
        compressorLabel: String,
    ) {
        val count = AgentContextCompactionUi.completedMessageCount(
            originalHistory, compressedHistory, compressorLabel,
        )
        if (count <= 0) return
        val body = appContext.resources.getQuantityString(R.plurals.context_compacted_messages, count, count)
        val title = conversationId?.let { conversationTitles[it] }?.trim().orEmpty()
            .let { raw -> if (raw.length <= 3) raw else raw.take(3) + "…" }
        val text = if (conversationId != selectedConversationId && title.isNotBlank()) "$title：$body" else body
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
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
        val sourceSnapshot = conversationsById[conversationId] ?: return false
        if (pending.size >= ConversationMention.MAX_ATTACHED) {
            Toast.makeText(appContext, "最多引用 3 个会话。", Toast.LENGTH_SHORT).show()
            return false
        }
        val status = if (sourceSnapshot.isStreaming) "[选择时快照：来源会话仍在运行，未包含后续输出]\n" else ""
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
                val prepared = withContext(Dispatchers.IO) {
                    val source = if (sourceSnapshot.conversationContentLoaded) sourceSnapshot else
                        requireNotNull(AgentConversationStore.loadConversation(appContext, conversationId))
                    val evidence = io.github.mangi.eta.ui.model.ConversationToolEvidence(source.messages)
                    evidence.add(source.history, "source conversation model history")
                    io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, conversationId)
                        .visitForConversationMention(evidence::add)
                    val toolFiles = mutableListOf<String>()
                    val body = status + ConversationMention.transcript(
                        source.messages, ConversationMention.SNAPSHOT_MAX_CHARS - status.length,
                        appContext.filesDir, conversationId, evidence, toolFiles,
                    )
                    val snapshot = ConversationMention.writeSnapshot(appContext.filesDir, conversationId, body)
                    val index = snapshot?.let { ConversationMention.writeToolIndex(it, toolFiles) }
                    Triple(snapshot?.absolutePath.orEmpty(), index?.absolutePath.orEmpty(), body)
                }
                if (ownerVersion != fileAttachmentOwnerVersion) return@launch
                if (conversationId !in conversationsById) {
                    removeConversationMention(mentionId)
                    return@launch
                }
                val current = homeState.pendingConversationMentions
                if (current.none { it.id == mentionId }) return@launch
                val (snapshotPath, toolsIndexPath, _) = prepared
                if (snapshotPath.isBlank()) {
                    removeConversationMention(mentionId)
                    Toast.makeText(appContext, "会话引用为空或快照没写上，请重新选择。", Toast.LENGTH_SHORT).show()
                } else {
                    updateCurrentConversation(homeState.copy(pendingConversationMentions = current.map {
                        if (it.id == mentionId) it.copy(transcript = "", snapshotPath = snapshotPath, toolsIndexPath = toolsIndexPath) else it
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

    private fun stopRun(
        runId: String,
        keepChildren: Boolean = true,
        reason: AgentChildControlPolicy.Reason = AgentChildControlPolicy.Reason.USER_STOP,
    ) {
        if (activeRunIdForSelectedConversation() == runId) io.github.mangi.eta.agent.voice.tts.SpeechPlayback.stop("run_stop")
        if (stoppingRuns.containsKey(runId)) return
        val imageGen = imageGenerationRunIds.remove(runId)
        if (imageGen) directMediaRuns.cancel(runId)
        flushPendingRunDelta(runId)
        val retrying = modelRetryState.isWaiting(runId)
        if (!imageGen) {
            // Publish scope before the stop flag observed by the delivery thread.
            if (keepChildren) mainStopReasons[runId] = reason else mainStopReasons.remove(runId)
            stoppingRuns[runId] = retrying
            armStopSealWatchdog(runId)
            scope.launch(Dispatchers.IO) {
                val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
                val accepted = if (keepChildren) client.stopMainRun(runId, reason) else { client.cancelRun(runId); true }
                if (!accepted) withContext(Dispatchers.Main.immediate) {
                    // A late transport failure cannot resurrect a run already settled by its
                    // result/watchdog, nor mark a newer run in the same conversation streaming.
                    if (!stoppingRuns.containsKey(runId)) return@withContext
                    stoppingRuns.remove(runId)
                    cancelStopSealWatchdog(runId)
                    conversationIdForRun(runId)?.let { owner ->
                        conversationState(owner)?.let { current ->
                            updateConversation(owner, current.copy(isStreaming = true, isPaused = false), updateTimestamp = false)
                        }
                    }
                    AndroidAgentLogger.warn("Stop request not confirmed for run=$runId")
                    Toast.makeText(appContext, "停止请求尚未确认，请重试或检查任务状态。", Toast.LENGTH_SHORT).show()
                    // Do not unlock settings or drop the result subscriber on an uncertain outcome.
                    refreshRuntimeResults()
                }
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
            SystemNoticeCode.Stopped,
            detail = when {
                imageGen -> "已停止本地生成等待并取消网络请求；服务端任务可能仍在处理或计费，不会自动重发。"
                retrying -> "已停止等待接口重试"
                else -> null
            },
        )
        // Immediate UI feedback, without cancelling the result subscriber or losing history.
        setConversationStreaming(runId, false)
        if (imageGen) {
            discardQueuedRunEvents(runId)
            runMessageProjector.clearRun(runId)
            runMessageProjector.seal(runId)
            runGeneratedAtMillis.remove(runId)
            branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId)
            runOverheadTokens.remove(runId); runContextWindows.remove(runId)
            contextEstimateDiagnostics.clear(runId)
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
        if (homeState.assistantId == id) return
        val replacedRun = activeRunIdForSelectedConversation().takeIf { homeState.isPaused }
        val discarded = memoryState.hasUnsavedChanges &&
            memoryState.assistantId.isNotBlank() &&
            memoryState.assistantId != id
        applyConversationAssistant(id, persist = true)
        replacedRun?.let { stopRun(it, reason = AgentChildControlPolicy.Reason.SETTINGS_CHANGED) }
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
            discardQueuedRunEvents(runId)
            runMessageProjector.clearRun(runId)
            runMessageProjector.seal(runId)
            runGeneratedAtMillis.remove(runId)
            branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId)
            runOverheadTokens.remove(runId); runContextWindows.remove(runId)
            contextEstimateDiagnostics.clear(runId)
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
            val state = conversationState(conversationId)
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
        // Resuming a paused run only releases Runtime's pause gate; an in-run compaction that
        // was interrupted by the pause must be allowed to finish, not block its own resume.
        if (!homeState.isPaused && rejectSendIfCompressing()) return
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
        val state = conversationState(conversationId) ?: return
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
        val state = conversationState(conversationId) ?: return
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
        // Snapshot batches publication only. Defer terminal ordering and summaries
        // explicitly, rather than rescanning the full history for every event.
        Snapshot.withMutableSnapshot {
            flushPendingRunDelta(runId)
            runReplayBatch.replay(
                runId = runId,
                events = events,
                reset = {
                    updateMessages(runId, updateTimestamp = false) { messages ->
                        runMessageProjector.resetForReplay(
                            runId = runId,
                            messages = messages,
                            replaySupplementIndexes = events.filterIsInstance<AgentEvent.UserSupplementReceived>()
                                .mapTo(mutableSetOf()) { it.index },
                        )
                    }
                },
                apply = { event -> applyRunEvent(runId, event, persistSupplement = false, replaying = true) },
                finish = {
                    updateMessages(runId, updateTimestamp = false) { it }
                    refreshConversationSummaries()
                },
            )
        }
    }

    private fun enqueueRunEvent(runId: String, event: AgentEvent) {
        if (!StreamPerformanceDiagnostics.enabled) {
            enqueueRunEventBudgeted(runId, event)
            return
        }
        val conversationId = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) runConversationIds[runId] else null
        StreamUiEventDiagnostics.withEvent(runId, conversationId,
            conversationId?.let { it == selectedConversationId }, event) {
            StreamPerformanceDiagnostics.measure("ui.enqueue") { enqueueRunEventBudgeted(runId, event) }
        }
    }

    private fun enqueueRunEventBudgeted(runId: String, event: AgentEvent) {
        // Runtime delivers events on an IO job. Keep the existing main-thread
        // ownership, but do not let a burst monopolize one dispatcher turn.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main.immediate) { enqueueRunEvent(runId, event) }
            return
        }
        if (runMessageProjector.isSealed(runId) && !event.allowedAfterSeal()) return
        runEventBudgets.getOrPut(runId) { AgentRunEventBudget(STREAM_EVENT_FRAME_BUDGET_NS) }
            .offer(event)
        if (runId !in runEventBudgetCallbacks) {
            drainRunEventBudget(runId, force = false)
        }
    }

    private fun drainRunEventBudget(runId: String, force: Boolean, singleEvent: Boolean = false) {
        val queue = runEventBudgets[runId] ?: return
        if (!force && runEventFrameWorkDepth > 0) {
            scheduleRunEventBudgetDrain(runId)
            return
        }
        if (!drainingRunEventBudgets.add(runId)) return
        runFrameEventBudget.measureWork {
            // Also schedule a one-shot reset when this drain empties the queue. New
            // arrivals in the same frame must not receive another fresh 2 ms budget.
            scheduleRunEventBudgetDrain(runId)
            runEventFrameWorkDepth++
            try {
                queue.drain(force, frameBudget = runFrameEventBudget,
                    maxEvents = if (singleEvent) 1 else Int.MAX_VALUE,
                ) { event -> enqueueRunEventNow(runId, event) }
            } finally {
                runEventFrameWorkDepth--
                drainingRunEventBudgets.remove(runId)
            }
            if (queue.isEmpty) {
                runEventBudgets.remove(runId)
                cancelRunEventBudgetCallback(runId)
            } else {
                scheduleRunEventBudgetDrain(runId)
            }
        }
    }

    private fun scheduleRunEventBudgetDrain(runId: String) {
        if (runEventBudgets.containsKey(runId) || runId in runEventDeferredFlushes) runEventBudgetCallbacks.add(runId)
        if (runEventBudgetFrameCallback != null) return
        val callback = Choreographer.FrameCallback { frameTimeNs ->
            runEventBudgetFrameCallback = null
            runFrameEventBudget.beginFrame(frameTimeNs)
            runFrameEventBudget.measureWork {
                // One event per turn: a busy run goes behind the other waiting runs.
                // All runs share the frame budget without starving later arrivals.
                while (runEventBudgetCallbacks.isNotEmpty() && !runFrameEventBudget.exhausted) {
                    val nextRun = runEventBudgetCallbacks.first()
                    runEventBudgetCallbacks.remove(nextRun)
                    val deferredFlush = runEventDeferredFlushes.remove(nextRun)
                    if (deferredFlush != null) {
                        deferredFlush()
                        if (runEventBudgets.containsKey(nextRun)) scheduleRunEventBudgetDrain(nextRun)
                    } else {
                        drainRunEventBudget(nextRun, force = false, singleEvent = true)
                    }
                }
                if (runEventBudgetCallbacks.isNotEmpty()) {
                    scheduleRunEventBudgetDrain(runEventBudgetCallbacks.first())
                }
            }
        }
        runEventBudgetFrameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun cancelRunEventBudgetCallback(runId: String) {
        if (runId !in runEventDeferredFlushes) runEventBudgetCallbacks.remove(runId)
        // Keep the single pending frame callback: it resets the budget even if
        // the last queue was emptied or cancelled before the next vsync.
    }

    private fun discardQueuedRunEvents(runId: String) {
        runEventDeferredFlushes.remove(runId)
        runEventFlushJobs.remove(runId)?.cancel()
        cancelRunEventBudgetCallback(runId)
        runEventBudgets.remove(runId)
        runEventCoalescer.flush(runId)
    }

    private fun enqueueRunEventNow(runId: String, event: AgentEvent) {
        if (event is AgentEvent.AssistantBlockDelta) {
            StreamPerformanceDiagnostics.record("ui.delta.received", value = event.delta.length.toLong())
        }
        if (stoppingRuns.containsKey(runId)) {
            // A terminal event is the runtime's own confirmation; dropping it left the watchdog as
            // the only way out of the stopping state.
            if (RunStopEventGate.isRunTerminal(event)) {
                finishStopSeal(runId)
            } else if (
                event !is AgentEvent.ContextCompacted &&
                event !is AgentEvent.UserSupplementReceived &&
                event !is AgentEvent.UsageReceived &&
                event !is AgentEvent.ChildContextUpdated &&
                !(event is AgentEvent.ErrorReconnectChanged && event.status != "running")
            ) {
                return
            }
        }
        if (runMessageProjector.isSealed(runId) && !event.allowedAfterSeal()) {
            runEventFlushJobs.remove(runId)?.cancel()
            runEventCoalescer.flush(runId)
            return
        }
        if (event is AgentEvent.AssistantBlockDelta) {
            if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL || event.delta.isEmpty()) return
            runEventCoalescer.append(runId, event)?.let { ready ->
                StreamUiEventDiagnostics.measure("ui.flush.blockSwitch") {
                    applyRunEvent(runId, ready)
                }
            }
            scheduleRunDeltaFlush(runId)
            return
        }

        flushPendingRunDelta(runId, diagnosticStage = "ui.flush.nonDelta")
        applyRunEvent(runId, event)
    }

    /**
     * 停止请求发出后启动看门狗。Runtime 可能永远不回 RunResult（子任务收尾阻塞、Binder 丢失），
     * 所以解除界面锁定不能只依赖它；到点后由看门狗补一条终态结果，并如实说明本轮结果未确认。
     */
    private fun armStopSealWatchdog(
        runId: String,
        timeout: RunStopSealTimeout = stopSealTimeout,
    ) {
        val ticket = timeout.beginStop(runId) ?: return
        stopSealWatchdogJobs.remove(runId)?.cancel()
        stopSealWatchdogJobs[runId] = scope.launch {
            delay(timeout.timeoutMillis)
            withContext(Dispatchers.Main.immediate) {
                if (!timeout.claimUnlock(ticket)) return@withContext
                stopSealWatchdogJobs.remove(runId)
                // applyRunResult consumes the stoppingRuns entry itself; removing it here would
                // erase the retry flag that decides which stop notice the user sees.
                if (!stoppingRuns.containsKey(runId)) return@withContext
                AndroidAgentLogger.warn("Stop seal timed out without a terminal result for run=$runId")
                applyRunResult(
                    runId,
                    AgentRuntimeWire.RunResult(
                        runId = runId,
                        ok = false,
                        content = "",
                        error = StopSealNotices.TIMED_OUT,
                    ),
                )
                Toast.makeText(appContext, StopSealNotices.TIMED_OUT, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Runtime 已确认终态，撤掉看门狗，避免它稍后再改写一条已经落定的结果。 */
    private fun cancelStopSealWatchdog(runId: String) {
        stopSealWatchdogJobs.remove(runId)?.cancel()
        stopSealTimeout.release(runId)
        stopSealTerminalTimeout.release(runId)
    }

    /**
     * 收到终态事件：本轮已经结束，只给正式 RunResult 一个短宽限期。
     * 这里不动 stoppingRuns，transcript 归属与 retry 标志仍由 applyRunResult 统一收尾。
     */
    private fun finishStopSeal(runId: String) {
        // Duplicate terminal notifications must not cancel the one remaining grace watchdog.
        if (stopSealTerminalTimeout.isPending(runId)) return
        stopSealWatchdogJobs.remove(runId)?.cancel()
        stopSealTimeout.release(runId)
        armStopSealWatchdog(runId, stopSealTerminalTimeout)
    }

    private fun AgentEvent.allowedAfterSeal(): Boolean =
        this is AgentEvent.UsageReceived || this is AgentEvent.ChildContextUpdated ||
            (this is AgentEvent.ErrorReconnectChanged && status != "running")

    private fun scheduleRunDeltaFlush(runId: String) {
        if (runEventFlushJobs[runId]?.isActive == true) return
        val scheduledAtNs = if (StreamPerformanceDiagnostics.enabled) System.nanoTime() else null
        val diagnosticAttribution = StreamPerformanceDiagnostics.captureAttribution()
        runEventFlushJobs[runId] = scope.launch {
            delay(STREAM_UI_UPDATE_INTERVAL_MS)
            if (scheduledAtNs != null && StreamPerformanceDiagnostics.enabled) {
                StreamPerformanceDiagnostics.record("ui.flushDelay", System.nanoTime() - scheduledAtNs)
            }
            runEventFlushJobs.remove(runId)
            if (runFrameEventBudget.exhausted) {
                runEventDeferredFlushes[runId] = {
                    StreamPerformanceDiagnostics.withAttribution(diagnosticAttribution) {
                        StreamPerformanceDiagnostics.measure("ui.flush") {
                            flushPendingRunDelta(runId, diagnosticStage = "ui.flush.timer", drainQueuedEvents = false)
                        }
                    }
                }
                scheduleRunEventBudgetDrain(runId)
                return@launch
            }
            runFrameEventBudget.measureWork {
                StreamPerformanceDiagnostics.withAttribution(diagnosticAttribution) {
                    StreamPerformanceDiagnostics.measure("ui.flush") {
                        flushPendingRunDelta(
                            runId,
                            diagnosticStage = "ui.flush.timer",
                            drainQueuedEvents = false,
                        )
                    }
                }
            }
        }
    }

    private fun flushPendingRunDelta(
        runId: String,
        diagnosticStage: String? = null,
        drainQueuedEvents: Boolean = true,
    ) {
        if (drainQueuedEvents && runId !in drainingRunEventBudgets) {
            drainRunEventBudget(runId, force = true)
        }
        runEventDeferredFlushes.remove(runId)
        runEventFlushJobs.remove(runId)?.cancel()
        runEventCoalescer.flush(runId)?.let { event ->
            // Only count a reason when a pending delta is actually applied. Other
            // callers (replay/result/stop) retain their existing default flush path.
            val started = if (drainQueuedEvents) null else System.nanoTime()
            if (started != null) {
                scheduleRunEventBudgetDrain(runId)
                runEventFrameWorkDepth++
            }
            try {
                StreamUiEventDiagnostics.measure(diagnosticStage) {
                    applyRunEvent(runId, event)
                }
            } finally {
                if (started != null) {
                    runEventFrameWorkDepth--
                    runFrameEventBudget.record(System.nanoTime() - started)
                }
            }
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
        // 只加计时：流式增量（value=1）和其它事件分开看单次耗时。
        val conversationId = if (StreamPerformanceDiagnostics.enabled) runConversationIds[runId] else null
        StreamUiEventDiagnostics.withEvent(runId, conversationId,
            conversationId?.let { it == selectedConversationId }, event, replaying) {
            StreamPerformanceDiagnostics.measure(
                "ui.runEvent",
                if (event is AgentEvent.AssistantBlockDelta) 1L else 0L,
            ) {
                StreamUiEventDiagnostics.measureEvent(event) {
                    applyRunEventNow(runId, event, persistSupplement, replaying)
                }
            }
        }
    }

    private fun applyRunEventNow(
        runId: String,
        event: AgentEvent,
        persistSupplement: Boolean,
        replaying: Boolean,
    ) {
        modelRetryState.accept(runId, event)
        val thinkingEvent = event.opensThinking()
        if (thinkingEvent && !replaying) runsWithLiveReasoning += runId
        // 流式增量很密，只在这次运行确实有推理块在跑时才扫一遍消息。
        val reasoningBefore = if (replaying || thinkingEvent || event is AgentEvent.RunFailed ||
            runId !in runsWithLiveReasoning
        ) {
            emptySet()
        } else {
            streamingThinkingIds(runId).also { if (it.isEmpty()) runsWithLiveReasoning -= runId }
        }
        applyRunEventBody(runId, event, persistSupplement, replaying)
        if (reasoningBefore.isNotEmpty()) noteCompletedReasoning(runId, reasoningBefore)
        if (event is AgentEvent.RunFinished || event is AgentEvent.RunFailed) runsWithLiveReasoning -= runId
    }

    private fun AgentEvent.opensThinking(): Boolean = when (this) {
        is AgentEvent.AssistantBlockStart -> kind == AgentEvent.AssistantBlockKind.THINKING
        is AgentEvent.AssistantBlockDelta -> kind == AgentEvent.AssistantBlockKind.THINKING
        else -> false
    }

    /** 有推理块可能还在「正在推理」的运行；只在主线程读写。 */
    private val runsWithLiveReasoning = mutableSetOf<String>()

    /** 本次运行里仍在「正在推理」的思考块。没有时返回空集，事件处理后不用再比。 */
    private fun streamingThinkingIds(runId: String): Set<String> {
        val state = conversationState(conversationIdForRun(runId)) ?: return emptySet()
        val prefix = "$runId-thinking-"
        var ids: MutableSet<String>? = null
        for (message in state.messages) {
            if (message is ThinkingMessageUi && message.isStreaming && message.id.startsWith(prefix)) {
                (ids ?: mutableSetOf<String>().also { ids = it }).add(message.id)
            }
        }
        return ids ?: emptySet()
    }

    /** 处理前在推理、处理后已结束的块各震一次；失败或停止不算完成。 */
    private fun noteCompletedReasoning(runId: String, before: Set<String>) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationState(conversationId) ?: return
        state.messages.forEach { message ->
            if (message is ThinkingMessageUi && !message.isStreaming && message.id in before) {
                io.github.mangi.eta.ui.haptics.StreamingHaptics.noteReasoningCompleted(message.id, conversationId)
            }
        }
    }

    /** Draft edits and submissions are bound to stored four-ID ownership, not selected chat. */
    fun updateQuestionDraft(conversationId: String, questionId: String, draft: AgentQuestionAnswer) {
        val state = conversationState(conversationId) ?: return
        val question = state.messages.filterIsInstance<AgentQuestionMessageUi>()
            .singleOrNull { it.request.questionId == questionId } ?: return
        if (question.status != AgentQuestionStatus.Waiting || question.submitting) return
        val kind = when {
            draft.kind == "option" -> "option"
            draft.kind == "other" && question.request.allowOther -> "other"
            draft.kind == "delegate" && question.request.allowDelegation -> "delegate"
            else -> return
        }
        val selected = draft.optionId?.takeIf { id -> question.request.options.any { it.id == id } }
        val updated = question.copy(answerKind = kind, selectedOptionId = selected,
            otherText = draft.otherText.take(2000), note = if (question.request.allowNote) draft.note.take(2000) else "",
            error = null)
        updateConversation(conversationId, state.copy(messages = state.messages.map {
            if (it.id == question.id) updated else it
        }))
        persistConversations()
    }

    fun submitQuestionAnswer(conversationId: String, questionId: String) {
        val state = conversationState(conversationId) ?: return
        val question = state.messages.filterIsInstance<AgentQuestionMessageUi>()
            .singleOrNull { it.request.questionId == questionId } ?: return
        if (question.request.conversationId != conversationId || question.status != AgentQuestionStatus.Waiting || question.submitting) return
        val answer = AgentQuestionProjection.draftAnswer(question)
        val validation = AgentQuestionCodec.validateAnswer(question.request, answer)
        if (!validation.accepted) {
            updateConversation(conversationId, state.copy(messages = state.messages.map {
                if (it.id == question.id) question.copy(error = validation.message.ifBlank { validation.code }) else it
            }))
            return
        }
        val owner = question.request
        updateConversation(conversationId, state.copy(messages = state.messages.map {
            if (it.id == question.id) question.copy(submitting = true, error = null) else it
        }))
        scope.launch(Dispatchers.Main.immediate) {
            val receipt = withContext(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).submitQuestionAnswer(
                    owner.conversationId, owner.runId, owner.questionId, owner.toolCallId, answer)
            }
            val current = conversationState(conversationId) ?: return@launch
            val messages = current.messages.map { m -> if (m is AgentQuestionMessageUi &&
                AgentQuestionProjection.sameOwner(m.request, owner)) AgentQuestionProjection.acknowledged(m, answer, receipt) else m }
            updateConversation(conversationId, current.copy(messages = messages))
            persistConversations()
            // A receipt only acknowledges the request. Query every non-Answered local card,
            // including Interrupted/Cancelled cards closed by a racing terminal event; an
            // authoritative Answered snapshot may correct those, while Waiting never reopens them.
            if (receipt.accepted) delay(1_000)
            val checkMessages = conversationState(conversationId)?.messages.orEmpty()
            if (AgentQuestionProjection.needsAuthoritativeQuery(checkMessages, owner)) {
                val snapshot = withContext(Dispatchers.IO) {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).queryQuestion(owner.conversationId,
                        owner.runId, owner.questionId, owner.toolCallId)
                }
                val latest = conversationState(conversationId) ?: return@launch
                updateConversation(conversationId, latest.copy(
                    messages = AgentQuestionProjection.reconcileMessages(latest.messages, owner, snapshot)
                ))
                persistConversations()
            }
        }
    }

    private fun applyRunEventBody(
        runId: String,
        event: AgentEvent,
        persistSupplement: Boolean,
        replaying: Boolean,
    ) {
        when (event) {
            is AgentEvent.QuestionRequested -> {
                val owner = conversationIdForRun(runId) ?: return
                updateRunTrace(runId) { messages -> runMessageProjector.requestQuestion(owner, runId, event, messages, replaying) }
                if (!replaying) persistConversations()
            }
            is AgentEvent.QuestionResolved -> {
                val owner = conversationIdForRun(runId) ?: return
                updateRunTrace(runId) { messages -> runMessageProjector.resolveQuestion(owner, runId, event, messages) }
                if (!replaying) persistConversations()
            }
            is AgentEvent.AssistantBlockStart -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.startAssistantBlock(runId, event, messages)
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                updateMessages(
                    runId = runId,
                    updateTimestamp = false,
                    normalizeTerminalOrder = false,
                    recomputeWaitingQuestion = false,
                ) { messages ->
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
                if (event.kind != AgentEvent.AssistantBlockKind.TOOL_CALL) {
                    io.github.mangi.eta.ui.haptics.StreamingHaptics.noteBackgroundOutput(
                        event.deltaChars.coerceAtLeast(event.delta.length),
                        conversationIdForRun(runId),
                        reasoning = event.kind == AgentEvent.AssistantBlockKind.THINKING,
                    )
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
                // Parent events invalidate telemetry only; old run snapshots cannot replace newer owner state.
                conversationIdForRun(runId)?.let(::requestOwnerContext)
            }

            is AgentEvent.UsageReceived -> {
                if (event.projected) {
                    if (!isStaleUsageAfterCompact(runId, event.round)) {
                        val occupancy = io.github.mangi.eta.ui.model.windowTokensFromUsage(event.usage.toUi())
                        updateLivePromptTokens(runId, occupancy, projected = true)
                    }
                } else if (!isStaleUsageAfterCompact(runId, event.round)) {
                    val occupancy = io.github.mangi.eta.ui.model.windowTokensFromUsage(event.usage.toUi())
                    // Record the bill and advance occupancy independently of local token growth.
                    // A local estimate cannot invalidate a cloud receipt or pin the ring to an old bill.
                    updateAssistantUsage(runId, event.round, event.usage.toUi())
                    val localBasis = event.requestHistoryTokens?.let { history ->
                        history + (event.requestOverheadTokens ?: 0)
                    }
                    val conversation = conversationIdForRun(runId)?.let(::conversationState)
                    // Judge the receipt against the window this run was launched with,
                    // not against a limit the user may have changed mid-run.
                    val window = runContextWindows[runId] ?: conversation?.let(::boundCompressionWindow)
                    // A gateway cache_read above its own prompt or the window is a billing
                    // artefact, not occupancy: drop it and keep the previous cloud receipt.
                    // Same inputs as AgentSilentContextBudget, so ring and compaction agree.
                    val inflatedCache = io.github.mangi.eta.agent.model.AgentBilledPromptPlausibility
                        .isInflatedCacheRead(occupancy, event.usage.cachedTokens, window)
                    val measured = occupancy.takeIf {
                        io.github.mangi.eta.ui.model.CloudReceiptPlausibility.isOccupancy(
                            tokens = it, contextWindow = window)
                    }
                    if (inflatedCache) {
                        // Ring keeps the last trusted cloud value; same rule as AgentSilentContextBudget.
                    } else if (measured != null) {
                        updateLivePromptTokens(runId, measured, projected = false,
                            historyTokens = event.requestHistoryTokens, overheadTokens = event.requestOverheadTokens,
                            round = event.round)
                        // 诊断只配对本次真实回执；显示学习通路已删除，不再持久化三样本比率。
                        if (!replaying) contextEstimateDiagnostics.receipt(runId, measured,
                            event.usage.cachedTokens, round = event.round,
                            historyTokens = event.requestHistoryTokens,
                            overheadTokens = event.requestOverheadTokens)
                    } else if (localBasis != null && localBasis > 0) {
                        // Keep a usable, self-consistent basis instead of an implausible bill.
                        updateLivePromptTokens(runId, localBasis, projected = true)
                    }
                }
            }

            is AgentEvent.UserSupplementReceived -> {
                conversationIdForRun(runId)?.let { id ->
                    conversationState(id)?.takeIf { it.isPaused }?.let { state ->
                        updateConversation(id, state.copy(isPaused = false))
                    }
                }
                pendingSteerDrafts.remove(event.requestId)?.let { draft ->
                    val id = draft.conversationId
                    if (conversationDrafts.field(id).text.toString() == draft.submittedText) {
                        conversationDrafts.replace(id, "")
                    }
                    val state = id?.let(::conversationState)
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
                if (!replaying) {
                    io.github.mangi.eta.ui.haptics.StreamingHaptics.noteToolAppeared(
                        "$runId-tool-${event.round}-${event.toolCallId.ifBlank { "unknown" }}",
                        conversationIdForRun(runId),
                    )
                }
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
                if (!replaying) {
                    io.github.mangi.eta.ui.haptics.StreamingHaptics.noteToolAppeared(
                        "$runId-tool-${event.round}-${event.toolCallId.ifBlank { "unknown" }}",
                        conversationIdForRun(runId),
                    )
                }
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
                contextEstimateDiagnostics.clear(runId)
                revokeContextActual(runId)
                updateRunTrace(runId) { messages ->
                    runMessageProjector.scheduleModelRetry(runId, event, messages)
                }
            }

            is AgentEvent.ErrorReconnectChanged -> {
                if (event.status == "running") {
                    contextEstimateDiagnostics.clear(runId)
                    revokeContextActual(runId)
                }
                updateRunTrace(runId) { messages ->
                    runMessageProjector.reconnectChanged(runId, event, messages)
                }
            }

            is AgentEvent.RunFailed -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
                    runMessageProjector.terminalFailure(runId, event.reason,
                        runMessageProjector.failRunningTools(event.reason, finalizedText))
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

            is AgentEvent.AutoCompactWaiting -> {
                conversationIdForRun(runId)?.let { setConversationWaitingForCompression(it, true) }
            }

            is AgentEvent.ContextCompactionStarted -> {
                conversationIdForRun(runId)?.let { setConversationCompressing(it, true, event.modelName) }
            }

            is AgentEvent.ContextCompacted -> {
                applyRuntimeCompactedHistory(runId, event, notifyCompletion = !replaying)
            }

            is AgentEvent.ProviderRequestStarted -> {
                if (runRequestRounds.put(runId, event.round) != null || event.round != 1) contextEstimateDiagnostics.clear(runId)
                revokeContextActual(runId)
                runMessageProjector.beginProviderRequest(runId, event.round)
                if (contextBudgetBlockedRuns.remove(runId) != null) {
                    if (contextBudgetPrompt?.runId == runId) contextBudgetPrompt = null
                    conversationIdForRun(runId)?.let { id -> conversationState(id)?.let { updateConversation(id, it.copy(isPaused = false)) } }
                }
            }

            is AgentEvent.RunStarted -> {
                conversationIdForRun(runId)?.let { id ->
                    conversationState(id)?.let { current ->
                        updateConversation(id, current.copy(childContextRunId = runId), updateTimestamp = false)
                    }
                    requestOwnerContext(id)
                }
            }
            is AgentEvent.RoundStarted -> {
                val source = conversationIdForRun(runId)?.let(::conversationState)
                if (source != null && event.historySnapshotId.isNotBlank()) {
                    branchRequestBoundaries[runId] = BranchRequestBoundary(runId, event.round, event.historySnapshotId,
                        source.messages.filterIsInstance<AgentMessageUi>()
                            .filter { AgentRunningBranchSnapshot.isTextForRound(it.id, runId, event.round) }
                            .associate { it.id to it.content })
                }
            }
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
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
                conversationState(conversationId)?.let { updateConversation(conversationId, it.copy(isPaused = true)) }
            } else if (event.reason.isNotBlank() && selectedConversationId == conversationId) {
                Toast.makeText(appContext, event.reason, Toast.LENGTH_LONG).show()
            }
            return
        }
        contextEstimateDiagnostics.clear(runId) // The pre-send request no longer exists, including pruning-only rewrites.
        runUsageResumeRounds[runId] = event.round
        val current = conversationState(conversationId) ?: return
        if (event.pruningOnly) {
            // Automatic and manual tool pruning both keep the cloud reading and
            // raw-history calibration anchor. Only the silent delta has changed.
            // Do not insert a summary marker or finish a pending manual summary.
            updateConversation(conversationId, current.copy(history = event.history))
            persistConversations()
            return
        }
        runCompressedDuringRun.add(runId)
        updateConversation(
            conversationId,
            current.copy(
                isCompressingContext = false,
                isWaitingForCompression = false,
                history = event.history,
                livePromptTokens = null,
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
                messages = AgentContextCompactionUi.applyMarker(
                    messages = current.messages,
                    originalHistory = current.history,
                    compressedHistory = event.history,
                    extraKeptUserMessages = 0,
                    compressorLabel = event.compressorLabel,
                    baselineTokens = 0,
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
        if (!autoCompressPreference.enabled) return
        if (!allowRepeat && runId != null && runId in runCompressedDuringRun) return
        val state = conversationState(conversationId) ?: return
        if (state.isStreaming || state.isPaused) {
            return
        }
        val boundModel = AgentModelPickerProjector.project(selectionProviders, state.providerId, state.modelId).selectedModel
            ?.takeIf { it.providerId == state.providerId && it.id == state.modelId } ?: return
        val contextWindow = boundModel.contextWindow
        val estimatedTokens = compressionContextUsage(
            history = state.history,
            currentInput = "",
            pendingImages = emptyList(),
            selectedModel = boundModel,
            billedContextTokens = budgetReceiptTokens(state),
            requestOverheadTokens = if (conversationId == selectedConversationId) requestOverheadTokens else state.cloudRequestOverheadTokens ?: 0,
            billedOverheadTokens = state.cloudRequestOverheadTokens,
            billedHistoryTokens = state.cloudHistoryTokens,
        ).contextTokens
        // 与圆环同一个数：云端实测。estimatedTokens 只用于压缩时按账单缩放保留尾部。
        if (!shouldAutoCompress(state.history, contextWindow, billedPromptTokens(state))) return
        val billed = billedPromptTokens(state)
        val local = billed?.let {
            compressionContextUsage(history = state.history, currentInput = "", pendingImages = emptyList(),
                selectedModel = boundModel,
                requestOverheadTokens = if (conversationId == selectedConversationId) requestOverheadTokens else state.cloudRequestOverheadTokens ?: 0,
            ).contextTokens
        }
        if (runId != null && !allowRepeat) {
            runCompressedDuringRun.add(runId)
        }
        setConversationCompressing(conversationId, true)
        val previous = compressionJob
        compressionJobConversationId = conversationId
        compressionJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                compressSubmittedHistory(conversationId, estimatedTokens.takeIf { billed != null }, local)
            } finally {
                withContext(Dispatchers.Main) {
                    // Only a manual request queued for *this* conversation keeps its indicator.
                    if (pendingManualCompress?.conversationId != conversationId) {
                        setConversationCompressing(conversationId, false)
                    }
                    startPendingManualCompress()
                }
            }
        }
    }

    private suspend fun compressSubmittedHistory(
        conversationId: String,
        billedTokens: Int? = null,
        localTokens: Int? = null,
    ) {
        val snapshot = withContext(Dispatchers.Main) {
            conversationState(conversationId)
        } ?: return
        val originalHistory = snapshot.history
        val fallback = runtimeConfigForBoundModel(snapshot, assistant = null)
            ?: return
        val compressModelConfig = resolveCompressModelConfig(fallback)
        var summaryCommitted = false
        val compressed = tryCompressHistory(originalHistory, compressModelConfig,
            sourceModelConfig = fallback,
            conversationId = conversationId, contextWindow = fallback.contextWindow,
            billedTokens = billedTokens, localTokens = localTokens,
            onSummaryCommitted = { summaryCommitted = true })
        if (compressed == originalHistory) return
        withContext(Dispatchers.Main) {
            val latest = conversationState(conversationId) ?: return@withContext
            if (latest.history != originalHistory) return@withContext
            if (!summaryCommitted) {
                updateConversation(conversationId, latest.copy(
                    history = compressed, isCompressingContext = false, isWaitingForCompression = false,
                ))
                persistConversations()
                return@withContext
            }
            updateConversation(
                conversationId,
                latest.copy(
                    isCompressingContext = false,
                    isWaitingForCompression = false,
                    history = compressed,
                    livePromptTokens = null,
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
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

    private fun markConversationCompleted(conversationId: String) {
        conversationCompletionMarkers = conversationCompletionMarkers + conversationId
    }

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
        petReaction: Boolean = false,
    ) {
        val stoppedDuringRetry = stoppingRuns.remove(runId)
        cancelStopSealWatchdog(runId)
        mainStopReasons.remove(runId)
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
                SystemNoticeCode.Stopped,
                detail = if (stoppedDuringRetry) "已停止等待接口重试" else null,
            )
            result.ok && (result.content.isNotBlank() || VirtualCompletionNotice.confirmed(result)) -> completeLatestAssistantMessage(
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
        if (stoppedDuringRetry == null) {
            updateMessages(runId) { VirtualCompletionNotice.append(it, runId, result) }
            if (acknowledgeRuntimeResult && VirtualCompletionNotice.confirmed(result)) {
                Toast.makeText(appContext, "任务完成", Toast.LENGTH_SHORT).show()
            }
        }
        // A stop/failure without this round's receipt still keeps the latest actual from this epoch.
        // setConversationStreaming consumes first-turn NONE without fabricating a cloud receipt.
        revokeContextActual(runId)
        setConversationStreaming(runId, false)
        val conversationId = conversationIdForRun(runId)
        if (conversationId != null && ConversationCompletionMarker.shouldMark(
                result, wasStopped = stoppedDuringRetry != null,
                isSelected = conversationId == selectedConversationId,
            )) {
            markConversationCompleted(conversationId)
        }
        conversationId?.let(pendingInRunCompactConversationIds::remove)
        runMessageProjector.clearRun(runId)
        runGeneratedAtMillis.remove(runId)
        branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId); invalidatedUsageRuns.remove(runId)
        runOverheadTokens.remove(runId); runContextWindows.remove(runId)
        contextEstimateDiagnostics.clear(runId)
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
        if (petReaction && result.ok && stoppedDuringRetry == null && conversationId != null) {
            val petTitle = conversationTitles[conversationId].orEmpty()
            val petContext = appContext
            scope.launch(Dispatchers.IO) {
                io.github.mangi.eta.agent.pet.WhaleMaidController.onWorkDone(petContext, petTitle)
            }
        }
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
        (runUsageResumeRounds[runId]?.let { round < it } ?: false) ||
            (runRequestRounds[runId]?.let { round < it } ?: false)

    private fun updateAssistantUsage(runId: String, round: Int, usage: TokenUsageUi) {
        if (usage.isEmpty) return
        if (isStaleUsageAfterCompact(runId, round)) return
        // 只补充 token 用量。不能触碰 isStreaming：Usage 事件紧跟在文本块结束之后，
        // 若把 isStreaming 改回 true，流式渲染会在流式/静态两种视图间反复切换，整段重渲染。
        // A recovered/unknown run must not borrow the currently selected model's estimate.
        val overhead = runOverheadTokens[runId]
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
                // Preserve every previous snapshot; only the target chunk changes.
                val target = messages[targetIndex] as AgentMessageUi
                messages.incrementalSnapshot().replacing(targetIndex, target.copy(usage = usage))
            }
        }
        billedOverheadConversationId = conversationId
        billedOverheadTokens = overhead
    }

    /** Request/retry/terminal boundaries only revoke a receipt if its model or route became invalid.
     * Same-route requests keep actual and evidence, including same-request usage corrections.
     * A new request's evidence is separated by ContextReceiptEvidence.merge(requestId), not here. */
    private fun revokeContextActual(runId: String) {
        val id = conversationIdForRun(runId) ?: return
        val state = conversationState(id) ?: return
        if (usageRunByConversation[id] != runId || runId in invalidatedUsageRuns) return
        val route = contextRouteSignature(state)
        if (route != null && runUsageOwners[runId] == (state.providerId to state.modelId) &&
            runUsageRoutes[runId] == route &&
            (state.cloudRouteSignature == null || state.cloudRouteSignature == route)) return
        invalidatedUsageRuns.add(runId)
        updateConversation(id, state.copy(
            livePromptTokens = null, livePromptIsProjected = false,
            cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
            contextAwaitingReceipt = state.contextAwaitingReceipt || state.contextHasStarted,
            cloudReceiptRequestId = null, cloudRouteSignature = null,
            contextReceiptEvidence = null, receiptPredictionTokens = null,
        ), updateTimestamp = false)
    }

    private fun bindUsageRun(runId: String, conversationId: String) {
        // Reattachment cannot revive an invalidated run or steal a newer run's receipt ownership.
        if (runId in invalidatedUsageRuns) return
        runConversationIds[runId] = conversationId
        usageRunByConversation[conversationId] = runId
        // A run's owner, route and compression resume floor are frozen at its first binding.
        if (runId in runUsageOwners) return
        conversationState(conversationId)?.let {
            runUsageOwners[runId] = it.providerId to it.modelId
            contextRouteSignature(it)?.let { signature -> runUsageRoutes[runId] = signature }
        }
    }

    private fun updateLivePromptTokens(runId: String, tokens: Int?, projected: Boolean = false,
        historyTokens: Int? = null, overheadTokens: Int? = null, round: Int? = null) {
        if (projected && stoppingRuns.containsKey(runId)) return
        if (tokens == null || tokens <= 0 || runId in invalidatedUsageRuns) return
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationState(conversationId) ?: return
        val owner = runUsageOwners[runId] ?: return
        if (owner != (state.providerId to state.modelId) || usageRunByConversation[conversationId] != runId) return
        val route = runUsageRoutes[runId] ?: return
        if (route != contextRouteSignature(state)) return
        // Runtime projections are budget evidence only, not learned display estimates.
        if (projected) return
        val requestId = round?.let { "$runId:$it" } ?: return
        val evidence = io.github.mangi.eta.ui.model.ContextReceiptEvidence.merge(
            state.contextReceiptEvidence, requestId, tokens, historyTokens, overheadTokens)
        val next = state.copy(livePromptTokens = tokens, livePromptIsProjected = false,
            contextBudgetReceiptTokens = tokens,
            contextHasStarted = true, contextAwaitingReceipt = false, receiptPredictionTokens = null,
            cloudReceiptRequestId = requestId, cloudRouteSignature = route, contextReceiptEvidence = evidence,
            cloudHistoryTokens = evidence.history, cloudRequestOverheadTokens = evidence.overhead)
        if (next == state) return
        updateConversation(
            conversationId,
            next,
            updateTimestamp = false,
        )
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
            val resultTail = AgentRunMessageProjector.completedResultTail(runId, messages, targetIndex, fallbackContent)
            if (targetIndex < 0) {
                messages + AgentMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    content = resultTail,
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
                            content = mergeCompletedAssistantContent(message.content, resultTail, sameRoundBlocks),
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
            // Error details belong in a clickable marker, never in the answer body.
            // Keep stop/control notices and the existing child-disposition flow intact.
            when (code) {
                SystemNoticeCode.RuntimeFailed -> runMessageProjector.terminalFailure(
                    runId, detail.orEmpty(), messages,
                )
                SystemNoticeCode.Stopped -> runMessageProjector.runStopped(runId, messages) +
                    SystemNoticeMessageUi("interrupted-$runId", code, detail)
                else -> messages + SystemNoticeMessageUi("interrupted-$runId", code, detail)
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

    private fun orderedTerminalState(state: AgentChatHomeUiState): AgentChatHomeUiState {
        if (!state.conversationContentLoaded) return state
        val ordered = state.messages.withTerminalBodiesInOrder()
        return if (ordered == state.messages) state else state.copy(messages = ordered)
    }

    private fun updateMessages(
        runId: String,
        updateTimestamp: Boolean = true,
        normalizeTerminalOrder: Boolean = true,
        recomputeWaitingQuestion: Boolean = true,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationState(conversationId) ?: return
        StreamPerformanceDiagnostics.measure("ui.messages.apply", state.messages.size.toLong()) {
            val projected = StreamUiEventDiagnostics.measure("ui.messages.transform", state.messages.size.toLong()) {
                transform(state.messages)
            }
            // Text/thinking deltas only replace or append the active run block. They
            // cannot create a terminal notice; defer the cross-history terminal
            // ordering pass to the next boundary/terminal event.
            val nextMessages = if (normalizeTerminalOrder) {
                StreamUiEventDiagnostics.measure("ui.messages.normalize", projected.size.toLong()) {
                    runReplayBatch.normalize(runId, projected)
                }
            } else {
                projected
            }
            // A sealed/filtered event can legitimately project the exact same immutable list.
            // Delta paths already disable timestamp and waiting-question recomputation, so
            // publishing that no-op only invalidates Compose observers and repeats routing work.
            if (shouldSkipNoOpStreamingPublication(
                    state.messages,
                    nextMessages,
                    updateTimestamp,
                    normalizeTerminalOrder,
                    recomputeWaitingQuestion,
                )
            ) {
                return@measure
            }
            StreamUiEventDiagnostics.measure("ui.messages.publish", nextMessages.size.toLong()) {
                updateConversationProjected(
                    conversationId = conversationId,
                    state = state.copy(messages = nextMessages),
                    updateTimestamp = updateTimestamp,
                    recomputeWaitingQuestion = recomputeWaitingQuestion,
                )
            }
        }
    }

    private fun applyConversationHistoryResult(
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationState(conversationId) ?: return
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
        if (!beginNewSubAgentDraft()) return
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
        ).withCurrentGptSpeedBinding()
        conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
    }

    private fun shouldSkipNoOpStreamingPublication(
        previousMessages: List<AgentChatMessageUi>,
        nextMessages: List<AgentChatMessageUi>,
        updateTimestamp: Boolean,
        normalizeTerminalOrder: Boolean,
        recomputeWaitingQuestion: Boolean,
    ): Boolean =
        nextMessages === previousMessages && !updateTimestamp &&
            !normalizeTerminalOrder && !recomputeWaitingQuestion

    private fun updateConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) = updateConversationProjected(conversationId, state, updateTimestamp, recomputeWaitingQuestion = true)

    private fun updateConversationProjected(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean,
        recomputeWaitingQuestion: Boolean,
    ) {
        check(state.conversationContentLoaded) { "Conversation content must be loaded before editing" }
        val previous = conversationState(conversationId)
        val projected = StreamUiEventDiagnostics.measure("ui.conversation.route") {
            val modelChanged = previous != null &&
                (previous.providerId != state.providerId || previous.modelId != state.modelId ||
                    (state.cloudRouteSignature != null && state.cloudRouteSignature != contextRouteSignature(state)))
            if (modelChanged) runConversationIds.filterValues { it == conversationId }.keys.forEach { invalidatedUsageRuns.add(it) }
            when {
                modelChanged -> state.copy(livePromptTokens = null, livePromptIsProjected = false,
                    cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                    // Selecting a model is not a completed first turn or a compression boundary.
                    contextAwaitingReceipt = state.contextAwaitingReceipt || state.contextHasStarted,
                    contextReceiptEvidence = null,
                    receiptPredictionTokens = null, cloudReceiptRequestId = null, cloudRouteSignature = null)
                state.contextAwaitingReceipt || state.cloudHistoryTokens == null || state.cloudRequestOverheadTokens == null ->
                    state.copy(contextBudgetReceiptTokens = null)
                (state.livePromptTokens == null || state.livePromptIsProjected) && state.contextBudgetReceiptTokens == null -> state.copy(
                    cloudHistoryTokens = null, cloudRequestOverheadTokens = null)
                else -> state
            }
        }
        val ownerContext = ownerContexts[conversationId]
        val view = StreamUiEventDiagnostics.measure("ui.conversation.owner") { ownerContext?.projection() }
        val questionProjected = if (recomputeWaitingQuestion) {
            StreamUiEventDiagnostics.measure("ui.conversation.waiting", projected.messages.size.toLong()) {
                projected.copy(isWaitingForAnswer = AgentQuestionProjection.hasWaiting(projected.messages))
            }
        } else {
            projected
        }
        // Owner projection is timed in two slices, not moved across the question scan.
        val current = StreamUiEventDiagnostics.measure("ui.conversation.owner") {
            if (view == null || ownerContext == null) questionProjected else questionProjected.copy(
                childContexts = view.children, selectedContextTaskId = view.selectedTaskId,
                childStatusRoster = ownerContext.roster(),
            )
        }
        StreamUiEventDiagnostics.measure("ui.conversation.publish") {
            conversationsById = conversationsById + (conversationId to current)
            if (conversationId !in conversationCreatedAt) {
                conversationCreatedAt = conversationCreatedAt + (conversationId to System.currentTimeMillis())
            }
            if (updateTimestamp) {
                conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
            }
            if (conversationId == selectedConversationId) {
                homeState = current
            }
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
        val current = conversationState(conversationId) ?: return
        updateConversation(conversationId, current.copy(isWaitingForCompression = waiting))
    }

    private fun setConversationCompressing(conversationId: String?, compressing: Boolean, modelName: String = "") {
        if (conversationId == null) {
            homeState = homeState.copy(isCompressingContext = compressing, isWaitingForCompression = false, compactingModelName = if (compressing) modelName else "")
            return
        }
        val current = conversationState(conversationId) ?: return
        updateConversation(conversationId, current.copy(isCompressingContext = compressing, isWaitingForCompression = false, compactingModelName = if (compressing) modelName else ""))
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationState(conversationId) ?: return
        if (state.isStreaming && !isStreaming) {
            ProviderBalanceStore.requestRefresh(scope)
        }
        updateConversation(
            conversationId,
            state.copy(
                isStreaming = isStreaming,
                // Normal, failed, stopped and direct-media completion all consume first-turn NONE.
                contextHasStarted = state.contextHasStarted || !isStreaming,
                // A parent terminal event does not determine independently owned child status.
                // Keep the last reported child snapshot; the task-group registry owns liveness.
                childContexts = state.childContexts,
                isWaitingForCompression = isStreaming && state.isWaitingForCompression,
                isPaused = if (isStreaming) state.isPaused else false,
                // Show the percentage against the window this run actually flies with;
                // a settled run releases the override back to the picker's window.
                activeRunContextWindow = if (isStreaming) runContextWindows[runId] else null,
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
        return conversationState(conversationId) ?: emptyChatState(defaultThinkingEnabled)
    }

    private fun conversationState(id: String?): AgentChatHomeUiState? {
        if (id == null) return null
        val cached = conversationsById[id] ?: return null
        if (cached.conversationContentLoaded) return cached
        val loaded = AgentConversationStore.loadConversation(appContext, id)?.let(::orderedTerminalState)
            ?.withCurrentGptSpeedBinding() ?: return null
        val current = conversationsById[id] ?: return null
        if (current !== cached) return conversationState(id)
        conversationsById = conversationsById + (id to loaded)
        return loaded
    }

    private fun releasePersistedConversationContent(saved: Map<String, AgentChatHomeUiState>) {
        if (conversationArchiveBusy) return
        val runOwners = runConversationIds.values.toSet()
        conversationsById = conversationsById.mapValues { (id, state) ->
            val pinned = id == selectedConversationId || id in runOwners || state.isStreaming || state.isPaused ||
                state.isCompressingContext || state.isWaitingForCompression || state.messageEdit != null ||
                state.pendingImages.isNotEmpty() || state.pendingFileReferences.isNotEmpty() ||
                state.pendingConversationMentions.isNotEmpty() || state.input.isNotEmpty()
            if (!pinned && state.conversationContentLoaded && saved[id] === state) {
                AgentConversationStore.unloadedPreview(state)
            } else state
        }
    }

    private fun observeConversationSummaryDates() {
        scope.launch(Dispatchers.Main.immediate) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    // Summary labels are cached; invalidate even when no conversation changes.
                    refreshConversationSummaries()
                }
            }
            appContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_DATE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_LOCALE_CHANGED)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                appContext.unregisterReceiver(receiver)
            }
        }
    }

    private fun refreshConversationSummaries() {
        if (runReplayBatch.isActive) return
        StreamUiEventDiagnostics.measure("ui.summaries.refresh", conversationsById.size.toLong()) {
            refreshConversationSummariesNow()
        }
    }

    private fun refreshConversationSummariesNow() {
        // Date labels depend on the local day (including year), locale, zone and clock format.
        // Capture once per refresh, not once per conversation; midnight/config changes invalidate
        // all entries without changing the createdAt-before-updatedAt ordering/label semantics.
        val nowMillis = System.currentTimeMillis()
        val timeZone = java.util.TimeZone.getDefault()
        val locale = appContext.resources.configuration.locales[0]
        val use24HourClock = DateFormat.is24HourFormat(appContext)
        val environment = ConversationSummaryEnvironment(
            // Only the locale affects labels; orientation/uiMode must not invalidate the cache.
            configuration = appContext.resources.configuration.locales.toLanguageTags(),
            localDay = java.time.Instant.ofEpochMilli(nowMillis).atZone(timeZone.toZoneId()).toLocalDate().toEpochDay(),
            timeZone = timeZone,
            use24HourClock = use24HourClock,
        )
        conversationSummaryCache.retain(conversationsById.keys)
        val summaries = conversationsById.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, AgentChatHomeUiState>> { (id, _) ->
                    id in conversationPinned
                }.thenByDescending { (id, _) ->
                    conversationCreatedAt[id] ?: conversationUpdatedAt[id] ?: 0L
                },
            )
            .map { (id, state) ->
                val lastMessage = state.messages.lastOrNull()
                val key = ConversationSummaryKey(
                    title = conversationTitles[id].orEmpty(),
                    previewInput = conversationSummaryPreviewInput(lastMessage),
                    createdAtMillis = conversationCreatedAt[id],
                    updatedAtMillis = conversationUpdatedAt[id],
                    isPinned = id in conversationPinned,
                    isActiveRun = state.isStreaming,
                    hasCompletionMarker = id in conversationCompletionMarkers,
                    folderId = conversationFolderIds[id],
                    environment = environment,
                )
                // The open conversation's preview changes on every token. Keep the last
                // summary while it is streaming so the manage list is not rebuilt per delta,
                // but never keep its old day/locale/zone after the label environment changes.
                val cached = if (state.isStreaming && id == selectedConversationId) {
                    conversationSummaryCache.current(id, environment)
                } else {
                    null
                }
                cached ?: conversationSummaryCache.getOrBuild(id, key) {
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
                                    SystemNoticeCode.Completed -> R.string.system_notice_completed
                                },
                            )
                            is ThinkingMessageUi -> appContext.getString(R.string.conversation_preview_reasoning)
                            is ToolActivityMessageUi -> appContext.getString(
                                R.string.conversation_preview_tool_call,
                                lastMessage.toolName,
                            )
                            else -> appContext.getString(R.string.conversation_preview_empty)
                        }.take(MAX_PREVIEW_CHARS),
                        timeLabel = (conversationCreatedAt[id] ?: conversationUpdatedAt[id])?.let { timestamp ->
                            ConversationTimeLabels.label(
                                timestampMillis = timestamp,
                                nowMillis = nowMillis,
                                locale = locale,
                                timeZone = timeZone,
                                use24HourClock = use24HourClock,
                                yesterdayLabel = appContext.getString(R.string.time_yesterday),
                                recentLabel = appContext.getString(R.string.time_recent),
                            )
                        } ?: appContext.getString(R.string.time_recent),
                        updatedAtMillis = conversationUpdatedAt[id] ?: 0L,
                        createdAtMillis = conversationCreatedAt[id] ?: conversationUpdatedAt[id] ?: 0L,
                        mode = ConversationModeUi.Chat,
                        isPinned = id in conversationPinned,
                        isActiveRun = state.isStreaming,
                        hasCompletionMarker = id in conversationCompletionMarkers,
                        folderId = conversationFolderIds[id],
                        isToday = ConversationTimeLabels.isToday(
                            timestampMillis = conversationCreatedAt[id] ?: conversationUpdatedAt[id] ?: 0L,
                            nowMillis = nowMillis,
                            timeZone = timeZone,
                        ),
                    )
                }
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

    private data class ConversationSaveSnapshot(
        val selected: String?,
        val conversations: Map<String, AgentChatHomeUiState>,
        val titles: Map<String, String>,
        val timestamps: Map<String, Long>,
        val folderIds: Map<String, String>,
        val pinnedIds: Set<String>,
        val completionMarkerIds: Set<String>,
        val folders: List<ConversationFolderUi>,
        val retired: ConversationTokenUsageUi,
        val retiredConversations: Int,
        val retiredMessages: Int,
        val retiredHeatmap: Map<java.time.LocalDate, Int>,
    ) {
        fun mergeRetiredFrom(older: ConversationSaveSnapshot): ConversationSaveSnapshot = copy(
            retired = ConversationTokenUsageUi(
                inputTokens = retired.inputTokens + older.retired.inputTokens,
                outputTokens = retired.outputTokens + older.retired.outputTokens,
                cachedTokens = retired.cachedTokens + older.retired.cachedTokens,
            ),
            retiredConversations = retiredConversations + older.retiredConversations,
            retiredMessages = retiredMessages + older.retiredMessages,
            retiredHeatmap = (retiredHeatmap.keys + older.retiredHeatmap.keys).associateWith {
                (retiredHeatmap[it] ?: 0) + (older.retiredHeatmap[it] ?: 0)
            },
        )
    }

    private fun persistConversations(allowArchive: Boolean = false, onSaved: (() -> Unit)? = null): Deferred<Boolean> {
        if (conversationArchiveBusy && !allowArchive) {
            return kotlinx.coroutines.CompletableDeferred<Boolean>().also { deferredArchiveSaves += it to onSaved }
        }
        if (io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance) {
            return kotlinx.coroutines.CompletableDeferred(false)
        }
        return synchronized(persistenceLock) {
            val snapshot = ConversationSaveSnapshot(
                selectedConversationId, conversationsById, conversationTitles, conversationUpdatedAt,
                conversationFolderIds, conversationPinned, conversationCompletionMarkers, conversationFolders,
                pendingRetiredUsage, pendingRetiredConversations, pendingRetiredMessages, pendingRetiredHeatmap,
            )
            pendingRetiredUsage = ConversationTokenUsageUi()
            pendingRetiredConversations = 0
            pendingRetiredMessages = 0
            pendingRetiredHeatmap = emptyMap()
            persistenceQueue.submit(snapshot, onSaved)
        }
    }

    private suspend fun writeConversationSnapshot(snapshot: ConversationSaveSnapshot): Boolean {
        var retirementCommitted = false
        try {
            conversationPersistenceMutex.withLock {
                AgentConversationStore.save(
                    context = appContext, selectedConversationId = snapshot.selected,
                    conversationsById = snapshot.conversations, titles = snapshot.titles,
                    updatedAt = snapshot.timestamps, folderIds = snapshot.folderIds,
                    pinnedIds = snapshot.pinnedIds, completionMarkerIds = snapshot.completionMarkerIds,
                    folders = snapshot.folders,
                )
                SettingsDataStore.addRetiredUsage(
                    inputTokens = snapshot.retired.inputTokens,
                    outputTokens = snapshot.retired.outputTokens,
                    cachedTokens = snapshot.retired.cachedTokens,
                    conversations = snapshot.retiredConversations,
                    messages = snapshot.retiredMessages,
                    heatmap = snapshot.retiredHeatmap,
                )
                retirementCommitted = true
            }
            withContext(Dispatchers.Main.immediate) {
                releasePersistedConversationContent(snapshot.conversations)
                pendingSubAgentDraftBindings.toMap().forEach { (id, draft) ->
                    if (id in snapshot.conversations && id in conversationsById) {
                        if (conversationSubAgentPreferences.confirmBoundDraft(draft, SubAgentConfigKey.Conversation(id))) {
                            pendingSubAgentDraftBindings.remove(id)
                        }
                    }
                }
            }
            lastConversationPersistenceError = null
            return true
        } catch (cancelled: CancellationException) {
            if (!retirementCommitted) restorePendingRetiredUsage(snapshot)
            throw cancelled
        } catch (failure: Exception) {
            if (!retirementCommitted) restorePendingRetiredUsage(snapshot)
            AndroidAgentLogger.error(
                "Agent conversation persistence failed: type=${failure.safeLogType()} message=${failure.message}"
            )
            lastConversationPersistenceError = failure.message
            return false
        }
    }

    private suspend fun restorePendingRetiredUsage(snapshot: ConversationSaveSnapshot) {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
            synchronized(persistenceLock) {
                pendingRetiredUsage = ConversationTokenUsageUi(
                    inputTokens = pendingRetiredUsage.inputTokens + snapshot.retired.inputTokens,
                    outputTokens = pendingRetiredUsage.outputTokens + snapshot.retired.outputTokens,
                    cachedTokens = pendingRetiredUsage.cachedTokens + snapshot.retired.cachedTokens,
                )
                pendingRetiredConversations += snapshot.retiredConversations
                pendingRetiredMessages += snapshot.retiredMessages
                snapshot.retiredHeatmap.forEach { (day, count) ->
                    pendingRetiredHeatmap = pendingRetiredHeatmap + (day to ((pendingRetiredHeatmap[day] ?: 0) + count))
                }
            }
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
        // Bind the local preview immediately to the selected conversation assistant. Remote
        // preference sync may suspend/fail; it must not keep the previous assistant's count.
        refreshRequestOverhead()
        if (AssistantRepository.activeId.value == assistantId) {
            refreshMemory()
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
            ).withCurrentGptSpeedBinding()

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
        // A 120 Hz frame is 8.33 ms; reserve most of it for Compose/layout/draw.
        const val STREAM_EVENT_FRAME_BUDGET_NS = 2_000_000L

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
        autoCompressPreference.setEnabled(enabled)
    }

    fun selectContextTask(taskId: String?, capturedOwnerId: String?) {
        if (capturedOwnerId == null || capturedOwnerId != selectedConversationId) return
        ownerContext(capturedOwnerId).select(capturedOwnerId, taskId)
        publishOwnerContext(capturedOwnerId)
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
            val ownerId = selectedConversationId
            if (child == null || child.status != "running" || child.role in setOf("image_generation", "video_generation") || ownerId == null) {
                Toast.makeText(appContext, "该子任务已结束、尚未执行或不支持对话压缩；不会改为压缩主代理。", Toast.LENGTH_LONG).show()
                onFinished(false)
                return
            }
            onFinished(true)
            scope.launch(Dispatchers.IO) {
                val sent = runCatching {
                    val config = if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) null else
                        resolveCompressModelConfig(null, providerId, modelId, manual = true)
                    AgentChildTaskGroups.requestCompact(ownerId, targetId, keepRecentFor(), config)
                }.getOrDefault(false)
                if (!sent) withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "未确认子任务接受压缩请求，它可能已结束；不会改为压缩其它代理。", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        persistCompressPreferences(providerId, modelId)
        val runInFlight = homeState.isStreaming || homeState.isPaused
        val busyWithOtherConversation = compressionJob?.isActive == true &&
            compressionJobConversationId != selectedConversationId
        if (compressionJob?.isActive == true && !busyWithOtherConversation) {
            onFinished(true)
            return
        }
        if (homeState.isCompressingContext || homeState.isWaitingForCompression) {
            onFinished(true)
            return
        }
        val keepRecentMessages = keepRecentFor()
        if (!runInFlight &&
            io.github.mangi.eta.agent.model.AgentCompressionBoundary.selectStart(homeState.history,
                compressionBoundaryWindow(boundCompressionWindow(homeState))) <= 0
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
        val snapshot = conversationState(conversationId) ?: homeState
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
        val pending = pendingManualCompress
        return conversationCompressionBlocksSend(
            conversationId = selectedConversationId,
            isCompressingContext = homeState.isCompressingContext,
            jobActive = compressionJob?.isActive == true,
            jobConversationId = compressionJobConversationId,
            hasPending = pending != null,
            pendingConversationId = pending?.conversationId,
        )
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
        val pending = pendingManualCompress
        return keepsCompressingIndicator(
            conversationId = conversationId,
            jobActive = compressionJob?.isActive == true,
            jobConversationId = compressionJobConversationId,
            hasPending = pending != null,
            pendingConversationId = pending?.conversationId,
        )
    }

    private fun startPendingManualCompress() {
        val request = pendingManualCompress ?: return
        // A running job may be another conversation's auto compaction (or the caller's own
        // finally block); the new job joins it below, so queue behind it instead of dropping.
        pendingManualCompress = null
        val resumeAfter = request.resumeAfter
        val previous = compressionJob
        compressionJobConversationId = request.conversationId
        compressionJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                val snapshot = withContext(Dispatchers.Main) {
                    request.conversationId?.let { conversationState(it) }
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
                        compressionBoundaryWindow(fallback?.contextWindow)) <= 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, appContext.getString(
                            R.string.compress_conversation_nothing_to_compress), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                var summaryCommitted = false
                val compressed = tryCompressHistory(
                    history = originalHistory,
                    compressModelConfig = modelConfig,
                    sourceModelConfig = fallback,
                    keepRecent = request.keepRecent,
                    conversationId = request.conversationId,
                    contextWindow = fallback?.contextWindow,
                    onSummaryCommitted = { summaryCommitted = true },
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
                        summaryCommitted,
                    )
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (pendingManualCompress == null) {
                        if (resumeAfter) {
                            resumeLastTurnAfterCompress(request.conversationId)
                        }
                        val resumed = request.conversationId?.let { conversationState(it)?.isStreaming } == true
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
        val state = conversationState(id) ?: return
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
        summaryCommitted: Boolean = false,
    ) {
        if (compressedHistory == originalHistory) return
        if (!summaryCommitted) {
            val current = if (conversationId == null) homeState.takeIf { selectedConversationId == null }
                else conversationState(conversationId)
            if (current == null || current.isStreaming || current.isPaused || current.history != originalHistory) return
            val pruned = current.copy(history = compressedHistory)
            if (conversationId == null) homeState = pruned else updateConversation(conversationId, pruned)
            persistConversations()
            return
        }
        if (conversationId != null) {
            val current = conversationState(conversationId) ?: return
            if (current.isStreaming || current.isPaused) return
            if (current.history != originalHistory) return
            updateConversation(
                conversationId,
                current.copy(
                    history = compressedHistory,
                    livePromptTokens = null,
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
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
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
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

/**
 * 运行结束时是否保留「正在压缩」提示。只认属于这个会话的压缩任务或待办：
 * 别的会话在压缩时，这里结束的回复不能被标成压缩中，否则那边压完不会回来清它。
 * 会话未知（null）时保持旧行为，按匹配处理。
 */
internal fun keepsCompressingIndicator(
    conversationId: String?,
    jobActive: Boolean,
    jobConversationId: String?,
    hasPending: Boolean,
    pendingConversationId: String?,
): Boolean {
    fun matches(owner: String?) = conversationId == null || owner == null || owner == conversationId
    if (jobActive && matches(jobConversationId)) return true
    return hasPending && matches(pendingConversationId)
}

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

/** Full provider/model value comparison, never an ID-only or picker-option comparison.
 * Repository emissions are immutable domain data classes (including nested model/config lists).
 * A settings-only preferences re-emission retains the providers reference, so the common path
 * does no list walk. A newly loaded list is compared in order and includes every config field.
 */
internal fun runtimeSelectionUnchanged(
    previous: Triple<String?, String?, List<io.github.mangi.eta.data.model.ProviderSetting>>,
    next: Triple<String?, String?, List<io.github.mangi.eta.data.model.ProviderSetting>>,
): Boolean = previous.first == next.first && previous.second == next.second &&
    (previous.third === next.third || previous.third == next.third)

internal data class ConversationSummaryEnvironment(
    val configuration: String,
    val localDay: Long,
    val timeZone: java.util.TimeZone,
    val use24HourClock: Boolean,
)

/** Do not retain attachments/history or invalidate on usage, thinking text and reveal flags. */
internal data class ConversationSummaryPreviewInput(val kind: String, val text: String = "", val notice: SystemNoticeCode? = null)

internal fun conversationSummaryPreviewInput(message: AgentChatMessageUi?): ConversationSummaryPreviewInput = when (message) {
    is UserMessageUi -> ConversationSummaryPreviewInput("user", message.content)
    is AgentMessageUi -> ConversationSummaryPreviewInput("assistant", message.content)
    is SystemNoticeMessageUi -> ConversationSummaryPreviewInput("notice", notice = message.code)
    is ThinkingMessageUi -> ConversationSummaryPreviewInput("thinking")
    is ToolActivityMessageUi -> ConversationSummaryPreviewInput("tool", message.toolName)
    else -> ConversationSummaryPreviewInput("empty")
}

/** Only inputs read by the existing summary projection, not the full transcript/history. */
internal data class ConversationSummaryKey(
    val title: String,
    val previewInput: ConversationSummaryPreviewInput,
    val createdAtMillis: Long?,
    val updatedAtMillis: Long?,
    val isPinned: Boolean,
    val isActiveRun: Boolean,
    val hasCompletionMarker: Boolean,
    val folderId: String?,
    val environment: ConversationSummaryEnvironment,
)

/** Main-owned, one entry per live conversation; deleted/archive-replaced IDs are pruned. */
internal class ConversationSummaryCache {
    private data class Entry(val key: ConversationSummaryKey, val summary: ConversationSummaryUi)
    private val entries = mutableMapOf<String, Entry>()

    fun current(id: String, environment: ConversationSummaryEnvironment): ConversationSummaryUi? =
        entries[id]?.takeIf { it.key.environment == environment }?.summary

    fun retain(ids: Set<String>) {
        entries.keys.retainAll(ids)
    }

    fun getOrBuild(id: String, key: ConversationSummaryKey, build: () -> ConversationSummaryUi): ConversationSummaryUi {
        val previous = entries[id]
        if (previous?.key == key) return previous.summary
        return build().also { entries[id] = Entry(key, it) }
    }
}
