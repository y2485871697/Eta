package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.formatTokenCount
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import java.text.NumberFormat
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ConversationTokenUsageDialog(
    show: Boolean,
    usage: ConversationTokenUsageUi,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.action_token_usage),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!usage.hasUsage) {
                Text(
                    text = stringResource(R.string.token_usage_empty),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                UsageRow(
                    label = stringResource(R.string.token_usage_total),
                    value = formatTokenCount(usage.totalTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_page_input_tokens),
                    value = formatTokenCount(usage.inputTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokenCount(usage.outputTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = formatTokenCount(usage.cachedTokens),
                )
                UsageRow(
                    label = stringResource(R.string.token_usage_cache_percent),
                    value = formatCachePercent(usage.cachePercent),
                )
            }
            TextButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
        )
    }
}

private fun formatCachePercent(percent: Double?): String {
    if (percent == null) return "—"
    val format = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return format.format(percent) + "%"
}
