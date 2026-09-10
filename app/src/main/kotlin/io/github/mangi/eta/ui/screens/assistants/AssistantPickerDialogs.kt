package io.github.mangi.eta.ui.screens.assistants

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.AssistantAvatar
import io.github.mangi.eta.ui.components.MiuixDialogActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AssistantPickerDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val profiles by AssistantRepository.profiles.collectAsState()
    val activeId by AssistantRepository.activeId.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var actionProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
        .coerceIn(240.dp, 420.dp)

    LaunchedEffect(show) {
        if (!show) {
            searchQuery = ""
            actionProfile = null
            deleteProfile = null
        }
    }

    val filtered = remember(profiles, searchQuery) {
        val query = searchQuery.trim()
        profiles.filter { profile ->
            query.isBlank() || profile.name.contains(query, ignoreCase = true)
        }
    }

    WindowDialog(
        show = show,
        title = stringResource(R.string.assistant_list_title),
        onDismissRequest = {
            if (actionProfile == null && deleteProfile == null) onDismiss()
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = stringResource(R.string.assistant_search),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .heightIn(max = maxListHeight),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.id }) { profile ->
                    AssistantProfileCard(
                        profile = profile,
                        selected = profile.id == activeId,
                        onClick = {
                            onSelect(profile.id)
                            onDismiss()
                        },
                        onLongClick = { actionProfile = profile },
                    )
                }
                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.assistant_empty),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }

    actionProfile?.let { profile ->
        AssistantActionsDialog(
            profile = profile,
            canDelete = profiles.size > 1,
            onCopy = {
                actionProfile = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AssistantRepository.duplicate(profile.id)
                    }
                }
            },
            onEdit = {
                actionProfile = null
                onDismiss()
                onEdit(profile.id)
            },
            onDelete = {
                actionProfile = null
                deleteProfile = profile
            },
            onDismiss = { actionProfile = null },
        )
    }

    deleteProfile?.let { profile ->
        AssistantDeleteDialog(
            profile = profile,
            canDelete = profiles.size > 1,
            onDismiss = { deleteProfile = null },
            onConfirm = {
                val target = profile
                deleteProfile = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AssistantRepository.delete(target.id)
                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantProfileCard(
    profile: AssistantProfile,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onLongClick()
                    },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantAvatar(assistant = profile, size = 44.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = profile.name,
                    style = MiuixTheme.textStyles.title4,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Text(
                        text = stringResource(R.string.assistant_current),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AssistantActionsDialog(
    profile: AssistantProfile,
    canDelete: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = profile.name,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.ui_copy_4edd1d),
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.ui_edit_a7f814),
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            TextButton(
                text = stringResource(R.string.action_delete),
                onClick = onDelete,
                enabled = canDelete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error,
                    textColor = MiuixTheme.colorScheme.onError,
                ),
            )
        }
    }
}

@Composable
internal fun AssistantDeleteDialog(
    profile: AssistantProfile,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = true,
        title = stringResource(R.string.assistant_delete_title),
        summary = stringResource(R.string.assistant_delete_body, profile.name),
        onDismissRequest = onDismiss,
    ) {
        MiuixDialogActions(
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            confirmEnabled = canDelete,
            onCancel = onDismiss,
            onConfirm = onConfirm,
        )
    }
}
