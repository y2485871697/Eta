package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.screens.assistants.AssistantPickerDialog
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup

private object DrawerMetrics {
    val PaneMaxWidth = 320.dp
    val PaneWidthFraction = 0.78f
    val DrawerCornerRadius = 28.dp
    val DrawerShadowElevation = 12.dp
    const val SettleDampingRatio = 1f
    const val SettleStiffness = 146f
    const val SettleVisibilityThresholdPx = 0.5f
    const val SettlePositionThresholdFraction = 0.5f
    val PaneHorizontalPadding = 16.dp
    val TopInset = 8.dp
    val AfterSearch = 12.dp
    val SearchIconSize = 18.dp
    val SearchCornerRadius = 14.dp
    val SearchVerticalPadding = 10.dp
    val ChipGap = 8.dp
    val AfterChips = 10.dp
    val ListBottomPadding = 12.dp
    val BottomInset = 10.dp
    val ActionIconSize = 20.dp
    val CircleButtonPadding = 10.dp
    val SectionTopPadding = 10.dp
    val SectionBottomPadding = 6.dp
    val RowMinHeight = 44.dp
    val RowGap = 2.dp
    val RowCornerRadius = 18.dp
    val RowHorizontalPadding = 14.dp
    val RowVerticalPadding = 10.dp
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 8.dp
    val EmptyVerticalPadding = 28.dp
    val DockTopGap = 8.dp
    val AssistantBarHeight = 48.dp
}

private enum class ConversationPaneAnchor {
    Closed,
    Open,
}


@Composable
fun ConversationSidePaneScaffold(
    state: ConversationPaneUiState,
    visible: Boolean,
    backHandlerEnabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onNewConversation: () -> Unit,
    onOpenManageChats: () -> Unit = {},
    onSelectFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sceneLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val paneWidth = minOf(maxWidth * DrawerMetrics.PaneWidthFraction, DrawerMetrics.PaneMaxWidth)
        val paneWidthPx = with(density) { paneWidth.toPx() }
        val anchors = remember(paneWidthPx) {
            DraggableAnchors {
                ConversationPaneAnchor.Closed at 0f
                ConversationPaneAnchor.Open at paneWidthPx
            }
        }
        val paneDragState = remember {
            AnchoredDraggableState(
                initialValue = if (visible) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed,
                anchors = anchors,
            )
        }
        val settleAnimation = remember {
            spring<Float>(
                dampingRatio = DrawerMetrics.SettleDampingRatio,
                stiffness = DrawerMetrics.SettleStiffness,
                visibilityThreshold = DrawerMetrics.SettleVisibilityThresholdPx,
            )
        }
        val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
            state = paneDragState,
            positionalThreshold = { distance ->
                distance * DrawerMetrics.SettlePositionThresholdFraction
            },
            animationSpec = settleAnimation,
        )
        val currentVisible by rememberUpdatedState(visible)
        val currentOnOpen by rememberUpdatedState(onOpen)
        val currentOnDismiss by rememberUpdatedState(onDismiss)
        val paneDragInteraction = remember { MutableInteractionSource() }
        val isPaneDragging by paneDragInteraction.collectIsDraggedAsState()
        val paneOffset by remember(paneDragState) {
            derivedStateOf {
                paneDragState.offset.takeUnless(Float::isNaN) ?: 0f
            }
        }
        val showDim = paneOffset > 0.5f
        val interceptContent = if (isPaneDragging) {
            paneDragState.settledValue == ConversationPaneAnchor.Open
        } else {
            paneDragState.targetValue == ConversationPaneAnchor.Open && showDim
        }
        val dimmingInteraction = remember { MutableInteractionSource() }
        val drawerShape = AbsoluteRoundedCornerShape(
            topLeft = 0.dp,
            topRight = DrawerMetrics.DrawerCornerRadius,
            bottomRight = DrawerMetrics.DrawerCornerRadius,
            bottomLeft = 0.dp,
        )
        fun paneDragModifier(enabled: Boolean = backHandlerEnabled): Modifier = Modifier.anchoredDraggable(
            state = paneDragState,
            reverseDirection = false,
            orientation = Orientation.Horizontal,
            enabled = enabled,
            interactionSource = paneDragInteraction,
            flingBehavior = flingBehavior,
        )

        SideEffect {
            paneDragState.updateAnchors(anchors)
        }

        LaunchedEffect(visible, paneWidthPx) {
            val target = if (visible) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed
            if (paneDragState.targetValue != target || paneDragState.settledValue != target) {
                paneDragState.animateTo(target, settleAnimation)
            }
        }

        LaunchedEffect(paneDragState) {
            snapshotFlow { paneDragState.settledValue }.collectLatest { settledValue ->
                val settledOpen = settledValue == ConversationPaneAnchor.Open
                if (settledOpen != currentVisible) {
                    if (settledOpen) currentOnOpen() else currentOnDismiss()
                }
            }
        }

        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = visible &&
                backHandlerEnabled &&
                sceneLifecycleState == Lifecycle.State.RESUMED,
            onBackCompleted = onDismiss,
        )

        val shadowElevationPx = with(density) { DrawerMetrics.DrawerShadowElevation.toPx() }
        val dimColor = MiuixTheme.colorScheme.windowDimming
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(paneDragModifier())
                .drawWithContent {
                    drawContent()
                    val offset = paneDragState.offset.takeUnless(Float::isNaN) ?: 0f
                    val progress = if (paneWidthPx > 0f) {
                        (offset / paneWidthPx).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    if (progress > 0f) {
                        drawRect(color = dimColor, alpha = progress)
                    }
                }
                .zIndex(0f),
        ) {
            content()
        }

        if (interceptContent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        onClick = { currentOnDismiss() },
                        interactionSource = dimmingInteraction,
                        indication = null,
                    )
                    .then(paneDragModifier())
                    .zIndex(1f),
            )
        }

        ConversationPanePanel(
            state = state,
            width = paneWidth,
            drawerShape = drawerShape,
            onSearchChange = onSearchChange,
            onConversationSelected = onConversationSelected,
            onConversationRename = onConversationRename,
            onConversationDelete = onConversationDelete,
            onMoveConversationToFolder = onMoveConversationToFolder,
            onNewConversation = onNewConversation,
            onConversationTogglePin = onConversationTogglePin,
            onOpenManageChats = onOpenManageChats,
            onSelectFolder = onSelectFolder,
            onCreateFolder = onCreateFolder,
            onRenameFolder = onRenameFolder,
            onDeleteFolder = onDeleteFolder,
            onSelectAssistant = onSelectAssistant,
            onEditAssistant = onEditAssistant,
            onOpenAssistants = onOpenAssistants,
            onOpenSettings = onOpenSettings,
            onOpenModelProviders = onOpenModelProviders,
            onOpenUsageStats = onOpenUsageStats,
            onOpenSkills = onOpenSkills,
            onOpenPermissions = onOpenPermissions,
            paneDragState = paneDragState,
            paneDragInteraction = paneDragInteraction,
            flingBehavior = flingBehavior,
            modifier = Modifier
                .graphicsLayer {
                    val offset = paneDragState.offset.takeUnless(Float::isNaN)
                        ?: if (visible) paneWidthPx else 0f
                    val progress = if (paneWidthPx > 0f) {
                        (offset / paneWidthPx).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    translationX = offset - paneWidthPx
                    shadowElevation = shadowElevationPx * progress
                    shape = drawerShape
                    clip = false
                }
                .clip(drawerShape)
                .zIndex(2f),
        )
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    drawerShape: AbsoluteRoundedCornerShape,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onNewConversation: () -> Unit,
    onOpenManageChats: () -> Unit = {},
    onSelectFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    paneDragState: AnchoredDraggableState<ConversationPaneAnchor>,
    paneDragInteraction: MutableInteractionSource,
    flingBehavior: FlingBehavior,
    modifier: Modifier = Modifier,
) {
    val query = state.searchQuery.trim()
    val visibleConversations = remember(state.conversations, query) {
        if (query.isBlank()) {
            state.conversations
        } else {
            state.conversations.filter { conversation ->
                conversation.title.contains(query, ignoreCase = true) ||
                    conversation.preview.contains(query, ignoreCase = true)
            }
        }
    }
    val groups = remember(visibleConversations) { visibleConversations.groupForDrawer() }
    val profiles by AssistantRepository.profiles.collectAsState()
    val activeId by AssistantRepository.activeId.collectAsState()
    val activeAssistant = remember(profiles, activeId) {
        profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }
    var showAssistantPicker by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .anchoredDraggable(
                state = paneDragState,
                reverseDirection = false,
                orientation = Orientation.Horizontal,
                enabled = true,
                interactionSource = paneDragInteraction,
                flingBehavior = flingBehavior,
            ),
        shape = drawerShape,
        color = MiuixTheme.colorScheme.surface,
        contentColor = MiuixTheme.colorScheme.onSurface,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .safeDrawingPadding()
                .padding(horizontal = DrawerMetrics.PaneHorizontalPadding),
        ) {
            Spacer(modifier = Modifier.height(DrawerMetrics.TopInset))
            PaneSearchRow(
                query = state.searchQuery,
                onSearchChange = onSearchChange,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.AfterSearch))
            PaneDrawerActions(
                onNewConversation = onNewConversation,
                onOpenManageChats = onOpenManageChats,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.AfterSearch))
            PaneFolderBar(
                folders = state.folders,
                selectedFolderId = state.selectedFolderId,
                onSelectFolder = onSelectFolder,
                onCreateFolder = onCreateFolder,
                onRenameFolder = onRenameFolder,
                onDeleteFolder = onDeleteFolder,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.AfterChips))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(bottom = DrawerMetrics.ListBottomPadding),
                verticalArrangement = Arrangement.spacedBy(DrawerMetrics.RowGap),
                overscrollEffect = null,
            ) {
                if (visibleConversations.isEmpty()) {
                    item {
                        EmptyConversations(isSearching = query.isNotBlank())
                    }
                } else {
                    groups.forEach { group ->
                        item(key = "section-${group.section}") {
                            ConversationSectionHeader(group = group)
                        }
                        items(
                            items = group.items,
                            key = { it.id },
                        ) { conversation ->
                            ConversationTextRow(
                                conversation = conversation,
                                selected = conversation.id == state.selectedConversationId,
                                onClick = { onConversationSelected(conversation.id) },
                                onRename = { onConversationRename(conversation) },
                                onDelete = { onConversationDelete(conversation) },
                                onMoveToFolder = { onMoveConversationToFolder(conversation) },
                                onTogglePin = { onConversationTogglePin(conversation) },
                            )
                        }
                    }
                }
            }
            PaneAssistantBar(
                name = activeAssistant?.name?.ifBlank { stringResource(R.string.app_name) }
                    ?: stringResource(R.string.app_name),
                onClick = { showAssistantPicker = true },
                avatar = {
                    AssistantAvatar(
                        assistant = activeAssistant,
                        size = 32.dp,
                    )
                },
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.DockTopGap))
            PaneDock(
                onOpenAssistants = onOpenAssistants,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenUsageStats = onOpenUsageStats,
                onOpenSkills = onOpenSkills,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.BottomInset))
        }
    }

    AssistantPickerDialog(
        show = showAssistantPicker,
        onDismiss = { showAssistantPicker = false },
        onSelect = onSelectAssistant,
        onEdit = onEditAssistant,
    )
}

@Composable
private fun PaneSearchRow(
    query: String,
    onSearchChange: (String) -> Unit,
) {
    val textStyle = MiuixTheme.textStyles.body2.merge(
        TextStyle(
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 14.sp,
        ),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DrawerMetrics.SearchCornerRadius))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = DrawerMetrics.SearchVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(DrawerMetrics.SearchIconSize),
            tint = MiuixTheme.colorScheme.onSurface,
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.conversation_search_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun PaneDrawerActions(
    onNewConversation: () -> Unit,
    onOpenManageChats: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DrawerMetrics.ChipGap),
    ) {
        DrawerActionRow(
            label = stringResource(R.string.drawer_new_conversation),
            icon = Icons.AutoMirrored.Rounded.Chat,
            onClick = onNewConversation,
        )
        DrawerActionRow(
            label = stringResource(R.string.drawer_manage_chats),
            icon = Icons.Rounded.History,
            onClick = onOpenManageChats,
        )
    }
}

@Composable
private fun DrawerActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DrawerMetrics.SearchCornerRadius))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(onClick) {
                detectTapGestures(onTap = {
                    TouchHaptics.click(view)
                    onClick()
                })
            }
            .padding(horizontal = 12.dp, vertical = DrawerMetrics.SearchVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PaneFolderBar(
    folders: List<ConversationFolderUi>,
    selectedFolderId: String?,
    onSelectFolder: (String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    var createFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<ConversationFolderUi?>(null) }
    var folderToDelete by remember { mutableStateOf<ConversationFolderUi?>(null) }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DrawerMetrics.ChipGap),
    ) {
        item(key = "unfiled") {
            DrawerChip(
                label = stringResource(R.string.drawer_chats_chip),
                selected = selectedFolderId == null,
                icon = null,
                onClick = { onSelectFolder(null) },
            )
        }
        items(folders, key = { it.id }) { folder ->
            var showMenu by remember(folder.id) { mutableStateOf(false) }
            Box {
                DrawerChip(
                    label = folder.name,
                    selected = selectedFolderId == folder.id,
                    icon = Icons.Rounded.Folder,
                    onClick = { onSelectFolder(folder.id) },
                    onLongClick = { showMenu = true },
                )
                WindowListPopup(
                    show = showMenu,
                    popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                    alignment = PopupPositionProvider.Align.BottomEnd,
                    onDismissRequest = { showMenu = false },
                ) {
                    val renameText = stringResource(R.string.action_rename)
                    val deleteText = stringResource(R.string.action_delete)
                    val renameItem = remember(renameText) {
                        DropdownItem(
                            text = renameText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = null,
                                    modifier = modifier.size(DrawerMetrics.ActionIconSize),
                                )
                            },
                        )
                    }
                    val deleteItem = remember(deleteText) {
                        DropdownItem(
                            text = deleteText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    modifier = modifier.size(DrawerMetrics.ActionIconSize),
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                    val deleteColors = DropdownDefaults.dropdownColors(
                        contentColor = MiuixTheme.colorScheme.error,
                        selectedContentColor = MiuixTheme.colorScheme.error,
                        selectedIndicatorColor = MiuixTheme.colorScheme.error,
                    )
                    ListPopupColumn {
                        DropdownImpl(
                            item = renameItem,
                            optionSize = 2,
                            isSelected = false,
                            index = 0,
                            onSelectedIndexChange = {
                                showMenu = false
                                folderToRename = folder
                            },
                        )
                        DropdownImpl(
                            item = deleteItem,
                            optionSize = 2,
                            isSelected = false,
                            index = 1,
                            dropdownColors = deleteColors,
                            onSelectedIndexChange = {
                                showMenu = false
                                folderToDelete = folder
                            },
                        )
                    }
                }
            }
        }
        item(key = "new-folder") {
            DrawerChip(
                label = stringResource(R.string.drawer_new_folder),
                selected = false,
                icon = Icons.Rounded.CreateNewFolder,
                onClick = { createFolder = true },
            )
        }
    }

    if (createFolder) {
        FolderNameDialog(
            title = stringResource(R.string.drawer_create_folder_title),
            initialName = "",
            onDismiss = { createFolder = false },
            onConfirm = { name ->
                onCreateFolder(name)
                createFolder = false
            },
        )
    }
    folderToRename?.let { folder ->
        FolderNameDialog(
            title = stringResource(R.string.drawer_rename_folder_title),
            initialName = folder.name,
            onDismiss = { folderToRename = null },
            onConfirm = { name ->
                onRenameFolder(folder.id, name)
                folderToRename = null
            },
        )
    }
    folderToDelete?.let { folder ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.drawer_delete_folder_title),
            summary = stringResource(R.string.drawer_delete_folder_message),
            onDismissRequest = { folderToDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { folderToDelete = null },
                onConfirm = {
                    onDeleteFolder(folder.id)
                    folderToDelete = null
                },
            )
        }
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.drawer_folder_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_save),
                confirmEnabled = name.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = { onConfirm(name) },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun DrawerChip(
    label: String,
    selected: Boolean,
    icon: ImageVector?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val background = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    val view = LocalView.current
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(background)
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = {
                        TouchHaptics.click(view)
                        onClick()
                    },
                    onLongPress = {
                        if (onLongClick != null) {
                            TouchHaptics.longPress(view)
                            onLongClick()
                        }
                    },
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor,
            )
        }
        Text(
            text = label,
            color = contentColor,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
) {
    Text(
        text = group.localizedLabel(),
        color = MiuixTheme.colorScheme.primary,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = DrawerMetrics.RowHorizontalPadding,
                top = DrawerMetrics.SectionTopPadding,
                bottom = DrawerMetrics.SectionBottomPadding,
            ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTextRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: () -> Unit,
    onTogglePin: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    val view = LocalView.current

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.RowMinHeight)
                .clip(RoundedCornerShape(DrawerMetrics.RowCornerRadius))
                .background(
                    if (selected) {
                        MiuixTheme.colorScheme.surfaceContainerHigh
                    } else {
                        Color.Transparent
                    },
                )
                .combinedClickable(
                    hapticFeedbackEnabled = false,
                    onClick = {
                        TouchHaptics.click(view)
                        onClick()
                    },
                    onLongClick = {
                        TouchHaptics.longPress(view)
                        showActionMenu = true
                    },
                )
                .padding(
                    horizontal = DrawerMetrics.RowHorizontalPadding,
                    vertical = DrawerMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = conversation.title.ifBlank { conversation.preview },
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (conversation.isPinned) {
                Icon(
                    imageVector = Icons.Rounded.PushPin,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
            if (conversation.isActiveRun) {
                Box(
                    modifier = Modifier
                        .padding(start = DrawerMetrics.ActiveDotGap)
                        .size(DrawerMetrics.ActiveDotSize)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                )
            }
        }

        WindowListPopup(
            show = showActionMenu,
            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
            alignment = PopupPositionProvider.Align.BottomEnd,
            onDismissRequest = { showActionMenu = false },
        ) {
            val renameText = stringResource(R.string.action_rename)
            val pinText = stringResource(
                if (conversation.isPinned) {
                    R.string.conversation_unpin
                } else {
                    R.string.conversation_pin
                },
            )
            val moveText = stringResource(R.string.drawer_move_to_folder)
            val deleteText = stringResource(R.string.action_delete)
            val isPinned = conversation.isPinned
            val renameItem = remember(renameText) {
                DropdownItem(
                    text = renameText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = modifier.size(DrawerMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val pinItem = remember(pinText, isPinned) {
                DropdownItem(
                    text = pinText,
                    icon = { modifier ->
                        Icon(
                            imageVector = if (isPinned) {
                                Icons.Outlined.PushPin
                            } else {
                                Icons.Rounded.PushPin
                            },
                            contentDescription = null,
                            modifier = modifier.size(DrawerMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val moveItem = remember(moveText) {
                DropdownItem(
                    text = moveText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            modifier = modifier.size(DrawerMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val deleteItem = remember(deleteText) {
                DropdownItem(
                    text = deleteText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = modifier.size(DrawerMetrics.ActionIconSize),
                            tint = MiuixTheme.colorScheme.error,
                        )
                    },
                )
            }
            val deleteColors = DropdownDefaults.dropdownColors(
                contentColor = MiuixTheme.colorScheme.error,
                selectedContentColor = MiuixTheme.colorScheme.error,
                selectedIndicatorColor = MiuixTheme.colorScheme.error,
            )
            ListPopupColumn {
                DropdownImpl(
                    item = renameItem,
                    optionSize = 4,
                    isSelected = false,
                    index = 0,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onRename()
                    },
                )
                DropdownImpl(
                    item = pinItem,
                    optionSize = 4,
                    isSelected = false,
                    index = 1,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onTogglePin()
                    },
                )
                DropdownImpl(
                    item = moveItem,
                    optionSize = 4,
                    isSelected = false,
                    index = 2,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onMoveToFolder()
                    },
                )
                DropdownImpl(
                    item = deleteItem,
                    optionSize = 4,
                    isSelected = false,
                    index = 3,
                    dropdownColors = deleteColors,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean) {
    Text(
        text = stringResource(
            if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(
            horizontal = DrawerMetrics.RowHorizontalPadding,
            vertical = DrawerMetrics.EmptyVerticalPadding,
        ),
    )
}

@Composable
private fun PaneAssistantBar(
    name: String,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DrawerMetrics.AssistantBarHeight)
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                TouchHaptics.click(view)
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
        )
        avatar()
    }
}

@Composable
private fun PaneDock(
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrawerCircleButton(
            icon = Icons.Outlined.Mood,
            label = stringResource(R.string.assistant_list_title),
            onClick = onOpenAssistants,
        )
        Spacer(modifier = Modifier.width(10.dp))
        DrawerCircleButton(
            icon = Icons.Rounded.Memory,
            label = stringResource(R.string.conversation_dock_models),
            onClick = onOpenModelProviders,
        )
        Spacer(modifier = Modifier.width(10.dp))
        DrawerCircleButton(
            icon = Icons.Rounded.Extension,
            label = stringResource(R.string.route_skills),
            onClick = onOpenSkills,
        )
        Spacer(modifier = Modifier.width(10.dp))
        DrawerCircleButton(
            icon = Icons.Rounded.BarChart,
            label = stringResource(R.string.stats_page_title),
            onClick = onOpenUsageStats,
        )
        Spacer(modifier = Modifier.weight(1f))
        DrawerCircleButton(
            icon = Icons.Rounded.Settings,
            label = stringResource(R.string.route_settings),
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun DrawerCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primaryContainer)
            .clickable {
                TouchHaptics.click(view)
                onClick()
            }
            .padding(DrawerMetrics.CircleButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(DrawerMetrics.ActionIconSize),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
)

private sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
}

private fun List<ConversationSummaryUi>.groupForDrawer(): List<ConversationDrawerGroup> {
    if (isEmpty()) return emptyList()
    val groups = mutableListOf<ConversationDrawerGroup>()
    for (conversation in this) {
        val section = conversation.drawerSection()
        val last = groups.lastOrNull()
        if (last?.section == section) {
            groups[groups.lastIndex] = last.copy(items = last.items + conversation)
        } else {
            groups += ConversationDrawerGroup(section = section, items = listOf(conversation))
        }
    }
    return groups
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isActiveRun || isUpdatedToday(updatedAtMillis) -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}

private fun isUpdatedToday(timestampMillis: Long): Boolean {
    if (timestampMillis <= 0L) return true
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestampMillis }
    return now.get(java.util.Calendar.ERA) == target.get(java.util.Calendar.ERA) &&
        now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
}
