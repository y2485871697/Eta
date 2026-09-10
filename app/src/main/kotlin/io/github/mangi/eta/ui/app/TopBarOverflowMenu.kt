package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaDropdownMenu
import io.github.mangi.eta.ui.model.MessageSearchHit
import io.github.mangi.eta.ui.CompressConversationDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val TopBarMenuIconSize = 24.dp
private val CompactMenuItemModifier = Modifier.height(40.dp)
private val CompactMenuItemPadding = PaddingValues(horizontal = 12.dp)

@Composable
internal fun TopBarOverflowMenu(
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    autoCompressEnabled: Boolean,
    onToggleAutoCompress: (Boolean) -> Unit,
    onCompressConversation: (
        providerId: String?,
        modelId: String?,
        targetTokens: Int,
        keepRecent: Int,
        onFinished: (Boolean) -> Unit,
    ) -> Unit = { _, _, _, _, done -> done(false) },
    onSearchHistory: (String) -> List<MessageSearchHit> = { emptyList() },
    onOpenHistoryHit: (MessageSearchHit) -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box {
        IconButton(
            onClick = {
                onRefreshKimiWeb()
                showMenu = true
            }
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.action_more),
            )
        }

        EtaDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = DpOffset(x = (-2).dp, y = 10.dp),
            alignEnd = true,
        ) {
            DropdownMenuItem(
                modifier = CompactMenuItemModifier,
                contentPadding = CompactMenuItemPadding,
                text = { Text(stringResource(R.string.action_new_conversation)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Forum,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize),
                    )
                },
                onClick = { showMenu = false; onNewConversation() },
            )
            DropdownMenuItem(
                modifier = CompactMenuItemModifier,
                contentPadding = CompactMenuItemPadding,
                text = { Text(stringResource(R.string.action_search_history)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize),
                    )
                },
                onClick = { showMenu = false; showSearchDialog = true },
            )
            MenuSectionDivider()
            DropdownMenuItem(
                modifier = CompactMenuItemModifier,
                contentPadding = CompactMenuItemPadding,
                text = { Text(stringResource(R.string.action_open_terminal)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize),
                    )
                },
                onClick = { showMenu = false; onOpenTerminal() },
            )
            DropdownMenuItem(
                modifier = CompactMenuItemModifier,
                contentPadding = CompactMenuItemPadding,
                text = { Text(stringResource(R.string.action_open_browser)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize),
                    )
                },
                onClick = { showMenu = false; onOpenBrowser() },
            )
            DropdownMenuItem(
                modifier = CompactMenuItemModifier,
                contentPadding = CompactMenuItemPadding,
                text = { Text(stringResource(R.string.action_browse_workspace_files)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize),
                    )
                },
                onClick = { showMenu = false },
            )
            DropdownMenuItem(
                modifier = CompactMenuItemModifier,
                contentPadding = CompactMenuItemPadding,
                text = { Text(kimiWebLabel) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_kimi_code),
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize),
                    )
                },
                onClick = { showMenu = false; onLaunchKimiWeb() },
            )
            MenuSectionDivider()
            DropdownMenuItem(
                modifier = CompactMenuItemModifier,
                contentPadding = CompactMenuItemPadding,
                text = { Text(stringResource(R.string.action_token_usage)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.DataUsage,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize),
                    )
                },
                onClick = { showMenu = false },
            )
            DropdownMenuItem(
                modifier = CompactMenuItemModifier,
                contentPadding = CompactMenuItemPadding,
                text = { Text(stringResource(R.string.action_compress_conversation)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Summarize,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize),
                    )
                },
                onClick = {
                    showMenu = false
                    showCompressDialog = true
                },
            )
            DropdownMenuItem(
                modifier = CompactMenuItemModifier,
                contentPadding = CompactMenuItemPadding,
                text = { Text(stringResource(R.string.action_auto_compress_context)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Compress,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize),
                    )
                },
                trailingIcon = {
                    Switch(
                        checked = autoCompressEnabled,
                        onCheckedChange = null,
                        modifier = Modifier.scale(0.72f),
                    )
                },
                onClick = {
                    onToggleAutoCompress(!autoCompressEnabled)
                },
            )
            if (canStopKimiWeb) {
                MenuSectionDivider()
                DropdownMenuItem(
                    modifier = CompactMenuItemModifier,
                    contentPadding = CompactMenuItemPadding,
                    text = { Text(stringResource(R.string.capability_kimi_stop)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(TopBarMenuIconSize),
                        )
                    },
                    onClick = { showMenu = false; onStopKimiWeb() },
                )
            }
        }
    }

    CompressConversationDialog(
        show = showCompressDialog,
        onDismiss = { showCompressDialog = false },
        onConfirm = onCompressConversation,
    )
    SearchHistoryDialog(
        show = showSearchDialog,
        onDismiss = { showSearchDialog = false },
        onSearch = onSearchHistory,
        onOpenHit = onOpenHistoryHit,
    )
}

@Composable
private fun MenuSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        thickness = 1.dp,
        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f),
    )
}
