package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionCoordinator
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.config.InteractiveModePreference
import io.github.mangi.eta.agent.delegation.*
import io.github.mangi.eta.agent.browser.ChildBrowserSession
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Blocking execution of one parent. A child group is a separate owner, not a controller binding. */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (AgentRuntimeWire.RunRequest, AgentRuntimeWire.RunResult, List<AgentEvent>) -> Unit,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )
    private val appContext = context.applicationContext

    fun execute(session: AgentRuntimeSession, request: AgentRuntimeWire.RunRequest): Outcome {
        val runController = session.controller
        val archivedEvents = mutableListOf<AgentEvent>()
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var localTools: AgentLocalTools? = null
        var deliveryFailure: String? = null
        var cleanupFailure: String? = null
        var modelCompleted = false
        var virtualDeliveryCompleted = false
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var toolsOwner: AgentChildToolOwnership? = null
        var groupGeneration: String? = null
        var replacementGeneration: String? = null
        val registeredChildGenerations = mutableListOf<String>()
        val childContextSink = AtomicReference<((SubAgentContextStats) -> Unit)?>(null)
        // “每次询问”选前台后补给服务的 ToolStarted 来源。
        val foregroundReplay = AgentForegroundReplay()
        val childSessionId = request.effectiveModelSessionId
        val allowBrowser = request.config.browserTools
        val allowTerminal = request.config.terminalTools
        val allowDirect = request.config.deviceDirectTools
        val allowSensitiveRead = request.config.deviceSensitiveReadTools
        val allowSensitiveAction = request.config.deviceSensitiveActionTools
        // Parent-only prompt policy, frozen for this run rather than re-read between rounds.
        val interactiveModeEnabled = InteractiveModePreference.read()
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        var unownedSkillRoot: java.io.File? = null
        val timing = AgentRunTiming(AndroidAgentLogger)
        childContextSink.set { stats ->
            if (!session.isTerminal) acceptEvent(session, AgentEvent.ChildContextUpdated(stats),
                archivedEvents, entrySurfaceGuard, checkpointRecorder)
        }
        session.childCompactor = { taskId, keep, model ->
            AgentChildTaskGroups.requestCompact(childSessionId, taskId, keep, model)
        }
        var result = try {
            runController.throwIfCancelled()
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            entrySurfaceGuard = EntrySurfaceGuard.from(request.handoff, AndroidAgentLogger) {
                EtaAssistantOverlayService.dismissForForegroundOperation(appContext)
            }
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(appContext.cacheDir, AgentHttpClient.client)
            val assistant = requireNotNull(AssistantRepository.currentProfile(request.assistantId)) { "任务所属助手不存在，请重新发起任务" }
            val enabledSkillIds = assistant.enabledSkillIds.toSet()
            val (runSkillsRoot, runSkillEntries) = SkillRuntime.createRunSkills(appContext, assistant.id,
                skillIndexService.listSkillsForManagement(forceRefresh = true)
                    .filter { it.installed && it.id in enabledSkillIds }
                    .filter { SkillCompatibilityChecker.evaluate(it).available })
            unownedSkillRoot = runSkillsRoot
            val skillContext = SkillContext(installedSkills = runSkillEntries)
            val memoryEnabled = assistant.memoryEnabled
            val memoryContext = if (memoryEnabled) runCatching {
                AgentMemoryContextBuilder.build(AgentMemoryRepository.snapshot(assistant.id), request.config.contextWindow)
            }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_memory_context_failed") { "Agent memory context unavailable: type=${throwable.safeLogType()}" }
                AgentMemoryContextBuilder.empty(request.config.contextWindow)
            } else AgentMemoryContext.DISABLED
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") { "MCP tool snapshot unavailable: type=${throwable.safeLogType()}" }
                    McpRunSnapshot.EMPTY
                }
            }
            val runSurface = session.taskSurfaceMode
            // 副屏工具只在明确选择后台后暴露；ASK 首轮由执行位置弹窗决定后续轮次。
            val runVirtualDisplay = {
                session.taskSurfaceMode == io.github.mangi.eta.agent.device.AgentTaskSurfaceMode.BACKGROUND
            }
            val mcpTools = JSONArray().also(mcpSnapshot::appendModelTools)
            val executor = AgentLocalTools(
                context = appContext, logger = AndroidAgentLogger, browserRunId = request.runId,
                browserConversationId = request.handoff?.takeIf {
                    it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE
                }?.let { AgentUiHandoffPayload.from(it.payload).conversationId }.orEmpty(),
                frozenSurface = runSurface,
                chooseSurface = { toolName, toolsClosed ->
                    // 先写会话再等弹窗退场；返回值取自会话，工具侧与会话保持一致。
                    AgentTaskSurfaceChoice.choose(
                        session = session,
                        await = {
                            io.github.mangi.eta.agent.device.AgentTaskPrompt.await(
                                runId = request.runId,
                                cancelled = { toolsClosed() || runController.isCancelled },
                                show = { prompt -> io.github.mangi.eta.ui.AgentTaskSurfaceDialogActivity.launch(appContext, prompt.id) },
                            )
                        },
                        awaitHostHidden = { io.github.mangi.eta.agent.device.AgentTaskPrompt.awaitHostHidden() },
                        // 选前台前 ToolStarted 已发出但服务没处理：只补给服务做悬浮窗与前台记录，不进对话记录和回放。
                        onForegroundResolved = {
                            onAcceptedEvent(foregroundReplay.startedEvent(toolName), entrySurfaceGuard)
                        },
                    )
                },
                browserToolsEnabled = { allowBrowser && currentPermissions().browserTools },
                terminalToolsEnabled = { allowTerminal && currentPermissions().terminalTools },
                deviceDirectToolsEnabled = { allowDirect && currentPermissions().deviceDirectTools },
                deviceSensitiveReadToolsEnabled = { allowSensitiveRead && currentPermissions().deviceSensitiveReadTools },
                deviceSensitiveActionToolsEnabled = { allowSensitiveAction && currentPermissions().deviceSensitiveActionTools },
                memoryToolsEnabled = { AssistantRepository.currentProfile(assistant.id)?.memoryEnabled == true },
                screenshotExcludedPackages = { entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty() },
                beforeToolExecution = { toolName ->
                    val requiresAccessibility = AgentToolRequirements.requiresAccessibility(toolName)
                    if (!requiresAccessibility && !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName)) {
                        ToolExecutionDecision.Allow
                    } else {
                        val accessibility = if (requiresAccessibility) AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext) else null
                        when {
                            accessibility != null && !accessibility.available -> ToolExecutionDecision.Reject(accessibility.code, accessibility.message)
                            entrySurfaceGuard?.dismissOnce() == false -> ToolExecutionDecision.Reject("ENTRY_SURFACE_NOT_READY", "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用")
                            else -> ToolExecutionDecision.Allow
                        }
                    }
                },
                skillIndexService = skillIndexService, skillLoader = skillLoader,
                skillResourceReader = skillResourceReader, githubSkillSource = githubSkillSource,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                runSkillEntries = skillContext.installedSkills, memoryAssistantId = assistant.id,
                runSkillsRoot = runSkillsRoot, pendingSkillConflict = pendingSkillConflict,
            )
            localTools = executor
            unownedSkillRoot = null
            val routingExecutor = RoutingToolExecutor(local = executor, mcp = McpToolExecutor(mcpSnapshot))
            val ownership = AgentChildToolOwnership { routingExecutor.close() }
            toolsOwner = ownership
            toolsBinding = runController.register { ownership.release() }
            timing.preparationFinished(skillContext.installedSkills.size)

            // Retain all exact-selection candidates, including explicit unavailable reasons.
            // This never re-resolves a retained child's healthy configuration for continue.
            val ownerKey = SubAgentConfigKey.Conversation(request.effectiveModelSessionId)
            val childConfig = ConversationSubAgentPreferences().snapshot(ownerKey)
            val childCandidates = runBlocking { ChildWorkerConfigResolver.resolve(childSessionId, childConfig, parentConfig = request.config) }
            val childDispatchPlan = AgentChildTaskGroups.ordinaryDispatchPlan(childSessionId, childCandidates)
            val workspaceEnvironment = LinuxEnvironmentSettingsRepository.current(appContext).wireName
            val childWorkspace = if (allowTerminal && currentPermissions().terminalTools) SubAgentWorkspace(
                appContext, executor, ownerId = childSessionId, initialEnvironment = workspaceEnvironment,
                environment = { when (LinuxEnvironmentSettingsRepository.current(appContext)) {
                    LinuxDistribution.ALPINE -> "alpine"
                    LinuxDistribution.DEBIAN -> "debian"
                } },
                legacyIds = { project -> AgentChildTaskGroups.ownedWorkspaceIds(childSessionId, project, workspaceEnvironment) },
            ) else null
            fun createChildGroup(candidates: List<ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>>): String? {
                val configuredChildren = AgentChildWorkerAvailability.configuredChildren(candidates)
                val childModels = configuredChildren.map { it.second }
                if (childModels.isEmpty()) return null
                val frozenParallelLimits = childModels.map { childConfig.parallelLimit(SubAgentParallelModel(it.providerId, it.model)) }
                // Retain BEFORE construction, which allocates scheduler and pool leases. Transfer only after registration.
                val childLease = checkNotNull(ownership.retain()) { "父任务已终止，无法创建子任务" }
                var childOwnershipTransferred = false
                var children: SubAgentCoordinator? = null
                try {
                    val workspace = childWorkspace
                    // Each invocation/continuation owns its own ephemeral browser; never forward it
                    // to the parent's AgentLocalTools (which is bound to the parent's browser page).
                    fun runTextChild(config: AgentModelClient.ModelConfig, prompt: String,
                        controller: AgentRunController, project: String, id: String?, writable: Boolean,
                        progress: (AgentEvent) -> Unit = {}): String {
                        val browser = if (io.github.mangi.eta.agent.browser.ChildBrowserPolicy.sessionAllowed(
                                allowBrowser && currentPermissions().browserTools, controller.childBrowserAccess.wire))
                            ChildBrowserSession(appContext, controller) { allowBrowser && currentPermissions().browserTools }
                        else null
                        try {
                            if (id != null) {
                                val backend = requireNotNull(workspace)
                                return SubAgentRunner.run(config, prompt, SubAgentWorkspace.childTools(writable),
                                    backend.childExecutor(project, id, writable, controller), controller,
                                    workspaceMode = true, writable = writable, sessionId = childSessionId,
                                    onProgress = progress, browserExecutor = browser?.executor)
                            }
                            val readTools = SubAgentTools.filter(AgentToolCatalog.build(
                                terminalTools = allowTerminal && currentPermissions().terminalTools,
                                browserTools = false, // Installed separately with a task-owned executor.
                                deviceDirectTools = allowDirect && currentPermissions().deviceDirectTools,
                                deviceSensitiveReadTools = allowSensitiveRead && currentPermissions().deviceSensitiveReadTools,
                                memoryTools = memoryEnabled,
                                capabilities = AgentToolCapabilities.capture(appContext).copy(virtualDisplay = runVirtualDisplay())))
                            return SubAgentRunner.run(config, prompt, readTools, executor, controller,
                                sessionId = childSessionId, onProgress = progress, browserExecutor = browser?.executor)
                        } finally {
                            browser?.release()
                        }
                    }
                    var generationForCallback: String? = null
                    val poolScope = "${request.effectiveModelSessionId}:${request.runId}:${UUID.randomUUID()}"
                    children = SubAgentCoordinator(childModels,
                        roles = configuredChildren.map { it.first.role },
                        workerIds = configuredChildren.map { it.first.id },
                        workerNames = configuredChildren.map { it.first.name },
                        workerModelIds = configuredChildren.map { it.first.modelId },
                        modelParallelLimits = frozenParallelLimits,
                        poolScope = poolScope,
                        allowTimeoutContinuation = true,
                        diagnostics = SubAgentDiagnostics(request.runId),
                        workspace = workspace,
                        onTaskChanged = { generationForCallback?.let(AgentChildTaskGroups::onTaskChanged) },
                        prepareManualCompactor = { config -> io.github.mangi.eta.agent.model.AgentCompressionEndpoint.apply(
                            AgentRuntimePolicy.forCompression(config),
                            Prefs.localAgentPreferences()?.getString(Prefs.Keys.AGENT_MANUAL_COMPRESS_ENDPOINT_MODE, null)) },
                        executeImageChild = { config, prompt, controller, imageOptions -> SubAgentMediaRunner.run(
                            appContext, childSessionId, config, prompt, controller, video = false, imageOptions = imageOptions) },
                        executeVideoChild = { config, prompt, controller -> SubAgentMediaRunner.run(
                            appContext, childSessionId, config, prompt, controller, video = true) },
                        onContext = { stats -> childContextSink.get()?.invoke(stats) },
                        executeObservedChild = { config, prompt, controller, project, id, writable, progress ->
                            runTextChild(config, prompt, controller, project, id, writable, progress)
                        },
                        executeWorkspaceChild = { config, prompt, controller, project, id, writable ->
                            runTextChild(config, prompt, controller, project, id, writable)
                        },
                    ) { config, prompt, controller ->
                        runTextChild(config, prompt, controller, "", null, false)
                    }
                    val registered = AgentChildTaskGroups.register(appContext, request.effectiveModelSessionId, request.runId, children,
                        releaseTools = childLease::close, workspaceEnvironment = workspaceEnvironment,
                        workers = AgentChildWorkerAvailability.workers(candidates))
                    if (registered == null) error("无法启动子代理前台执行服务，请返回 Eta 后重试")
                    childOwnershipTransferred = true
                    generationForCallback = registered
                    registeredChildGenerations.add(registered)
                    AgentChildRunControl.registered(session, childSessionId, registered)
                    runController.throwIfCancelled()
                    return registered
                } finally {
                    if (!childOwnershipTransferred) {
                        try { children?.close() }
                        finally { childLease.close() }
                    }
                }
            }
            // Frozen configuration does not imply historical execution ownership: ordinary work
            // gets a fresh coordinator. Only explicit replacement may use current user settings.
            groupGeneration = createChildGroup(childDispatchPlan.ordinary)
            replacementGeneration = if (childDispatchPlan.ordinary === childDispatchPlan.replacement) groupGeneration
                else createChildGroup(childDispatchPlan.replacement)
            val toolChildren = AgentChildWorkerAvailability.configuredChildren(
                if (groupGeneration != null) childDispatchPlan.ordinary else childDispatchPlan.replacement)
            if (groupGeneration != null || replacementGeneration != null) {
                SubAgentTools.appendTo(mcpTools, toolChildren.mapIndexed { i, (slot, model) ->
                    SubAgentPreferences.workerDescription(slot, i + 1, model,
                        childConfig.parallelLimit(SubAgentParallelModel(model.providerId, model.model)))
                }, workspaceEnabled = childWorkspace != null)
            } else {
                ExistingChildTaskTools.appendTo(mcpTools)
            }
            val questionCoordinator = AgentQuestionCoordinator(runController) { event ->
                acceptEvent(session, event, archivedEvents, entrySurfaceGuard, checkpointRecorder)
            }
            session.questionCoordinator = questionCoordinator
            val delegatedExecutor = AgentModelClient.ToolExecutor { call ->
                runController.throwIfCancelled()
                if (call.name == "ask_user") {
                    val question = AgentQuestionCodec.parseArguments(call.argumentsJson,
                        conversationId = request.effectiveModelSessionId,
                        runId = request.runId, toolCallId = call.id,
                        questionId = "question-${UUID.randomUUID()}", createdAtMillis = System.currentTimeMillis())
                    val answer = try { questionCoordinator.awaitAnswer(question) }
                    catch (failure: Exception) {
                        runController.throwIfCancelled()
                        throw io.github.mangi.eta.agent.question.AgentQuestionInterruptedException(failure)
                    }
                    if (answer == null) {
                        runController.throwIfCancelled()
                        throw io.github.mangi.eta.agent.question.AgentQuestionInterruptedException()
                    }
                    AgentModelClient.ToolResult(AgentQuestionCodec.resultJson(question, answer).toString())
                } else if (call.name == "manage_agent_workspace") {
                    val backend = childWorkspace
                    val payload = try { AgentWorkspaceAccessPolicy.execute(
                        argumentsJson = call.argumentsJson,
                        requestAllowsTerminal = allowTerminal,
                        runtimeAllowsTerminal = currentPermissions().terminalTools,
                        backendAvailable = backend != null,
                        ownsWorkspace = { project, id ->
                            requireNotNull(backend).ownsWorkspace(project, id)
                        },
                        backendOperation = { workspaceRequest ->
                            requireNotNull(backend).operation(
                                workspaceRequest.project, workspaceRequest.action, workspaceRequest.workspaceId,
                                org.json.JSONObject().put("offset", workspaceRequest.offset).put("limit", workspaceRequest.limit),
                            )
                        },
                    ) } catch (_: io.github.mangi.eta.agent.delegation.WorkspaceOwnershipException) {
                        runController.throwIfCancelled()
                        org.json.JSONObject().put("ok", false).put("code", "WORKSPACE_OWNERSHIP_STORE_UNAVAILABLE")
                            .put("shell_executed", false).put("message", "工作区归属账本不可用，未执行 shell。")
                    }
                    AgentModelClient.ToolResult(payload.toString(), sensitive = true)
                } else if (call.name in SubAgentTools.names) {
                    // Even with no new coordinator, explicit continue adopts into THIS run.
                    AgentChildTaskGroups.execute(childSessionId, groupGeneration, call, currentRunId = request.runId,
                        replacementGeneration = replacementGeneration)
                } else routingExecutor.execute(call)
            }
            val compactPolicy = runBlocking { AgentCompressionPolicy.resolve(request.config) }
            val promptWithChildHandoff = AgentChildTaskHandoff.appendToPrompt(
                AgentChildWorkerAvailability.appendToPrompt(request.prompt, childDispatchPlan), childSessionId)
            runController.throwIfCancelled()
            val completedResponse = AgentModelClient.complete(
                config = request.config, sessionId = request.effectiveModelSessionId,
                capabilitiesProvider = { AgentToolCapabilities.captureForRound(appContext).copy(virtualDisplay = runVirtualDisplay()) },
                prompt = promptWithChildHandoff, toolExecutor = delegatedExecutor, images = request.images,
                history = request.history, skipHistoryTrimming = true,
                compactionArchive = io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, request.effectiveModelSessionId),
                turnId = request.effectiveTurnId, runController = runController,
                calibratedInputTokens = request.calibratedInputTokens,
                allowUnmeasuredContextSend = request.allowUnmeasuredContextSend,
                skillContext = skillContext, memoryContext = memoryContext,
                skillContextProvider = {
                    check(AssistantRepository.currentProfile(assistant.id) != null) { "任务所属助手已删除" }
                    SkillContext(installedSkills = executor.currentSkillEntries())
                },
                memoryContextProvider = {
                    val current = requireNotNull(AssistantRepository.currentProfile(assistant.id)) { "任务所属助手已删除" }
                    if (!current.memoryEnabled) AgentMemoryContext.DISABLED
                    else runCatching { AgentMemoryContextBuilder.build(AgentMemoryRepository.snapshot(current.id), request.config.contextWindow) }
                        .getOrElse { AgentMemoryContextBuilder.empty(request.config.contextWindow) }
                },
                additionalTools = mcpTools,
                linuxEnvironmentLabelProvider = { when (LinuxEnvironmentSettingsRepository.current(appContext)) {
                    LinuxDistribution.ALPINE -> "Alpine"
                    LinuxDistribution.DEBIAN -> "Debian"
                } },
                terminalSessionEnvironmentProvider = executor::terminalSessionEnvironment,
                terminalSessionIdentityProvider = executor::terminalSessionIdentity,
                compactPolicy = compactPolicy,
                onHistorySnapshot = session::publishHistorySnapshot,
                interactiveModeEnabled = interactiveModeEnabled,
                onEvent = { event ->
                    timing.accept(event)
                    foregroundReplay.accept(event)
                    acceptEvent(session, event, archivedEvents, entrySurfaceGuard, checkpointRecorder)
                },
            )
            response = completedResponse
            modelCompleted = true
            AgentRuntimeWire.RunResult(runId = request.runId, ok = true, content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent, transcript = completedResponse.transcript)
        } catch (throwable: Throwable) {
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException ||
                throwable is java.util.concurrent.CancellationException || throwable is InterruptedException
            val modelFailure = throwable as? AgentModelExecutionException
            val message = if (cancelled) "已停止" else throwable.message ?: throwable.javaClass.simpleName
            // This catch is after the retry loop has given up, not a ModelRetryScheduled event.
            // Freeze while the original generation is still attached, before terminal delivery.
            if (AgentParentNetworkFailure.isFinal(throwable, cancelled)) {
                AgentChildRunControl.terminate(session, AgentChildControlPolicy.Reason.FINAL_NETWORK_FAILURE)
            }
            if (cancelled) AndroidAgentLogger.info("Agent runtime stopped") else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                AndroidAgentLogger.error("Agent runtime failed: type=${throwable.safeLogType()}, " +
                    "model_code=${requestFailure?.code.orEmpty()}, cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}, " +
                    "detail=${(requestFailure?.message ?: throwable.message).orEmpty().take(600)}")
                AgentErrorReconnectTerminal.failureEvent(archivedEvents, throwable, request.config.apiKey)?.let {
                    runCatching { acceptEvent(session, it, archivedEvents, entrySurfaceGuard, checkpointRecorder) }
                }
                val event = AgentEvent.RunFailed(message)
                runCatching { acceptEvent(session, event, archivedEvents, entrySurfaceGuard, checkpointRecorder) }
                    .onFailure { checkpointFailure ->
                        AndroidAgentLogger.error("Agent runtime failure checkpoint failed: type=${checkpointFailure.safeLogType()}")
                        session.emit(event)
                    }
            }
            if (throwable is Error || throwable is java.util.concurrent.CancellationException) throw throwable
            AgentRuntimeWire.RunResult(runId = request.runId, ok = false, content = "", error = message,
                reasoningContent = modelFailure?.reasoningContent ?: (throwable as? AgentRunCancelledException)?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript ?: (throwable as? AgentRunCancelledException)?.transcript.orEmpty())
        } finally {
            if (modelCompleted && !cancelled && !runController.isCancelled) {
                // A normal final/supplemental reply seals only parent control. Children retain
                // their execution and tool/service leases across detach until they finish.
                // SUCCESS never pauses, cancels or automatically resumes a child task.
                AgentChildRunControl.terminate(session, AgentChildControlPolicy.Reason.SUCCESS)
                try {
                    val receipt = localTools?.completeVirtualDelivery()
                    virtualDeliveryCompleted = session.taskSurfaceMode == io.github.mangi.eta.agent.device.AgentTaskSurfaceMode.BACKGROUND &&
                        receipt != null && receipt.opt("ok") == true && receipt.opt("handedOff") == true && receipt.opt("released") == true
                    if (receipt != null && (!receipt.optBoolean("ok") || receipt.opt("released") != true)) {
                        deliveryFailure = receipt.optString("error", "AUTO_FINISH_FAILED")
                    }
                } catch (_: Exception) { deliveryFailure = "AUTO_FINISH_FAILED" }
            } else {
                // No automatic delivery after provider failure or user stop. Owner IPC is not
                // attached to the cancelled controller; this path verifies cleanup or reports it.
                cleanupFailure = io.github.mangi.eta.agent.device.VirtualDisplayAbortCleanup.failureCode {
                    localTools?.cleanupAbortedVirtualSession()
                }
            }
            session.childCompactor = null
            childContextSink.set(null)
            registeredChildGenerations.forEach(AgentChildTaskGroups::detach)
            runCatching { toolsBinding?.close() }
            runCatching { toolsOwner?.release() }
            unownedSkillRoot?.let { root -> runCatching { SkillRuntime.releaseRunSkills(appContext, root) } }
        }
        deliveryFailure?.let { code ->
            val message = "副屏自动回迁/释放未完成（$code）；会话已保留，不能视为交付成功。"
            result = result.copy(ok = false, content = result.content + "\n\n" + message, error = message)
        }
        cleanupFailure?.let { code ->
            val message = "副屏清理/释放未确认（$code）；已保留恢复状态，不会强制关闭或重放操作。"
            result = result.copy(ok = false, content = listOf(result.content, message).filter { it.isNotBlank() }.joinToString("\n\n"),
                error = listOfNotNull(result.error?.takeIf { it.isNotBlank() }, message).joinToString("\n"))
        }
        result = result.copy(virtualDeliveryCompleted = result.ok && virtualDeliveryCompleted && !cancelled && !runController.isCancelled)
        val completedRequest = runCatching { snapshotRequest(request) }.getOrElse { throwable ->
            AndroidAgentLogger.error("Agent runtime request snapshot failed: type=${throwable.safeLogType()}")
            request
        }
        val committed = session.complete(result) { terminal ->
            runCatching { checkpointRecorder?.seal() }.onFailure { throwable ->
                AndroidAgentLogger.error("Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}")
            }
            runCatching { persistArtifacts(completedRequest, terminal, archivedEvents) }.onFailure { throwable ->
                AndroidAgentLogger.error("Agent runtime artifact persistence failed: type=${throwable.safeLogType()}")
            }
        }
        return Outcome(session.terminalResult ?: result, entrySurfaceGuard, completedRequest.takeIf { committed },
            response.takeIf { committed && session.terminalResult?.ok == true }, committed)
    }

    // All stages below start inside this synchronized method: executor monitor acquisition is
    // excluded. checkpoint.accept includes the recorder call (and its own monitor, if contended).
    @Synchronized private fun acceptEvent(session: AgentRuntimeSession, event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>, entrySurfaceGuard: EntrySurfaceGuard?,
        checkpointRecorder: AgentRunCheckpointRecorder?) {
        if (event is AgentEvent.QuestionRequested || event is AgentEvent.QuestionResolved) {
            AgentQuestionEventPublisher.publish(session, event) {
                checkpointRecorder?.let { recorder ->
                    measureRuntimeStreamStage("runtime.checkpoint.accept") { recorder.accept(event) }
                }
            }
        } else {
            if (!session.emit(event) {
                checkpointRecorder?.let { recorder ->
                    measureRuntimeStreamStage("runtime.checkpoint.accept") { recorder.accept(event) }
                }
            }) return
        }
        archivedEvents += event
        if (event is AgentEvent.ModelRetryScheduled) AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        else if (event !is AgentEvent.AssistantBlockDelta) AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                "Agent runtime event projection failed: type=${throwable.safeLogType()}"
            }
        }
    }
}
