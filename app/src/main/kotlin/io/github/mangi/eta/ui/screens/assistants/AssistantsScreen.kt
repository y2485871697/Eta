package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantsScreen(
    picker: Boolean,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val profiles by AssistantRepository.profiles.collectAsState()
    val activeId by AssistantRepository.activeId.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var actionProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }

    val filtered = remember(profiles, searchQuery) {
        val query = searchQuery.trim()
        profiles.filter { profile ->
            query.isBlank() || profile.name.contains(query, ignoreCase = true)
        }
    }

    fun syncRuntime() {
        scope.launch {
            withContext(Dispatchers.IO) {
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            }
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.assistant_list_title),
        onBack = onBack,
        actions = {
            if (!picker) {
                IconButton(
                    onClick = {
                        scope.launch {
                            val created = withContext(Dispatchers.IO) {
                                AssistantRepository.create()
                            }
                            onNavigate(AppRoute.AssistantEdit(created.id))
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.assistant_add),
                    )
                }
            }
        },
    ) {
        item(key = "search") {
            InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.assistant_search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 8.dp),
            )
        }
        filtered.forEach { profile ->
            item(key = profile.id) {
                val selected = profile.id == activeId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (picker) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                AssistantRepository.select(profile.id)
                                            }
                                            syncRuntime()
                                            onBack()
                                        }
                                    } else {
                                        onNavigate(AppRoute.AssistantEdit(profile.id))
                                    }
                                },
                                onLongClick = { actionProfile = profile },
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistantAvatar(assistant = profile, size = 44.dp)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
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
                        if (picker && selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.assistant_empty),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }

    actionProfile?.let { profile ->
        WindowDialog(
            show = true,
            title = profile.name,
            onDismissRequest = { actionProfile = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = stringResource(R.string.ui_copy_4edd1d),
                    onClick = {
                        actionProfile = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AssistantRepository.duplicate(profile.id)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = stringResource(R.string.ui_edit_a7f814),
                    onClick = {
                        actionProfile = null
                        onNavigate(AppRoute.AssistantEdit(profile.id))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        actionProfile = null
                        deleteProfile = profile
                    },
                    enabled = profiles.size > 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { actionProfile = null },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    deleteProfile?.let { profile ->
        OverlayDialog(
            show = true,
            title = stringResource(R.string.assistant_delete_title),
            summary = stringResource(R.string.assistant_delete_body, profile.name),
            onDismissRequest = { deleteProfile = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                confirmEnabled = profiles.size > 1,
                onCancel = { deleteProfile = null },
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
}
