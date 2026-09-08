package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs

private val TopBarMenuIconSize = 24.dp

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
) {
    var showMenu by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box {
        IconButton(
            onClick = {
                onRefreshKimiWeb()
                showMenu = true
            }
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.action_more),
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = DpOffset(x = 0.dp, y = 0.dp),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_new_conversation)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.AddComment,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize)
                    )
                },
                onClick = { showMenu = false; onNewConversation() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_open_terminal)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize)
                    )
                },
                onClick = { showMenu = false; onOpenTerminal() }
            )
            DropdownMenuItem(
                text = { Text(kimiWebLabel) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_kimi_code),
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize)
                    )
                },
                onClick = { showMenu = false; onLaunchKimiWeb() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_open_browser)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Language,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize)
                    )
                },
                onClick = { showMenu = false; onOpenBrowser() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_auto_compress_context)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Compress,
                        contentDescription = null,
                        modifier = Modifier.size(TopBarMenuIconSize)
                    )
                },
                trailingIcon = {
                    androidx.compose.material3.Switch(
                        checked = autoCompressEnabled,
                        onCheckedChange = null,
                        modifier = Modifier.size(TopBarMenuIconSize)
                    )
                },
                onClick = {
                    showMenu = false
                    onToggleAutoCompress(!autoCompressEnabled)
                }
            )
            if (canStopKimiWeb) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.capability_kimi_stop)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(TopBarMenuIconSize)
                        )
                    },
                    onClick = { showMenu = false; onStopKimiWeb() }
                )
            }
        }
    }
}
