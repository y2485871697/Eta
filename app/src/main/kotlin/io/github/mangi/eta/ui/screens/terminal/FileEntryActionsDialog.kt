package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Immutable
internal data class FileEntryActions(
    val path: String,
    val name: String,
    val directory: Boolean,
    val canExport: Boolean,
    val canDelete: Boolean,
)

@Composable
internal fun FileEntryActionsDialog(
    target: FileEntryActions,
    enabled: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = target.name,
        onDismissRequest = { if (enabled) onDismiss() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (target.canExport) {
                TextButton(
                    text = stringResource(R.string.linux_files_export),
                    onClick = onExport,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            if (target.canDelete) {
                TextButton(
                    text = stringResource(R.string.action_delete),
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(
                        color = MiuixTheme.colorScheme.error,
                        textColor = MiuixTheme.colorScheme.onError,
                    ),
                )
            }
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
