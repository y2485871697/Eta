package io.github.mangi.eta.agent.runtime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayBubble
import io.github.mangi.eta.agent.overlay.AgentOverlayOrb
import io.github.mangi.eta.agent.overlay.AgentResultCard
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块进程内的通用 Agent Runtime。
 *
 * Hook 入口只发送请求和接收结果；模型调用、工具执行、运行状态浮窗都在本服务中完成。
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceMessenger = Messenger(IncomingHandler())

    private val sessions = AgentRuntimeSessionRegistry()
    private val pendingStartRequests = linkedMapOf<String, PendingStartRequest>()
    // Keep the originating identity and frozen mode even after terminal registry removal.
    @Volatile
    private var overlaySession: AgentRuntimeSession? = null
    private val overlayRunId: String?
        get() = overlaySession?.runId

    private data class PendingStartRequest(
        val incoming: AgentRuntimeWire.IncomingRunRequest,
        val replyTo: Messenger?,
    )

    private var windowManager: WindowManager? = null
    private var orbView: ComposeView? = null
    private var bubbleView: ComposeView? = null
    private var resultCardView: ComposeView? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var resultCardParams: WindowManager.LayoutParams? = null

    private val state = mutableStateOf(AgentOverlayState.Initial)
    // The compact orb does not display tool details or round counters.
    private val orbPhase = derivedStateOf { state.value.phase }
    private val collapsed = mutableStateOf(true)
    private var hasExecutedForegroundTool = false
    private val supplementsLock = Any()
    private val supplementsByRunId = linkedMapOf<String, RunSupplements>()
    @Volatile
    private var lastCompletedRunContext: CompletedRunContext? = null
    private val hideToken = Any()
    private val pendingResultTranscripts =
        ConcurrentHashMap<String, AgentRuntimeTranscriptTransfer.PreparedTranscript>()

    private val pendingCompactionTransfers = ConcurrentHashMap<String, MutableList<AgentRuntimeTranscriptTransfer.PreparedTranscript>>()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_KEEP_ALIVE || sessions.isEmpty()) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (sessions.anyNonTerminal()) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        failPendingStarts("Agent Runtime 服务已停止")
        sessions.cancelAll("Agent Runtime 服务已停止")
        overlaySession = null
        mainHandler.removeCallbacksAndMessages(null)
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        orbView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        bubbleView = null
        orbView = null
        resultCardParams = null
        bubbleParams = null
        orbParams = null
        windowManager = null
        pendingResultTranscripts.values.forEach { prepared -> runCatching { prepared.close() } }
        pendingResultTranscripts.clear()
        pendingCompactionTransfers.values.forEach { transfers -> synchronized(transfers) { transfers.forEach { it.close() } } }
        pendingCompactionTransfers.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure("Agent Runtime 请求缺少消息体", msg.replyTo)
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure("Agent Runtime 请求格式无效", msg.replyTo)
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.prompt.isBlank() && incoming.images.isEmpty())) {
                        incoming.close()
                        finishWithFailure("Agent Runtime 请求缺少 runId 或用户输入", msg.replyTo)
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo)
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId)
                    releaseResultTranscript(runId)
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }

                AgentRuntimeWire.MSG_STEER_RUN -> {
                    val data = msg.data ?: return
                    requestSupplementForRun(
                        runId = AgentRuntimeWire.runIdFromBundle(data),
                        text = AgentRuntimeWire.steerTextFromBundle(data),
                        requestId = data.getString("request_id").orEmpty(),
                        imagesJson = data.getString("images_json") ?: "[]",
                    )
                }

                AgentRuntimeWire.MSG_PAUSE_RUN -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    requestPause(runId)
                }

                AgentRuntimeWire.MSG_RESUME_RUN -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    requestResume(runId)
                }

                AgentRuntimeWire.MSG_COMPACT_RUN -> {
                    val data = msg.data ?: return
                    val accepted = requestCompactRun(
                        runId = AgentRuntimeWire.runIdFromBundle(data),
                        keepRecent = AgentRuntimeWire.compactKeepRecentFromBundle(data),
                        compressModelConfig = AgentRuntimeWire.compactModelConfigFromBundle(data),
                        childTaskId = AgentRuntimeWire.compactChildTaskFromBundle(data),
                    )
                    msg.replyTo?.let { reply -> runCatching {
                        reply.send(Message.obtain(null, AgentRuntimeWire.MSG_COMPACT_RUN).apply {
                            this.data = android.os.Bundle().apply { putBoolean("compact_accepted", accepted) }
                        })
                    } }
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
    ) {
        val runId = incoming.request.runId
        pendingStartRequests.remove(runId)?.let { previous ->
            failPendingStart(previous, "已被同一任务的新请求替换")
        }
        val pending = PendingStartRequest(incoming, replyTo)
        pendingStartRequests[runId] = pending
        thread(name = "agent-runtime-image-ingest") {
            val prepared = runCatching {
                val request = AgentRuntimeImageTransfer.materialize(incoming)
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        RuntimeConfigRepository.currentRuntimeConfig()
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                if (pendingStartRequests[runId] !== pending) return@post
                pendingStartRequests.remove(runId)
                sendRequestIngestedTo(replyTo, incoming.request.runId)
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        startRun(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                            replyTo,
                        )
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure(
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: "Agent Runtime 无法读取图片"
                                is RuntimeConfigUnavailableException ->
                                    "请先在 Eta 中配置可用的模型"
                                else -> "Agent Runtime 无法准备请求"
                            },
                            replyTo,
                        )
                    },
                )
            }
        }
    }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        sessions.get(request.runId)?.cancel("已被同一任务的新请求替换")
        val session = AgentRuntimeSession(
            runId = request.runId,
            eventSink = { event -> sendEventTo(replyTo, event, request.runId) },
            resultSink = { result -> sendResultTo(replyTo, result) },
        )
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted
        val executionHeld = AgentExecutionService.acquire(
            this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
        ) { session.cancel("已停止") }
        if (!executionHeld && (!allowBoundFallback || AgentExecutionService.backupMaintenance)) {
            session.complete(AgentRuntimeWire.RunResult(
                runId = request.runId, ok = false, content = "",
                error = "无法启动后台执行服务，请返回 Eta 后重试",
            )) {}
            return
        }
        sessions.put(session)
        if (overlayRunId == request.runId) {
            if (AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode)) {
                overlaySession = session
            } else {
                // Only retire the replaced run's windows, never another foreground run's.
                dismissAndStop()
            }
        }
        if (lastCompletedRunContext?.request?.runId == request.runId) {
            lastCompletedRunContext = null
        }
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        if (AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode)) {
            mainHandler.removeCallbacksAndMessages(hideToken)
            state.value = AgentOverlayState.Initial
            collapsed.value = true
            if (overlayRunId == null || overlaySession === session) {
                hasExecutedForegroundTool = false
            }
        }
        synchronized(supplementsLock) {
            val extras = RunSupplements()
            if (request.handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                val payload = AgentUiHandoffPayload.from(request.handoff.payload)
                extras.items += payload.supplements
                extras.nextIndex = (
                    listOfNotNull(payload.promptSupplement?.index) +
                        payload.supplements.map { it.index }
                    ).maxOrNull()?.plus(1) ?: 1
            }
            supplementsByRunId[request.runId] = extras
        }

        thread(name = "agent-runtime") {
            try {
                executeRun(session, request)
            } finally {
                AgentExecutionService.release("run:${request.runId}")
            }
        }
    }

    private fun executeRun(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = { it.withActiveSupplements() },
            onAcceptedEvent = { event, entrySurfaceGuard ->
                handleAcceptedRunEvent(session, event, entrySurfaceGuard)
            },
            persistArtifacts = ::persistRunArtifacts,
        ).execute(session, request)
        if (!outcome.shouldUpdateHost) return
        postTerminalOverlay(
            session = session,
            result = outcome.result,
            entrySurfaceGuard = outcome.entrySurfaceGuard,
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    CompletedRunContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    private fun handleAcceptedRunEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        entrySurfaceGuard: EntrySurfaceGuard?,
    ) {
        if (!sessions.contains(session)) return
        if (!AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode)) return
        val revealsForegroundOperation =
            AgentOverlayVisibilityPolicy.shouldRevealFor(event, session.taskSurfaceMode)
        val requiresEntrySurfaceDismissal =
            AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event, session.taskSurfaceMode)
        val entrySurfaceReady = if (requiresEntrySurfaceDismissal && entrySurfaceGuard != null) {
            runCatching { entrySurfaceGuard.dismissOnce() }.getOrDefault(false)
        } else {
            true
        }
        mainHandler.post {
            if (!sessions.contains(session)) return@post
            if (
                AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                    event,
                    entrySurfaceReady,
                    session.taskSurfaceMode,
                )
            ) {
                hasExecutedForegroundTool = true
            }
            if (session.isTerminal) return@post
            runCatching {
                val ownsOverlay = claimOverlay(session, revealsForegroundOperation && entrySurfaceReady)
                if (ownsOverlay) {
                    state.value = state.value.applyEvent(event)
                }
                if (revealsForegroundOperation && entrySurfaceReady && ownsOverlay) {
                    if (orbView == null) {
                        AgentHapticFeedback.perform(
                            this,
                            AgentHapticFeedback.Type.RUN_STARTED,
                        )
                    }
                    ensureOverlayVisible()
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>,
    ) {
        // outbox 是终态与在途 checkpoint 之间的提交点；失败时保留 checkpoint 供下次恢复。
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    private fun postTerminalOverlay(
        session: AgentRuntimeSession,
        result: AgentRuntimeWire.RunResult,
        entrySurfaceGuard: EntrySurfaceGuard?,
        completedContext: CompletedRunContext? = null,
    ) {
        mainHandler.post {
            // Removal is identity-based: a late callback cannot consume a replacement
            // session's supplements or reveal its windows just because run IDs match.
            if (!sessions.remove(session)) return@post
            synchronized(supplementsLock) { supplementsByRunId.remove(session.runId) }
            if (
                !AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode) ||
                overlaySession !== session
            ) {
                if (sessions.isEmpty() && pendingStartRequests.isEmpty() &&
                    orbView == null && bubbleView == null && resultCardView == null
                ) {
                    stopSelf()
                }
                return@post
            }
            lastCompletedRunContext = completedContext
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                        session = session,
                    )
                } else {
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (result.error == "已停止") {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = result.error.orEmpty(),
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                        session = session,
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(target: Messenger?, event: AgentEvent, runId: String) {
        if (target == null) return
        var prepared: AgentRuntimeTranscriptTransfer.PreparedTranscript? = null
        try {
            if (event is AgentEvent.ContextCompacted && event.applied && event.history.isNotEmpty()) {
                prepared = AgentRuntimeTranscriptTransfer.prepare(this, event.history)
            }
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event, prepared?.descriptor).apply {
                if (event is AgentEvent.AssistantBlockDelta) {
                    putLong(StreamDeliveryTiming.KEY, android.os.SystemClock.elapsedRealtimeNanos())
                }
            }
            target.send(msg)
            prepared?.let { transfer ->
                val pending = pendingCompactionTransfers.computeIfAbsent(runId) { mutableListOf() }
                synchronized(pending) { pending += transfer }
            }
        } catch (failure: Exception) {
            prepared?.close()
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${failure.safeLogType()}"
            }
        }
    }

    private fun sendResultTo(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        if (target == null) return
        val prepared = runCatching {
            AgentRuntimeTranscriptTransfer.prepare(this, result.transcript)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_transcript_prepare_failed") {
                "Agent runtime result transcript prepare failed: type=${throwable.safeLogType()}"
            }
        }.getOrNull()
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, prepared?.descriptor)
            target.send(msg)
            if (prepared != null) {
                val key = result.runId.ifBlank { "anonymous-${System.nanoTime()}" }
                pendingResultTranscripts.put(key, prepared)?.close()
            }
        }.onFailure { throwable ->
            prepared?.close()
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun releaseResultTranscript(runId: String) {
        if (runId.isBlank()) return
        pendingResultTranscripts.remove(runId)?.close()
        pendingCompactionTransfers.remove(runId)?.let { transfers -> synchronized(transfers) { transfers.forEach { it.close() } } }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                AgentRuntimeResultStore.list(this)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.activeRunsBundle(sessions.activeRunIds())
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?, attached: Boolean = false) {
        val session = sessions.get(runId)
        val attachedToSession = replyTo != null &&
            runId.isNotBlank() &&
            session != null &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event, runId) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attachedToSession) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
        // Preparation failures have no session/overlay ownership. They must not
        // reuse another run's foreground-execution flag or terminal windows.
        if (sessions.isEmpty() && pendingStartRequests.isEmpty() &&
            orbView == null && bubbleView == null && resultCardView == null
        ) {
            stopSelf()
        }
    }

    private fun requestStop() {
        val runId = overlayRunId ?: sessions.activeRunIds().lastOrNull()
        if (runId == null) {
            dismissAndStop()
            return
        }
        cancelRun(runId)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        pendingStartRequests.remove(runId)?.let { pending ->
            failPendingStart(pending, "已停止")
            return
        }
        val session = sessions.get(runId) ?: return
        if (session.requestStop() && overlaySession === session) {
            state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
        }
    }

    private fun requestPause() {
        requestPause(overlayRunId.orEmpty())
    }

    private fun requestPause(runId: String) {
        if (runId.isBlank()) return
        sessions.get(runId)?.controller?.pause()
        if (overlayRunId == runId) {
            state.value = state.value.copy(
                phase = AgentOverlayPhase.PAUSED,
                status = AgentOverlayStatus.Paused,
            )
        }
    }

    private fun requestResume() {
        requestResume(overlayRunId.orEmpty())
    }

    private fun requestCompactRun(
        runId: String,
        keepRecent: Int? = null,
        compressModelConfig: AgentModelClient.ModelConfig? = null,
        childTaskId: String? = null,
    ): Boolean {
        if (runId.isBlank()) return false
        return sessions.get(runId)?.requestCompact(keepRecent, compressModelConfig, childTaskId) ?: false
    }

    private fun requestResume(runId: String) {
        if (runId.isBlank()) return
        sessions.get(runId)?.controller?.resume()
        if (overlayRunId == runId) {
            state.value = state.value.copy(
                phase = AgentOverlayPhase.RUNNING,
                status = AgentOverlayStatus.Continuing,
            )
        }
    }

    private fun requestSupplementForRun(runId: String, text: String, requestId: String = "", imagesJson: String = "[]") {
        if (runId.isBlank() || sessions.get(runId) == null) return
        requestSupplement(text, runId, requestId, imagesJson)
    }

    private fun requestSupplement(text: String, runId: String? = overlayRunId, requestId: String = "", imagesJson: String = "[]") {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        val targetRunId = runId.orEmpty()
        if (runCatching { io.github.mangi.eta.agent.model.AgentSupplementMedia.persistedImages(imagesJson) }.isFailure) return
        if (requestId.isNotBlank() && synchronized(supplementsLock) {
                supplementsByRunId[targetRunId]?.items?.any { it.requestId == requestId } == true
            }) return
        val targetSession = sessions.get(targetRunId)
        val updatesOverlay = targetSession?.let {
            AgentOverlayVisibilityPolicy.allowsOverlay(it.taskSurfaceMode)
        } ?: true
        if (updatesOverlay) setBubbleInputMode(focusable = false)
        targetSession?.let { session ->
            val event = session.steer(supplementText, imagesJson) {
                recordSupplementEvent(targetRunId, supplementText, requestId, imagesJson)
            }
            if (event == null) {
                if (!session.isTerminal) {
                    if (updatesOverlay) {
                        state.value = state.value.copy(
                            status = AgentOverlayStatus.Finishing,
                        )
                    }
                    return
                }
            } else {
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                if (updatesOverlay) state.value = state.value.applyEvent(event)
                return
            }
        }

        val completed = lastCompletedRunContext ?: return
        if (targetRunId.isNotBlank() && completed.request.runId != targetRunId) return
        if (completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
            requestId = requestId,
            imagesJson = imagesJson,
        )
        startRun(continuationRequest)
    }

    private fun recordSupplementEvent(runId: String, text: String, requestId: String = "", imagesJson: String = "[]"): AgentEvent.UserSupplementReceived {
        val supplement = synchronized(supplementsLock) {
            val extras = supplementsByRunId.getOrPut(runId) { RunSupplements() }
            AgentUiHandoffPayload.Supplement(
                index = extras.nextIndex++,
                text = text,
                requestId = requestId,
                imagesJson = imagesJson,
                createdAt = System.currentTimeMillis(),
            ).also { extras.items += it }
        }
        return AgentEvent.UserSupplementReceived(
            index = supplement.index,
            text = supplement.text,
            requestId = supplement.requestId,
            imagesJson = supplement.imagesJson,
        )
    }

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    private fun showOverlay() {
        val session = overlaySession ?: return
        if (!AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode)) return
        if (orbView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY 免 SYSTEM_ALERT_WINDOW 权限；仅回退态（无障碍未启用）才需检查
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val wm = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        // No full-screen decorative window: it shared the UI/RenderThread with chat scrolling.
        // ── 光球窗口：始终显示，右侧中下 ──────────────────────────────
        val orb = createOverlayComposeView {
            AgentOverlayOrb(
                phase = orbPhase.value,
                onToggleCollapse = ::toggleCollapse,
            )
        }
        val orbLp = orbLayoutParams()
        runCatching { wm.addView(orb, orbLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_orb_add_view_failed") {
                "Agent runtime orb addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        orbView = orb
        orbParams = orbLp
        orb.visibility = View.VISIBLE

        // ── 小气泡窗口：展开态显示，跟随光球，窗口外触摸穿透 ─────────
        if (!collapsed.value) {
            showBubble(wm)
        }
    }

    private fun toggleCollapse() {
        collapsed.value = !collapsed.value
        val wm = windowManager ?: return
        if (collapsed.value) {
            bubbleView?.let { view -> runCatching { wm.removeView(view) } }
            bubbleView = null
            bubbleParams = null
        } else {
            if (bubbleView == null) showBubble(wm)
        }
    }

    private fun showBubble(wm: WindowManager) {
        val session = overlaySession ?: return
        if (!AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode)) return
        if (bubbleView != null) return
        val bubble = createOverlayComposeView {
            AgentOverlayBubble(
                state = state.value,
                onCollapse = ::toggleCollapse,
                onPause = ::requestPause,
                onResume = ::requestResume,
                onStop = ::requestStop,
                onSupplementModeChange = ::setBubbleInputMode,
                onSupplement = { text -> requestSupplement(text) },
            )
        }
        val lp = bubbleLayoutParams()
        runCatching { wm.addView(bubble, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_bubble_add_view_failed") {
                "Agent runtime bubble addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        bubbleView = bubble
        bubbleParams = lp
    }

    private fun showResultCard(wm: WindowManager, session: AgentRuntimeSession) {
        if (overlaySession !== session ||
            !AgentOverlayVisibilityPolicy.shouldShowResultCard(
                session.taskSurfaceMode, hasExecutedForegroundTool,
            )
        ) return
        if (resultCardView != null) return
        val card = createOverlayComposeView {
            AgentResultCard(
                state = state.value,
                onClose = ::dismissAndStop,
            )
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        resultCardView = card
        resultCardParams = lp
    }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    // 部分 ROM 会给 TYPE_ACCESSIBILITY_OVERLAY 分配软件 Canvas；Miuix 的
                    // RuntimeShader 只检查系统版本，因此系统浮层统一使用其普通圆角回退。
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                        content()
                    }
                }
            }
        }

    @Suppress("unused")
    private fun handleDrag(dx: Float, dy: Float) {
        val lp = orbParams ?: return
        val wm = windowManager ?: return
        val view = orbView ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun orbLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "Eta Agent Orb"
            // 右侧中下，贴近右边缘
            gravity = Gravity.END or Gravity.TOP
            x = dpToPx(8)
            y = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        }

    private fun bubbleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "Eta Agent Controls"
            // 跟随光球：右侧中下，窗口外触摸穿透
            gravity = Gravity.END or Gravity.TOP
            x = dpToPx(72)
            y = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            windowAnimations = 0
        }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "Eta Agent Result"
            // 半屏底部居中，窗口外触摸穿透
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    private fun overlayType(): Int =
        // 无障碍服务可用时用 TYPE_ACCESSIBILITY_OVERLAY（免 SYSTEM_ALERT_WINDOW 权限，且截图
        // filterValidWindows 可过滤）；需用无障碍服务 context 创建，否则 BadTokenException
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    private fun setBubbleInputMode(focusable: Boolean) {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        val lp = bubbleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(bubble, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_bubble_focus_update_failed") {
                "Agent runtime bubble focus update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun resultCardWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(
        finalState: AgentOverlayState,
        keepVisible: Boolean = false,
        session: AgentRuntimeSession,
    ) {
        if (overlaySession !== session ||
            !AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode)
        ) return
        state.value = finalState

        if (AgentOverlayVisibilityPolicy.shouldShowResultCard(session.taskSurfaceMode, hasExecutedForegroundTool)) {
            // 撤掉光球和小气泡，改显半屏结果卡片，不自动关闭，用户手动关闭
            collapsed.value = true
            removeAmbientWindows()
            windowManager?.let { showResultCard(it, session) }
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else {
            dismissAndStop()
        }
    }

    private fun removeAmbientWindows() {
        orbView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        orbView = null
        bubbleView = null
        orbParams = null
        bubbleParams = null
    }

    private fun dismissAndStop() {
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        orbView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        bubbleView = null
        orbView = null
        resultCardParams = null
        bubbleParams = null
        orbParams = null
        windowManager = null
        overlaySession = null
        hasExecutedForegroundTool = false
        if (sessions.isEmpty() && pendingStartRequests.isEmpty()) {
            stopSelf()
        }
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        return packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    private fun AgentRuntimeWire.RunRequest.withActiveSupplements(): AgentRuntimeWire.RunRequest {
        val handoff = handoff ?: return this
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return this
        val supplements = synchronized(supplementsLock) {
            supplementsByRunId[runId]?.items?.toList().orEmpty()
        }
        if (supplements.isEmpty()) return this
        val payload = AgentUiHandoffPayload.from(handoff.payload).copy(
            supplements = supplements,
        )
        return copy(
            handoff = handoff.copy(payload = payload.toJson())
        )
    }

    private fun failPendingStarts(error: String) {
        pendingStartRequests.values.toList().forEach { pending -> failPendingStart(pending, error) }
        pendingStartRequests.clear()
    }

    private fun failPendingStart(pending: PendingStartRequest, error: String) {
        pending.incoming.close()
        sendRequestIngestedTo(pending.replyTo, pending.incoming.request.runId)
        sendResultTo(
            pending.replyTo,
            AgentRuntimeWire.RunResult(
                runId = pending.incoming.request.runId,
                ok = false,
                content = "",
                error = error,
            ),
        )
    }

    private fun claimOverlay(session: AgentRuntimeSession, wantsOverlay: Boolean): Boolean {
        if (!sessions.contains(session) ||
            !AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode)
        ) return false
        val current = overlaySession
        if (current === session) return true
        if (!wantsOverlay) return current == null
        if (current != null && sessions.contains(current) && !current.isTerminal) return false
        overlaySession = session
        return true
    }

    private companion object {
        const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"
        const val HIDE_DELAY_MS = 2_500L
        const val RESULT_REVIEW_DELAY_MS = 120_000L
        const val RESULT_CARD_HEIGHT_RATIO = 0.5f
        const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private data class CompletedRunContext(
        val request: AgentRuntimeWire.RunRequest,
        val response: AgentModelClient.ModelResponse.Text,
    )

    private data class RunSupplements(
        val items: MutableList<AgentUiHandoffPayload.Supplement> = mutableListOf(),
        var nextIndex: Int = 1,
    )

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
