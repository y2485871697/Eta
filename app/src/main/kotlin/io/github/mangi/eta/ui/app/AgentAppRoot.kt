package io.github.mangi.eta.ui.app

import androidx.compose.runtime.CompositionLocalProvider
import io.github.mangi.eta.ui.components.StreamingMarkdownCache
import io.github.mangi.eta.ui.components.LocalStreamingMarkdownStates
import io.github.mangi.eta.ui.components.LocalChatRouteCovered
import io.github.mangi.eta.ui.components.LocalChatTransitionActive
import io.github.mangi.eta.ui.components.LocalChatUiActive
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.AppUpdateOffer
import io.github.mangi.eta.data.repository.AppUpdateRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.AgentTaskPreferenceScreen
import io.github.mangi.eta.ui.AgentTaskSurfacePrompt
import io.github.mangi.eta.ui.AppearanceSettingsScreen
import io.github.mangi.eta.ui.WhaleMaidSettingsScreen
import io.github.mangi.eta.ui.HapticsSettingsScreen
import io.github.mangi.eta.ui.ErrorReconnectSettingsScreen
import io.github.mangi.eta.ui.ContextCompressionSettingsScreen
import io.github.mangi.eta.ui.SettingsScreen
import io.github.mangi.eta.ui.components.AppUpdateDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.AgentPendingChildStopDialog
import io.github.mangi.eta.ui.model.AgentChatAction
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.conversationTokenUsage
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.navigation.AgentNavigator
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.pages.providers.ModelProviderDetailScreen
import io.github.mangi.eta.ui.pages.providers.ProviderAuthMethodScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderListScreen
import io.github.mangi.eta.ui.screens.assistants.AssistantEditScreen
import io.github.mangi.eta.ui.screens.assistants.AssistantsScreen
import io.github.mangi.eta.ui.screens.backup.DataBackupScreen
import io.github.mangi.eta.ui.screens.browser.AgentBrowserScreen
import io.github.mangi.eta.ui.screens.chat.AgentChatScreen
import io.github.mangi.eta.ui.screens.chat.ManageChatsScreen
import io.github.mangi.eta.ui.screens.enhance.SystemEnhanceScreen
import io.github.mangi.eta.ui.screens.home.AgentHomeScreen
import io.github.mangi.eta.ui.screens.mcp.McpServerDetailScreen
import io.github.mangi.eta.ui.screens.mcp.McpServersScreen
import io.github.mangi.eta.ui.screens.memory.AgentMemoryScreen
import io.github.mangi.eta.ui.screens.permissions.PermissionHealthScreen
import io.github.mangi.eta.ui.screens.skills.AgentSkillsScreen
import io.github.mangi.eta.ui.screens.stats.UsageStatsScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxEnvironmentScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxFilesScreen
import io.github.mangi.eta.ui.screens.terminal.SharedFoldersScreen
import io.github.mangi.eta.ui.screens.terminal.TerminalEntryScreen
import io.github.mangi.eta.ui.screens.terminal.WorkspaceScreen
import io.github.mangi.eta.ui.screens.tools.AgentToolsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import top.yukonga.miuix.kmp.basic.TextField
import io.github.mangi.eta.agent.pet.WhaleMaidInAppHost
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Agent App 根组件：持有本地导航栈，并把 Screen actions 交给 [AgentAppState]。
 */
@Composable
fun AgentAppRoot(
    assistantConversationKey: String? = null,
    inboundShareUris: List<String> = emptyList(),
    onAssistantConversationOpened: (Boolean) -> Unit = {},
    onInboundShareConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Home)
    val navigator = remember(backStack) { AgentNavigator(backStack) }
    val appViewModel = viewModel<AgentAppViewModel>()
    val agentState = appViewModel.state
    val subAgentOwner = agentState.subAgentConfigOwner
    val subAgentEditor = remember(agentState, subAgentOwner) { agentState.subAgentEditor(subAgentOwner) }
    fun stopMainReply() {
        val captured = agentState.captureStopRequest() ?: return
        if (!agentState.stopMainReply(captured)) {
            Toast.makeText(context, "任务状态已变化，请重新选择要停止的任务。", Toast.LENGTH_SHORT).show()
        }
    }
    val usageConversationId = agentState.conversationPaneState.selectedConversationId
    val recordedUsageState by remember(usageConversationId) {
        io.github.mangi.eta.data.repository.UsageStatsRepository.conversationUsageFlow(usageConversationId)
            .map { usageConversationId to it }
    }.collectAsState(initial = null)
    val recordedUsage = recordedUsageState?.takeIf { it.first == usageConversationId }?.second
    // 输出中每条增量都会换成新的 homeState。侧边栏只需要压缩状态、子任务和累计用量，
    // 这些没变时不能跟着消息重组。
    val shellHomeState by remember {
        derivedStateOf {
            val state = agentState.homeState
            ShellHomeState(
                selectedContextTaskId = state.selectedContextTaskId,
                isCompressingContext = state.isCompressingContext,
                isWaitingForCompression = state.isWaitingForCompression,
                childContexts = state.childContexts,
                childStatusRoster = state.childStatusRoster,
                providerId = state.providerId,
                modelId = state.modelId,
                assistantId = state.assistantId,
                pendingConversationMentions = state.pendingConversationMentions,
                tokenUsage = conversationTokenUsage(state.messages),
            )
        }
    }
    val cumulativeUsage = recordedUsage?.let {
        io.github.mangi.eta.ui.model.ConversationTokenUsageUi(it.input, it.output, it.cached, it.cacheCreation)
    } ?: shellHomeState.tokenUsage

    DisposableEffect(backStack.lastOrNull(), agentState.conversationPaneState.selectedConversationId) {
        onDispose { io.github.mangi.eta.agent.voice.tts.SpeechPlayback.stopUiBound("route_change") }
    }
    val speechPlayback by io.github.mangi.eta.agent.voice.tts.SpeechPlayback.state.collectAsState()
    AgentTaskSurfacePrompt()
    LaunchedEffect(speechPlayback.error) {
        speechPlayback.error?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    val streamingMarkdownCache = remember { StreamingMarkdownCache() }
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        agentState.refreshPermissionHealth()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var updateOffer by remember { mutableStateOf<AppUpdateOffer?>(null) }
    val currentVersion = remember { AppUpdateRepository.currentVersionName(context) }
    DisposableEffect(lifecycleOwner, focusManager, keyboard) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    io.github.mangi.eta.agent.voice.tts.SpeechPlayback.stopUiBound("pause")
                    keyboard?.hide()
                    focusManager.clearFocus(force = true)
                }
                Lifecycle.Event.ON_RESUME -> {
                    RootAccess.refresh(context)
                    appViewModel.refreshKimiWeb()
                    agentState.refreshPermissionHealth()
                    agentState.refreshRuntimeResults()
                    agentState.refreshRequestOverhead()
                    uiScope.launch {
                        AppUpdateRepository.checkForUpdate(context, force = false)
                            .getOrNull()
                            ?.let { updateOffer = it }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }


    var conversationPaneOpen by remember { mutableStateOf(false) }
    var browserSheetVisible by rememberSaveable { mutableStateOf(false) }
    val diagnosticLoggingEnabled by remember {
        io.github.mangi.eta.data.datastore.SettingsDataStore.fileLoggingEnabledFlow()
    }.collectAsState(initial = false)
    io.github.mangi.eta.ui.components.StreamPerformanceMonitor(
        loggingEnabled = diagnosticLoggingEnabled,
        page = when {
            browserSheetVisible -> io.github.mangi.eta.ui.components.FrameDiagnosticPage.BrowserOverlay
            conversationPaneOpen -> io.github.mangi.eta.ui.components.FrameDiagnosticPage.ConversationDrawer
            else -> (backStack.lastOrNull() as? AppRoute)?.frameDiagnosticPage()
                ?: io.github.mangi.eta.ui.components.FrameDiagnosticPage.Unknown
        },
    )
    var conversationRenameTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationDeleteTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationMoveTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var conversationExportTitle by rememberSaveable { mutableStateOf("") }
    var conversationExportBusy by remember { mutableStateOf(false) }
    var conversationExportConfirmation by rememberSaveable { mutableStateOf(false) }

    val conversationExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val id = conversationExportId
        val title = conversationExportTitle
        conversationExportId = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (id == null) {
            Toast.makeText(context, "导出目标已丢失，未写入文件，请重新选择会话。", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        uiScope.launch {
            conversationExportBusy = true
            var touchedDestination = false
            val temporary = java.io.File(context.cacheDir, "conversation-export-${java.util.UUID.randomUUID()}.zip")
            val validation = java.io.File(context.cacheDir, "conversation-export-check-${java.util.UUID.randomUUID()}")
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    temporary.outputStream().use { agentState.exportConversation(id, it) }
                    io.github.mangi.eta.data.repository.BackupArchiveSafety.stageZip(temporary, validation)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    touchedDestination = true
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error(context.getString(R.string.data_backup_file_open_failed))
                    output.use { sink -> temporary.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                        }
                    } }
                }
                Toast.makeText(context, context.getString(R.string.conversation_exported, title), Toast.LENGTH_SHORT).show()
            } catch (failure: Throwable) {
                val cleaned = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                }
                if (failure is kotlinx.coroutines.CancellationException) {
                    if (!cleaned && touchedDestination) Toast.makeText(context, "导出已取消，目标文件可能不完整，请删除后重试。", Toast.LENGTH_LONG).show()
                    throw failure
                }
                val detail = failure.message ?: context.getString(R.string.conversation_export_failed)
                Toast.makeText(context, detail + if (!cleaned) {
                    if (touchedDestination) "\n目标文件可能不完整，请删除后重试。" else "\n未写入备份；文件选择器创建的空文件可能仍保留。"
                } else "", Toast.LENGTH_LONG).show()
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    temporary.delete()
                    validation.deleteRecursively()
                }
                conversationExportBusy = false
            }
        }
    }

    var messageDeleteTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    var messageRegenerateTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    LaunchedEffect(Unit) {
        delay(800)
        AppUpdateRepository.checkForUpdate(context, force = true)
            .onFailure { failure ->
                AndroidAgentLogger.warn(
                    "App update check failed: type=${failure.safeLogType()}",
                )
            }
            .getOrNull()
            ?.let { updateOffer = it }
    }

    LaunchedEffect(assistantConversationKey) {
        val conversationKey = assistantConversationKey ?: return@LaunchedEffect
        val opened = agentState.openAssistantConversation(conversationKey)
        if (opened) {
            navigator.replace(AppRoute.Chat)
        }
        onAssistantConversationOpened(opened)
    }

    LaunchedEffect(inboundShareUris) {
        if (inboundShareUris.isEmpty()) return@LaunchedEffect
        agentState.attachSharedUris(inboundShareUris)
        navigator.popToHome()
        onInboundShareConsumed()
    }

    fun pushRoute(route: AppRoute) {
        if (route == AppRoute.Browser) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            conversationPaneOpen = false
            browserSheetVisible = true
        } else {
            navigator.push(route)
        }
    }

    fun pushFromDrawer(route: AppRoute) {
        // 先收起抽屉再入栈：NavDisplay 若带着打开的侧栏+聊天页一起转场，底部按钮会顿一下。
        conversationPaneOpen = false
        pushRoute(route)
    }

    val exitGuard = remember { DoublePressExitGuard() }

    fun popRoute() {
        if (navigator.pop()) {
            exitGuard.reset()
            return
        }
        if (exitGuard.consume()) {
            (context as? Activity)?.finish()
            return
        }
        Toast.makeText(
            context.applicationContext,
            context.getString(R.string.app_press_back_again_to_exit),
            Toast.LENGTH_SHORT,
        ).show()
    }

    // NavDisplay 只在还能出栈时拦截返回；根页面必须自己接住，否则系统会直接 finish Activity。
    val interceptExitBack = backStack.size <= 1 && !conversationPaneOpen && !browserSheetVisible
    val exitBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = exitBackState,
        isBackEnabled = interceptExitBack,
        onBackCompleted = { popRoute() },
    )

    fun selectConversation(conversationId: String) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        agentState.selectConversation(conversationId)
    }

    fun createConversation() {
        focusManager.clearFocus()
        agentState.createConversation()
        conversationPaneOpen = false
    }

    @Composable
    fun RoutedShell(
        route: AppRoute,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(
            // 半遮住时页面还在组合里。保持实时消息，让露出的字继续长；
            // 完全盖住后导航会把这一页移出组合，不再绘制。
            LocalChatUiActive provides true,
            LocalChatRouteCovered provides (backStack.lastOrNull() != route),
        ) {
        AgentAppShell(
            currentRoute = route,
            isCurrentRoute = backStack.lastOrNull() == route,
            conversationPaneState = agentState.conversationPaneState,
            currentConversationTitle = agentState.conversationPaneState.selectedConversationId?.let { id ->
                agentState.conversationPaneState.conversations.find { it.id == id }?.title
            },
            isConversationPaneOpen = conversationPaneOpen,
            onBack = { popRoute() },
            onOpenConversationPane = { conversationPaneOpen = true },
            onDismissConversationPane = { conversationPaneOpen = false },
            onSearchConversations = { query -> agentState.updateSearchQuery(query) },
            onNewConversation = { createConversation() },
            onOpenTerminal = { pushRoute(AppRoute.Terminal) },
            onLaunchKimiWeb = {
                requestExecutionNotifications()
                if (appViewModel.kimiWebState.phase != KimiWebPhase.NOT_INSTALLED) {
                    appViewModel.launchKimiWeb { result ->
                        if (result is KimiWebLaunchResult.Failed) {
                            Toast.makeText(
                                context,
                                result.message(context),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } else {
                    pushRoute(AppRoute.LinuxEnvironment)
                }
            },
            kimiWebLabel = appViewModel.kimiWebState.actionLabel(context),
            canStopKimiWeb = appViewModel.kimiWebState.canStop,
            onStopKimiWeb = appViewModel::stopKimiWeb,
            onRefreshKimiWeb = appViewModel::refreshKimiWeb,
            onOpenBrowser = { pushRoute(AppRoute.Browser) },
            onOpenWorkspace = { pushRoute(AppRoute.Workspace) },
            autoCompressEnabled = agentState.autoCompressEnabled,
            isCompressingContext = if (shellHomeState.selectedContextTaskId == null)
                shellHomeState.isCompressingContext || shellHomeState.isWaitingForCompression
            else shellHomeState.childContexts.any { it.taskId == shellHomeState.selectedContextTaskId &&
                (it.isCompacting || it.manualCompactionState == "pending") },
            onToggleAutoCompress = { agentState.updateAutoCompressEnabled(it) },
            onCompressConversation = { providerId, modelId, onFinished ->
                agentState.compressCurrentConversation(
                    providerId,
                    modelId,
                    onFinished,
                )
            },
            onSearchHistory = { query ->
                agentState.searchHistory(query, currentConversationOnly = true)
            },
            onOpenHistoryHit = { hit ->
                conversationPaneOpen = false
                agentState.openHistorySearchHit(hit)
            },
            tokenUsage = cumulativeUsage,
            subAgentStatuses = shellHomeState.childStatusRoster,
            selectedProviderId = agentState.modelPickerState.selectedModel?.providerId,
            onSelectConversation = { conversationId -> selectConversation(conversationId) },
            onConversationRename = { conversation ->
                conversationRenameTarget = conversation
            },
            onConversationDelete = { conversation ->
                conversationDeleteTarget = conversation
            },
            onMoveConversationToFolder = { conversation ->
                conversationMoveTarget = conversation
            },
            onConversationTogglePin = { conversation ->
                agentState.toggleConversationPinned(conversation.id)
            },
            onConversationExport = { conversation ->
                if (conversationExportBusy || conversationExportId != null) {
                    Toast.makeText(context, "已有会话导出任务，请等待完成。", Toast.LENGTH_SHORT).show()
                } else {
                    conversationExportId = conversation.id
                    conversationExportTitle = conversation.title.ifBlank { context.getString(R.string.conversation_unnamed) }
                    conversationExportConfirmation = true
                }
            },
            onOpenManageChats = { pushFromDrawer(AppRoute.ManageChats) },
            onSelectFolder = { folderId -> agentState.selectFolder(folderId) },
            onCreateFolder = { name -> agentState.createFolder(name) },
            onRenameFolder = { folderId, name -> agentState.renameFolder(folderId, name) },
            onDeleteFolder = { folderId -> agentState.deleteFolder(folderId) },
            onSelectAssistant = { id -> agentState.selectAssistant(id) },
            onEditAssistant = { id -> pushFromDrawer(AppRoute.AssistantEdit(id)) },
            onOpenAssistants = { pushFromDrawer(AppRoute.Assistants()) },
            onOpenUsageStats = { pushFromDrawer(AppRoute.UsageStats) },
            onOpenSkills = { pushFromDrawer(AppRoute.Skills) },
            onOpenPermissions = { pushFromDrawer(AppRoute.Permissions) },
            onOpenSettings = { pushFromDrawer(AppRoute.Settings) },
            onOpenModelProviders = { pushFromDrawer(AppRoute.ModelProviders) },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                CompositionLocalProvider(LocalStreamingMarkdownStates provides streamingMarkdownCache.forConversation(
                    agentState.conversationPaneState.selectedConversationId ?: "new-conversation"
                )) {
                    content()
                }
            }
        }
        }
    }

    val swipeBackDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }
    val swipeDismiss = swipeBackDirection.takeIf {
        LocalAppearanceSettings.current.swipeDismissEnabled
    }
    // 副屏恢复进行中不能被滑走；其余时间任务偏好页跟其他设置页一样可以横滑返回。
    var taskRecoveryWorking by remember { mutableStateOf(false) }
    val taskPreferenceSwipeDismiss = if (taskRecoveryWorking) NavSwipeDirection.None else swipeDismiss
    // Draw-phase atomics only. The boolean is published on a later frame, and only when it changes,
    // so a swipe cannot write snapshot state from the graphics layer on every sampled frame.
    val navigationActive = remember { mutableStateOf(false) }
    val navigationTransition = remember {
        chatNavigationActivityTransition { active ->
            if (navigationActive.value != active) navigationActive.value = active
        }
    }
    DisposableEffect(navigationTransition) {
        onDispose { navigationTransition.cancel() }
    }
    CompositionLocalProvider(
        io.github.mangi.eta.ui.components.LocalConversationSubAgentEditor provides subAgentEditor,
        LocalChatTransitionActive provides navigationActive.value,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    NavDisplay(
        backStack = backStack,
        onBack = { popRoute() },
        transition = navigationTransition.transition,
        effects = NavDisplayEffects(
            cornerClipRadius = rememberNavSystemCornerRadius(),
        ),
    ) {
            entry<AppRoute.Home>(swipeDismiss = swipeDismiss) {
                RoutedShell(route = AppRoute.Home) {
                    AgentHomeScreen(
                        stateProvider = { agentState.homeState },
                        conversationMentions = io.github.mangi.eta.ui.model.ConversationMentionInputUi(
                            conversations = agentState.conversationPaneState.historyConversations,
                            currentConversationId = agentState.conversationPaneState.selectedConversationId,
                            pending = shellHomeState.pendingConversationMentions,
                            onAttach = agentState::attachConversationMention,
                            onRemove = agentState::removeConversationMention,
                        ),
                        modelPickerState = agentState.modelPickerState,
                        autoCompressEnabled = agentState.autoCompressEnabled,
                        requestOverheadTokens = agentState.requestOverheadTokens,
                        measuredContextTokens = { agentState.measuredContextTokens },
                        billedOverheadTokens = agentState.billedOverheadTokens,
                        conversationKey = agentState.conversationPaneState.selectedConversationId,
                        draftField = agentState.currentDraftField(),
                        onAction = { action ->
                            when (action) {
                                AgentHomeAction.CycleGptSpeedMode -> agentState.cycleGptSpeedMode()
                                is AgentHomeAction.ReasoningEffortChanged ->
                                    agentState.updateReasoningEffort(action.effort)
                                is AgentHomeAction.ContextTaskSelected -> agentState.selectContextTask(action.taskId, usageConversationId)
                                is AgentHomeAction.ModelSelected -> agentState.selectModel(action.modelId, action.providerId)
                                is AgentHomeAction.SubmitMessage -> { requestExecutionNotifications(); agentState.sendCurrentMessage(action.text) }
                                AgentHomeAction.StopRun -> agentState.pauseCurrentRun()
                                AgentHomeAction.ContinueRun -> agentState.continuePausedGeneration()
                                AgentHomeAction.AbortPausedRun -> stopMainReply()
                                is AgentHomeAction.AssistantSelected -> agentState.selectAssistant(action.id)
                                is AgentHomeAction.ImageAttached -> agentState.attachImage(action.uri)
                                is AgentHomeAction.VideoAttached -> agentState.attachVideo(action.uri)
                                is AgentHomeAction.RemoveImage -> agentState.removePendingImage(action.id)
                                is AgentHomeAction.FilesAttached -> agentState.attachFiles(action.uris)
                                is AgentHomeAction.FolderAttached -> agentState.attachFolder(action.uri)
                                is AgentHomeAction.FilePathAttached -> agentState.attachFilePath(action.path)
                                is AgentHomeAction.RemoveFileReference ->
                                    agentState.removePendingFileReference(action.id)
                                is AgentHomeAction.EditMessage -> agentState.beginMessageEdit(action.id)
                                AgentHomeAction.CancelMessageEdit -> agentState.cancelMessageEdit()
                                is AgentHomeAction.DeleteMessage -> {
                                    agentState.messageDeleteImpact(action.id)?.let { impact ->
                                        messageDeleteTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentHomeAction.RegenerateMessage -> {
                                    val impact = agentState.messageRevisionImpact(action.id)
                                    if (impact?.laterTurnCount == 0) {
                                        agentState.regenerateMessage(action.id)
                                    } else if (impact != null) {
                                        messageRegenerateTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentHomeAction.BranchMessage -> agentState.branchConversation(action.id)
                                is AgentHomeAction.QuestionDraftChanged -> agentState.updateQuestionDraft(action.conversationId, action.questionId, action.answer)
                                is AgentHomeAction.SubmitQuestionAnswer -> agentState.submitQuestionAnswer(action.conversationId, action.questionId)
                                AgentHomeAction.OpenTools -> pushRoute(AppRoute.Tools)
                                AgentHomeAction.OpenSkills -> pushRoute(AppRoute.Skills)
                                AgentHomeAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                                AgentHomeAction.OpenSystemEnhance -> pushRoute(AppRoute.SystemEnhance)
                                AgentHomeAction.OpenSettings -> pushRoute(AppRoute.Settings)
                                AgentHomeAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                                is AgentHomeAction.EditAssistant -> pushRoute(AppRoute.AssistantEdit(action.id))
                                AgentHomeAction.ExpandRunTrace -> Unit
                            }
                        },
                        isDrawerOpen = conversationPaneOpen,
                        scrollToMessageId = agentState.pendingScrollToMessageId,
                        onScrollToMessageConsumed = agentState::consumePendingScrollToMessage,
                    )
                }
            }
            entry<AppRoute.Chat>(swipeDismiss = swipeDismiss) {
                RoutedShell(route = AppRoute.Chat) {
                    AgentChatScreen(
                        stateProvider = { agentState.homeState },
                        conversationMentions = io.github.mangi.eta.ui.model.ConversationMentionInputUi(
                            conversations = agentState.conversationPaneState.historyConversations,
                            currentConversationId = agentState.conversationPaneState.selectedConversationId,
                            pending = shellHomeState.pendingConversationMentions,
                            onAttach = agentState::attachConversationMention,
                            onRemove = agentState::removeConversationMention,
                        ),
                        modelPickerState = agentState.modelPickerState,
                        autoCompressEnabled = agentState.autoCompressEnabled,
                        requestOverheadTokens = agentState.requestOverheadTokens,
                        measuredContextTokens = { agentState.measuredContextTokens },
                        billedOverheadTokens = agentState.billedOverheadTokens,
                        conversationKey = agentState.conversationPaneState.selectedConversationId,
                        draftField = agentState.currentDraftField(),
                        onAction = { action ->
                            when (action) {
                                AgentChatAction.NavigateBack -> popRoute()
                                AgentChatAction.CycleGptSpeedMode -> agentState.cycleGptSpeedMode()
                                is AgentChatAction.ReasoningEffortChanged ->
                                    agentState.updateReasoningEffort(action.effort)
                                is AgentChatAction.ContextTaskSelected -> agentState.selectContextTask(action.taskId, usageConversationId)
                                is AgentChatAction.ModelSelected -> agentState.selectModel(action.modelId, action.providerId)
                                is AgentChatAction.SubmitMessage -> { requestExecutionNotifications(); agentState.sendCurrentMessage(action.text) }
                                AgentChatAction.StopRun -> agentState.pauseCurrentRun()
                                AgentChatAction.ContinueRun -> agentState.continuePausedGeneration()
                                AgentChatAction.AbortPausedRun -> stopMainReply()
                                is AgentChatAction.AssistantSelected -> agentState.selectAssistant(action.id)
                                AgentChatAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                                is AgentChatAction.EditAssistant -> pushRoute(AppRoute.AssistantEdit(action.id))
                                is AgentChatAction.ImageAttached -> agentState.attachImage(action.uri)
                                is AgentChatAction.VideoAttached -> agentState.attachVideo(action.uri)
                                is AgentChatAction.RemoveImage -> agentState.removePendingImage(action.id)
                                is AgentChatAction.FilesAttached -> agentState.attachFiles(action.uris)
                                is AgentChatAction.FolderAttached -> agentState.attachFolder(action.uri)
                                is AgentChatAction.FilePathAttached -> agentState.attachFilePath(action.path)
                                is AgentChatAction.RemoveFileReference ->
                                    agentState.removePendingFileReference(action.id)
                                is AgentChatAction.EditMessage -> agentState.beginMessageEdit(action.id)
                                AgentChatAction.CancelMessageEdit -> agentState.cancelMessageEdit()
                                is AgentChatAction.DeleteMessage -> {
                                    agentState.messageDeleteImpact(action.id)?.let { impact ->
                                        messageDeleteTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentChatAction.RegenerateMessage -> {
                                    val impact = agentState.messageRevisionImpact(action.id)
                                    if (impact?.laterTurnCount == 0) {
                                        agentState.regenerateMessage(action.id)
                                    } else if (impact != null) {
                                        messageRegenerateTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentChatAction.BranchMessage -> agentState.branchConversation(action.id)
                                is AgentChatAction.QuestionDraftChanged -> agentState.updateQuestionDraft(action.conversationId, action.questionId, action.answer)
                                is AgentChatAction.SubmitQuestionAnswer -> agentState.submitQuestionAnswer(action.conversationId, action.questionId)
                            }
                        },
                    )
                }
            }
            entry<AppRoute.Browser>(swipeDismiss = swipeDismiss) {
                // Migrate a restored legacy route to a modal over the preceding screen.
                LaunchedEffect(Unit) {
                    navigator.pop()
                    browserSheetVisible = true
                }
            }
            entry<AppRoute.Terminal>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) { requestExecutionNotifications() }
                RoutedShell(route = AppRoute.Terminal) {
                    TerminalEntryScreen(
                        terminalStore = appViewModel.terminalStore,
                        consoleStore = appViewModel.consoleStore,
                        onOpenEnvironment = { pushRoute(AppRoute.LinuxEnvironment) },
                    )
                }
            }
            entry<AppRoute.Haptics>(swipeDismiss = swipeDismiss) {
                HapticsSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.AgentTaskPreference>(swipeDismiss = taskPreferenceSwipeDismiss) {
                AgentTaskPreferenceScreen(
                    onBack = ::popRoute,
                    onRecoveryWorkingChanged = { taskRecoveryWorking = it },
                )
            }
            // Compatibility alias for a restored old back stack; there is no separate recovery page.
            entry<AppRoute.VirtualDisplayRecovery>(swipeDismiss = taskPreferenceSwipeDismiss) {
                AgentTaskPreferenceScreen(
                    onBack = ::popRoute,
                    onRecoveryWorkingChanged = { taskRecoveryWorking = it },
                )
            }
            entry<AppRoute.Tools>(swipeDismiss = swipeDismiss) {
                AgentToolsScreen(
                    state = agentState.toolsState,
                    onAction = { action ->
                        when (action) {
                            AgentToolsAction.NavigateBack -> popRoute()
                            AgentToolsAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                            AgentToolsAction.OpenEnhancements -> pushRoute(AppRoute.SystemEnhance)
                            AgentToolsAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                        }
                    },
                )
            }
            entry<AppRoute.Skills>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    agentState.refreshSkills()
                }
                AgentSkillsScreen(
                    state = agentState.skillsState,
                    onAction = { action ->
                        when (action) {
                            AgentSkillsAction.NavigateBack -> popRoute()
                            is AgentSkillsAction.ImportZip -> agentState.importSkillZip(action.uri)
                            AgentSkillsAction.ConfirmZipReplacement -> agentState.confirmSkillZipReplacement()
                            AgentSkillsAction.CancelZipReplacement -> agentState.cancelSkillZipReplacement()
                            AgentSkillsAction.DismissNotice -> agentState.dismissSkillNotice()
                            is AgentSkillsAction.ToggleSkill -> agentState.toggleSkill(action.skillId, action.enabled)
                            is AgentSkillsAction.DeleteSkill -> agentState.deleteSkill(action.skillId)
                            is AgentSkillsAction.ReinstallBuiltin -> agentState.reinstallBuiltin(action.skillId)
                        }
                    },
                )
            }
            entry<AppRoute.Permissions>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    agentState.refreshPermissionHealth()
                }
                PermissionHealthScreen(
                    state = agentState.permissionHealthState,
                    onAction = { action ->
                        when (action) {
                            PermissionHealthAction.NavigateBack -> popRoute()
                            is PermissionHealthAction.OpenItemAction -> {
                                when (action.itemId) {
                                    "accessibility" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        }
                                    }
                                    "overlay" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    }
                                    "background" -> {
                                        if (RootAccess.isGranted && Build.MANUFACTURER.lowercase() in setOf("oppo", "realme", "oneplus")) {
                                            uiScope.launch(Dispatchers.IO) {
                                                BoundedRootCommandExecutor(AndroidAgentLogger).use {
                                                    it.execute(
                                                        "am start --user current -n " +
                                                            "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerControlActivity " +
                                                            "--es title Eta --es pkgName io.github.mangi.eta --es drainType APP",
                                                    )
                                                }
                                            }
                                        } else {
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                            }
                                        }
                                    }
                                    "app_list" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    }
                                    "location" -> {
                                        when (DeviceLocationProvider.accessState(context)) {
                                            DeviceLocationProvider.AccessState.DENIED -> {
                                                locationPermissionLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                                    )
                                                )
                                            }
                                            DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            Uri.parse("package:${context.packageName}")
                                                        )
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.DISABLED -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.AVAILABLE -> {
                                                agentState.refreshPermissionHealth()
                                            }
                                        }
                                    }
                                    "notification_history" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }
                                    }
                                    "usage_access" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        }
                                    }
                                    "notifications" -> {
                                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                    }
                                    "root" -> pushRoute(AppRoute.SystemEnhance)
                                }
                            }
                        }
                    },
                )
            }
            entry<AppRoute.SystemEnhance>(swipeDismiss = swipeDismiss) {
                SystemEnhanceScreen(
                    onAction = { action ->
                        when (action) {
                            AgentSystemEnhanceAction.NavigateBack -> popRoute()
                            AgentSystemEnhanceAction.RequestRoot -> { RootAccess.request(context) }
                            AgentSystemEnhanceAction.RefreshRoot -> { RootAccess.refresh(context) }
                        }
                    },
                )
            }
            entry<AppRoute.Workspace>(swipeDismiss = swipeDismiss) {
                WorkspaceScreen(onBack = ::popRoute)
            }
            entry<AppRoute.UsageStats>(swipeDismiss = swipeDismiss) {
                UsageStatsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.ManageChats>(swipeDismiss = swipeDismiss) {
                ManageChatsScreen(
                    conversations = agentState.conversationPaneState.historyConversations,
                    onBack = ::popRoute,
                    onOpenConversation = { conversationId ->
                        selectConversation(conversationId)
                        popRoute()
                    },
                    onTogglePin = { conversationId ->
                        agentState.toggleConversationPinned(conversationId)
                    },
                    onDeleteConversation = { conversation ->
                        agentState.deleteConversation(conversation.id)
                    },
                    onDeleteAll = { agentState.deleteAllConversations() },
                    onSearchHistory = { query -> agentState.searchHistory(query) },
                    onOpenHistoryHit = { hit ->
                        agentState.openHistorySearchHit(hit)
                        popRoute()
                    },
                )
            }
            entry<AppRoute.Settings>(swipeDismiss = swipeDismiss) {
                SettingsScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                    currentProviderId = shellHomeState.providerId,
                    currentModelId = shellHomeState.modelId,
                )
            }
            entry<AppRoute.AuxiliaryVision>(swipeDismiss = swipeDismiss) {
                io.github.mangi.eta.ui.ModelFeatureSettingsScreen(
                    feature = io.github.mangi.eta.agent.model.ModelFeature.VISION, onBack = ::popRoute)
            }
            entry<AppRoute.SubAgents>(swipeDismiss = swipeDismiss) {
                io.github.mangi.eta.ui.SubAgentSettingsScreen(onBack = ::popRoute,
                    isCurrentRoute = backStack.lastOrNull() == AppRoute.SubAgents)
            }
            entry<AppRoute.TitleModel>(swipeDismiss = swipeDismiss) {
                io.github.mangi.eta.ui.ModelFeatureSettingsScreen(
                    feature = io.github.mangi.eta.agent.model.ModelFeature.TITLE, onBack = ::popRoute)
            }
            entry<AppRoute.ErrorReconnectSettings>(swipeDismiss = swipeDismiss) {
                ErrorReconnectSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.VoiceSettings>(swipeDismiss = swipeDismiss) {
                io.github.mangi.eta.ui.VoiceSettingsScreen(onBack = ::popRoute)
            }
            // Retain serialized legacy routes, but show the same unified voice surface.
            entry<AppRoute.TtsSettings>(swipeDismiss = swipeDismiss) {
                io.github.mangi.eta.ui.VoiceSettingsScreen(onBack = ::popRoute,
                    initialSection = io.github.mangi.eta.ui.VoiceSettingsSection.READ_ALOUD)
            }
            entry<AppRoute.SpeechSettings>(swipeDismiss = swipeDismiss) {
                io.github.mangi.eta.ui.VoiceSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.VoiceModeSettings>(swipeDismiss = swipeDismiss) {
                io.github.mangi.eta.ui.VoiceSettingsScreen(onBack = ::popRoute,
                    initialSection = io.github.mangi.eta.ui.VoiceSettingsSection.CONVERSATION)
            }
            entry<AppRoute.AppearanceSettings>(swipeDismiss = swipeDismiss) {
                AppearanceSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.WhaleMaid>(swipeDismiss = swipeDismiss) {
                WhaleMaidSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.DataBackup>(swipeDismiss = swipeDismiss) {
                DataBackupScreen(
                    context = context,
                    onBack = ::popRoute,
                    onExport = agentState::exportBackup,
                    onImport = { input ->
                        val summary = agentState.importBackup(input)
                        appViewModel.refreshKimiWeb()
                        summary
                    },
                )
            }
            entry<AppRoute.Memory>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    agentState.refreshMemory()
                }
                AgentMemoryScreen(
                    state = agentState.memoryState,
                    onAction = { action ->
                        when (action) {
                            AgentMemoryAction.NavigateBack -> popRoute()
                            is AgentMemoryAction.ToggleEnabled -> agentState.setMemoryEnabled(action.enabled)
                            is AgentMemoryAction.DraftChanged -> agentState.updateMemoryDraft(action.content)
                            AgentMemoryAction.Save -> agentState.saveMemory()
                            AgentMemoryAction.Clear -> agentState.clearMemory()
                            AgentMemoryAction.DismissNotice -> agentState.dismissMemoryNotice()
                        }
                    },
                )
            }
            entry<AppRoute.LinuxEnvironment>(swipeDismiss = swipeDismiss) {
                LinuxEnvironmentScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.SharedFolders>(swipeDismiss = swipeDismiss) {
                SharedFoldersScreen(
                    context = context,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.LinuxFiles>(swipeDismiss = swipeDismiss) { route ->
                LinuxFilesScreen(
                    context = context,
                    distribution = route.distribution,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviders>(swipeDismiss = swipeDismiss) {
                ModelProviderListScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                    currentProviderId = shellHomeState.providerId,
                )
            }
            entry<AppRoute.McpServers>(swipeDismiss = swipeDismiss) {
                McpServersScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.McpServerDetail>(swipeDismiss = swipeDismiss) { route ->
                McpServerDetailScreen(
                    serverId = route.serverId,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviderDetail>(swipeDismiss = swipeDismiss) { route ->
                ModelProviderDetailScreen(
                    providerId = route.providerId,
                    onBack = ::popRoute,
                    currentModelId = shellHomeState.modelId,
                    onSelectCurrentModel = { modelId ->
                        agentState.selectModel(modelId, route.providerId)
                    },
                )
            }
            entry<AppRoute.ContextCompression>(swipeDismiss = swipeDismiss) {
                ContextCompressionSettingsScreen(
                    context = context,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.Assistants>(swipeDismiss = swipeDismiss) { route ->
                AssistantsScreen(
                    picker = route.picker,
                    onNavigate = { destination -> pushRoute(destination) },
                    onBack = ::popRoute,
                    selectedAssistantId = shellHomeState.assistantId.ifBlank { null },
                    onSelectAssistant = agentState::selectAssistant,
                )
            }
            entry<AppRoute.AssistantEdit>(swipeDismiss = swipeDismiss) { route ->
                AssistantEditScreen(
                    assistantId = route.assistantId,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviderAuthMethod>(swipeDismiss = swipeDismiss) { route ->
                ProviderAuthMethodScreen(
                    providerType = route.providerType,
                    onNavigate = { destination -> pushRoute(destination) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviderNew>(swipeDismiss = swipeDismiss) { route ->
                ModelProviderDetailScreen(
                    newType = route.providerType,
                    authMode = route.authMode,
                    onBack = ::popRoute
                )
            }
    }

    AgentPendingChildStopDialog(agentState.conversationPaneState.selectedConversationId)

    if (browserSheetVisible) {
        AgentBrowserScreen(onDismiss = { browserSheetVisible = false }, conversationId = agentState.conversationPaneState.selectedConversationId)
    }

    WhaleMaidInAppHost()

    conversationRenameTarget?.let { conversation ->
        var renameInput by remember(conversation.id) { mutableStateOf(conversation.title) }
        WindowDialog(
            show = true,
            title = stringResource(R.string.conversation_rename_title),
            onDismissRequest = { conversationRenameTarget = null },
        ) {
            Column {
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = stringResource(R.string.conversation_rename_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_save),
                    confirmEnabled = renameInput.isNotBlank(),
                    onCancel = { conversationRenameTarget = null },
                    onConfirm = {
                        agentState.renameConversation(conversation.id, renameInput)
                        conversationRenameTarget = null
                    },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }

    agentState.contextBudgetPrompt?.takeIf {
        it.conversationId == agentState.conversationPaneState.selectedConversationId
    }?.let { prompt ->
        ContextBudgetPromptDialog(
            reason = prompt.reason,
            onDismiss = agentState::dismissContextBudgetPrompt,
            onCompactThisRun = { agentState.allowCurrentRunCompaction(prompt.runId) },
            onStop = { stopMainReply() },
        )
    }

    if (conversationExportConfirmation) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.conversation_export_title),
            summary = stringResource(R.string.conversation_export_summary),
            onDismissRequest = {
                conversationExportConfirmation = false
                conversationExportId = null
            },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.conversation_export_choose_location),
                onCancel = {
                    conversationExportConfirmation = false
                    conversationExportId = null
                },
                onConfirm = {
                    conversationExportConfirmation = false
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val title = conversationExportTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").take(40)
                    conversationExportLauncher.launch("代鱼-$title-$stamp.zip")
                },
            )
        }
    }

    conversationDeleteTarget?.let { conversation ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.conversation_delete_title),
            summary = stringResource(R.string.conversation_delete_message),
            onDismissRequest = { conversationDeleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { conversationDeleteTarget = null },
                onConfirm = {
                    agentState.deleteConversation(conversation.id)
                    conversationDeleteTarget = null
                },
            )
        }
    }

    conversationMoveTarget?.let { conversation ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.drawer_move_to_folder),
            onDismissRequest = { conversationMoveTarget = null },
        ) {
            Column {
                val folders = agentState.conversationPaneState.folders
                MoveFolderOption(
                    label = stringResource(R.string.drawer_chats_chip),
                    selected = conversation.folderId.isNullOrBlank(),
                    onClick = {
                        agentState.moveConversationToFolder(conversation.id, null)
                        conversationMoveTarget = null
                    },
                )
                folders.forEach { folder ->
                    MoveFolderOption(
                        label = folder.name,
                        selected = conversation.folderId == folder.id,
                        onClick = {
                            agentState.moveConversationToFolder(conversation.id, folder.id)
                            conversationMoveTarget = null
                        },
                    )
                }
            }
        }
    }

    messageDeleteTarget?.let { target ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.conversation_delete_message_title),
            summary = if (target.laterTurnCount == 0) {
                stringResource(R.string.conversation_delete_message_body)
            } else {
                pluralStringResource(
                    R.plurals.conversation_delete_later_turns,
                    target.laterTurnCount,
                    target.laterTurnCount,
                )
            },
            onDismissRequest = { messageDeleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { messageDeleteTarget = null },
                onConfirm = {
                    agentState.deleteMessageTurn(target.messageId)
                    messageDeleteTarget = null
                },
            )
        }
    }

    AppUpdateDialog(
        offer = updateOffer,
        currentVersion = currentVersion,
        onDismiss = { updateOffer = null },
    )

    messageRegenerateTarget?.let { target ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.conversation_regenerate_title),
            summary = if (target.laterTurnCount == 0) {
                stringResource(R.string.conversation_regenerate_current_turn)
            } else {
                pluralStringResource(
                    R.plurals.conversation_regenerate_later_turns,
                    target.laterTurnCount,
                    target.laterTurnCount,
                )
            },
            onDismissRequest = { messageRegenerateTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_regenerate),
                destructive = true,
                onCancel = { messageRegenerateTarget = null },
                onConfirm = {
                    agentState.regenerateMessage(target.messageId)
                    messageRegenerateTarget = null
                },
            )
        }
    }
    } // task controls overlay
    } // selected conversation settings context
}

private data class MessageMutationTarget(
    val messageId: String,
    val laterTurnCount: Int,
)

private data class ShellHomeState(
    val selectedContextTaskId: String?,
    val isCompressingContext: Boolean,
    val isWaitingForCompression: Boolean,
    val childContexts: List<io.github.mangi.eta.agent.delegation.SubAgentContextStats>,
    val childStatusRoster: List<io.github.mangi.eta.agent.delegation.SubAgentContextStats>,
    val providerId: String,
    val modelId: String,
    val assistantId: String,
    val pendingConversationMentions: List<io.github.mangi.eta.ui.model.PendingConversationMentionUi>,
    val tokenUsage: io.github.mangi.eta.ui.model.ConversationTokenUsageUi,
)
