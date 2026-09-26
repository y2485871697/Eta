package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.data.repository.ProviderBalanceStore
import io.github.mangi.eta.ui.pages.providers.ProviderBalanceIndicator
import io.github.mangi.eta.ui.pages.providers.activityLifecycleOwnerOrNull
import io.github.mangi.eta.ui.pages.providers.hasBalanceIndicatorContent
import io.github.mangi.eta.ui.components.AdaptiveTopAppBar
import io.github.mangi.eta.ui.components.ConversationSidePaneScaffold
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.components.TopBarBackdrop
import io.github.mangi.eta.ui.components.captureForTopBar
import io.github.mangi.eta.ui.components.rememberTopBarBackdrop
import io.github.mangi.eta.ui.components.topBarContainerColor
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.MessageSearchHit
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar

/**
 * Agent App 统一壳层。
 *
 * - 负责全局 Scaffold、状态栏/横向安全边距、顶层工具栏。
 * - 首页工具栏只保留历史入口与溢出菜单（新建对话、终端、浏览器），保持聊天舞台干净。
 * - 非首页子路由统一提供返回按钮与标题，避免每个页面各自像独立设置页。
 * - Settings 由标准二级页骨架自己提供 TopAppBar，壳层在此路由不重复绘制。
 */
@Composable
internal fun AgentAppShell(
    currentRoute: AppRoute?,
    isCurrentRoute: Boolean,
    conversationPaneState: ConversationPaneUiState?,
    currentConversationTitle: String? = null,
    isConversationPaneOpen: Boolean,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onDismissConversationPane: () -> Unit,
    onSearchConversations: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenWorkspace: () -> Unit,
    autoCompressEnabled: Boolean = false,
    isCompressingContext: Boolean = false,
    onToggleAutoCompress: (Boolean) -> Unit = {},
    onCompressConversation: (
        providerId: String?,
        modelId: String?,
        onFinished: (Boolean) -> Unit,
    ) -> Unit = { _, _, done -> done(false) },
    onSearchHistory: (String) -> List<MessageSearchHit> = { emptyList() },
    onOpenHistoryHit: (MessageSearchHit) -> Unit = {},
    tokenUsage: ConversationTokenUsageUi = ConversationTokenUsageUi(),
    selectedProviderId: String? = null,
    onSelectConversation: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onConversationExport: (ConversationSummaryUi) -> Unit = {},
    onOpenManageChats: () -> Unit = {},
    onSelectFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit = {},
    onEditAssistant: (String) -> Unit = {},
    onOpenAssistants: () -> Unit = {},
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    // App 回到前台时补刷一次余额，并调用 start 以恢复可能已停止的轮询。
    // 这里挂在壳层的 Activity 生命周期上（壳层在每个路由都会组合），
    // 用去抖避免导航导致的观察者重建触发多余请求。
    val balanceContext = LocalContext.current
    val balanceLifecycleOwner = remember(balanceContext) { balanceContext.activityLifecycleOwnerOrNull() }
    val balanceScope = balanceLifecycleOwner?.lifecycleScope
    DisposableEffect(balanceLifecycleOwner) {
        val owner = balanceLifecycleOwner
        val scope = balanceScope
        if (owner == null || scope == null) {
            onDispose { }
        } else {
            ProviderBalanceStore.start(scope)
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastBalanceForegroundRefreshAtMs >= BalanceForegroundRefreshDebounceMs) {
                        lastBalanceForegroundRefreshAtMs = now
                        ProviderBalanceStore.start(scope)
                        ProviderBalanceStore.requestRefresh(scope)
                    }
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    val pageContent: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
            topBar = {
                if (currentRoute !is AppRoute.Settings) {
                    TopBarBackdrop(backdrop) {
                        AgentTopBar(
                            route = currentRoute,
                            scrollBehavior = scrollBehavior,
                            color = topBarColor,
                            onBack = onBack,
                            onOpenConversationPane = onOpenConversationPane,
                            onNewConversation = onNewConversation,
                            onOpenTerminal = onOpenTerminal,
                            onLaunchKimiWeb = onLaunchKimiWeb,
                            kimiWebLabel = kimiWebLabel,
                            canStopKimiWeb = canStopKimiWeb,
                            onStopKimiWeb = onStopKimiWeb,
                            onRefreshKimiWeb = onRefreshKimiWeb,
                            onOpenBrowser = onOpenBrowser,
                            onOpenWorkspace = onOpenWorkspace,
                            currentConversationTitle = currentConversationTitle,
                            autoCompressEnabled = autoCompressEnabled,
                            isCompressingContext = isCompressingContext,
                            onToggleAutoCompress = onToggleAutoCompress,
                            onCompressConversation = onCompressConversation,
                            onSearchHistory = onSearchHistory,
                            onOpenHistoryHit = onOpenHistoryHit,
                            tokenUsage = tokenUsage,
                            selectedProviderId = selectedProviderId,
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .captureForTopBar(backdrop)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                content(padding)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onConversationSelected = onSelectConversation,
                onConversationRename = onConversationRename,
                onConversationDelete = onConversationDelete,
                onMoveConversationToFolder = onMoveConversationToFolder,
                onNewConversation = onNewConversation,
                onConversationTogglePin = onConversationTogglePin,
                onConversationExport = onConversationExport,
                onOpenManageChats = onOpenManageChats,
                onSelectFolder = onSelectFolder,
                onCreateFolder = onCreateFolder,
                onRenameFolder = onRenameFolder,
                onDeleteFolder = onDeleteFolder,
                selectedAssistantId = selectedAssistantId,
                onSelectAssistant = onSelectAssistant,
                onEditAssistant = onEditAssistant,
                onOpenAssistants = onOpenAssistants,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenUsageStats = onOpenUsageStats,
                onOpenSkills = onOpenSkills,
                onOpenPermissions = onOpenPermissions,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }
}

@Composable
private fun AgentTopBar(
    route: AppRoute?,
    currentConversationTitle: String? = null,
    scrollBehavior: ScrollBehavior,
    color: Color,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenWorkspace: () -> Unit,
    autoCompressEnabled: Boolean = false,
    isCompressingContext: Boolean = false,
    onToggleAutoCompress: (Boolean) -> Unit = {},
    onCompressConversation: (
        providerId: String?,
        modelId: String?,
        onFinished: (Boolean) -> Unit,
    ) -> Unit = { _, _, done -> done(false) },
    onSearchHistory: (String) -> List<MessageSearchHit> = { emptyList() },
    onOpenHistoryHit: (MessageSearchHit) -> Unit = {},
    tokenUsage: ConversationTokenUsageUi = ConversationTokenUsageUi(),
    selectedProviderId: String? = null,
) {
    val view = LocalView.current
    val isHome = route is AppRoute.Home
    val navigationIcon: @Composable () -> Unit = {
        if (isHome) {
            IconButton(onClick = {
                TouchHaptics.click(view)
                onOpenConversationPane()
            }) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.action_conversation_history),
                )
            }
        } else {
            MiuixBackButton(onClick = onBack)
        }
    }
    val balanceStates by ProviderBalanceStore.states.collectAsState()
    val selectedBalanceState = selectedProviderId?.let(balanceStates::get)
    val actions: @Composable RowScope.() -> Unit = {
        if (isHome) {
            if (hasBalanceIndicatorContent(selectedBalanceState)) {
                ProviderBalanceIndicator(
                    state = selectedBalanceState,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            TopBarOverflowMenu(
                onNewConversation = onNewConversation,
                onOpenTerminal = onOpenTerminal,
                onLaunchKimiWeb = onLaunchKimiWeb,
                kimiWebLabel = kimiWebLabel,
                canStopKimiWeb = canStopKimiWeb,
                onStopKimiWeb = onStopKimiWeb,
                onRefreshKimiWeb = onRefreshKimiWeb,
                onOpenBrowser = onOpenBrowser,
                onOpenWorkspace = onOpenWorkspace,
                autoCompressEnabled = autoCompressEnabled,
                isCompressingContext = isCompressingContext,
                onToggleAutoCompress = onToggleAutoCompress,
                onCompressConversation = onCompressConversation,
                onSearchHistory = onSearchHistory,
                onOpenHistoryHit = onOpenHistoryHit,
                tokenUsage = tokenUsage,
            )
        }
    }

    if (isHome) {
        // 首页聊天舞台保持紧凑；二级内容页统一使用可折叠大标题。
        SmallTopAppBar(
            title = titleForRoute(route, currentConversationTitle),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    } else {
        AdaptiveTopAppBar(
            title = titleForRoute(route, currentConversationTitle),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

@Composable
private fun titleForRoute(route: AppRoute?, currentConversationTitle: String? = null): String = when (route) {
    is AppRoute.Home -> currentConversationTitle ?: stringResource(R.string.app_name)
    is AppRoute.Chat -> stringResource(R.string.route_chat)
    is AppRoute.Browser -> stringResource(R.string.route_browser)
    is AppRoute.Terminal -> stringResource(R.string.route_terminal)
    is AppRoute.Tools -> stringResource(R.string.route_tools)
    is AppRoute.AgentTaskPreference -> stringResource(R.string.agent_task_surface_title)
    is AppRoute.VirtualDisplayRecovery -> stringResource(R.string.vd_recovery_title)
    is AppRoute.Haptics -> stringResource(R.string.haptics_title)
    is AppRoute.Skills -> stringResource(R.string.route_skills)
    is AppRoute.Permissions -> stringResource(R.string.route_permissions)
    is AppRoute.SystemEnhance -> stringResource(R.string.route_system_enhancements)
    is AppRoute.Settings -> stringResource(R.string.route_settings)
    is AppRoute.SpeechSettings -> stringResource(R.string.speech_title)
    is AppRoute.AuxiliaryVision -> stringResource(R.string.auxiliary_vision_title)
    is AppRoute.SubAgents -> "子代理"
    is AppRoute.TitleModel -> stringResource(R.string.title_model_title)
    is AppRoute.TtsSettings -> stringResource(R.string.tts_title)
    is AppRoute.VoiceModeSettings -> stringResource(R.string.voice_mode_title)
    is AppRoute.AppearanceSettings -> stringResource(R.string.appearance_title)
    is AppRoute.DataBackup -> stringResource(R.string.data_backup_title)
    is AppRoute.Memory -> stringResource(R.string.route_memory)
    is AppRoute.LinuxEnvironment -> stringResource(R.string.route_linux_environment)
    is AppRoute.Workspace -> stringResource(R.string.capability_workspace)
    is AppRoute.SharedFolders -> stringResource(R.string.route_shared_folders)
    is AppRoute.LinuxFiles -> stringResource(R.string.route_linux_files)
    is AppRoute.ModelProviders -> stringResource(R.string.route_model_providers)
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderAuthMethod -> stringResource(R.string.provider_auth_method_title)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    is AppRoute.ContextCompression -> stringResource(R.string.route_context_compression)
    is AppRoute.UsageStats -> stringResource(R.string.stats_page_title)
    is AppRoute.ManageChats -> stringResource(R.string.history_page_title)
    is AppRoute.Assistants -> stringResource(R.string.assistant_list_title)
    is AppRoute.AssistantEdit -> stringResource(R.string.assistant_edit_title)
    null -> stringResource(R.string.app_name)
}

private const val BalanceForegroundRefreshDebounceMs = 15_000L

private var lastBalanceForegroundRefreshAtMs = 0L
