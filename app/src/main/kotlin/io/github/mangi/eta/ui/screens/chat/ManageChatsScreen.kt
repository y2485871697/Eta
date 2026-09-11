package io.github.mangi.eta.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.app.SearchHistoryDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.MessageSearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ManageChatsScreen(
    conversations: List<ConversationSummaryUi>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteConversation: (ConversationSummaryUi) -> Unit,
    onDeleteAll: () -> Unit,
    onSearchHistory: (String) -> List<MessageSearchHit> = { emptyList() },
    onOpenHistoryHit: (MessageSearchHit) -> Unit = {},
) {
    var showDeleteAll by remember { mutableStateOf(false) }
    var showSearchHistory by remember { mutableStateOf(false) }

    MiuixScaffoldPage(
        title = stringResource(R.string.history_page_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showSearchHistory = true }) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.action_search_history),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { if (conversations.isNotEmpty()) showDeleteAll = true }) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.history_page_delete_all),
                    tint = MiuixTheme.colorScheme.error,
                )
            }
        },
    ) {
        if (conversations.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.history_page_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                )
            }
        } else {
            conversations.forEach { conversation ->
                item(key = conversation.id) {
                    SwipeableManageChatRow(
                        conversation = conversation,
                        onClick = { onOpenConversation(conversation.id) },
                        onTogglePin = { onTogglePin(conversation.id) },
                        onDelete = { onDeleteConversation(conversation) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = tween(180),
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ),
                    )
                }
            }
        }
    }

    SearchHistoryDialog(
        show = showSearchHistory,
        onDismiss = { showSearchHistory = false },
        onSearch = onSearchHistory,
        onOpenHit = { hit ->
            showSearchHistory = false
            onOpenHistoryHit(hit)
        },
    )

    if (showDeleteAll) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.history_page_delete_all_title),
            summary = stringResource(R.string.history_page_delete_all_message),
            onDismissRequest = { showDeleteAll = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { showDeleteAll = false },
                onConfirm = {
                    onDeleteAll()
                    showDeleteAll = false
                },
            )
        }
    }
}

@Composable
private fun SwipeableManageChatRow(
    conversation: ConversationSummaryUi,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.4f },
    )
    var collapsing by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    var crossedDeleteThreshold by remember { mutableStateOf(false) }

    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.targetValue }.collectLatest { target ->
            val crossed = target == SwipeToDismissBoxValue.EndToStart
            if (crossed && !crossedDeleteThreshold) {
                crossedDeleteThreshold = true
                TouchHaptics.gestureThreshold(view)
            } else if (!crossed) {
                crossedDeleteThreshold = false
            }
        }
    }

    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.settledValue }.collectLatest { settled ->
            if (!collapsing && settled == SwipeToDismissBoxValue.EndToStart) {
                collapsing = true
            }
        }
    }

    LaunchedEffect(collapsing) {
        if (!collapsing || deleted) return@LaunchedEffect
        delay(300)
        deleted = true
        onDelete()
    }

    AnimatedVisibility(
        visible = !collapsing,
        modifier = modifier,
        exit = fadeOut(animationSpec = tween(160)) + shrinkVertically(
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top,
        ),
    ) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(18.dp)),
            backgroundContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.errorContainer)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MiuixTheme.colorScheme.onErrorContainer,
                    )
                }
            },
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                ManageChatRow(
                    conversation = conversation,
                    onClick = onClick,
                    onTogglePin = onTogglePin,
                )
            }
        }
    }
}

@Composable
private fun ManageChatRow(
    conversation: ConversationSummaryUi,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (conversation.isPinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = conversation.title.ifBlank { conversation.preview },
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = conversation.timeLabel,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                imageVector = if (conversation.isPinned) {
                    Icons.Outlined.PushPin
                } else {
                    Icons.Rounded.PushPin
                },
                contentDescription = stringResource(
                    if (conversation.isPinned) {
                        R.string.conversation_unpin
                    } else {
                        R.string.conversation_pin
                    },
                ),
                modifier = Modifier.size(20.dp),
                tint = if (conversation.isPinned) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }
    }
}
