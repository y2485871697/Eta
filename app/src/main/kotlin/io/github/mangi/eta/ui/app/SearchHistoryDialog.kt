package io.github.mangi.eta.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.MessageSearchHit
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun SearchHistoryDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onSearch: (String) -> List<MessageSearchHit>,
    onOpenHit: (MessageSearchHit) -> Unit,
    showConversationTitle: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MessageSearchHit>>(emptyList()) }

    LaunchedEffect(show) {
        if (!show) {
            query = ""
            results = emptyList()
        }
    }

    LaunchedEffect(show, query) {
        if (!show) return@LaunchedEffect
        results = onSearch(query)
    }

    WindowDialog(
        show = show,
        title = stringResource(R.string.action_search_history),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.search_history_hint),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            when {
                query.isBlank() -> {
                    Text(
                        text = stringResource(R.string.search_history_empty),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                results.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.search_history_no_results),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(
                            items = results,
                            key = { "${it.conversationId}:${it.messageId}" },
                        ) { hit ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenHit(hit)
                                        onDismiss()
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                if (showConversationTitle) {
                                    Text(
                                        text = hit.conversationTitle,
                                        style = MiuixTheme.textStyles.body1,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = "${hit.roleLabel} · ${hit.snippet}",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
