package io.github.mangi.eta.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.UsageStatsRepository
import io.github.mangi.eta.data.repository.UsageStatsSnapshot
import io.github.mangi.eta.data.repository.formatStatCount
import io.github.mangi.eta.data.repository.formatTokenCount
import io.github.mangi.eta.data.repository.heatmapAlpha
import io.github.mangi.eta.data.repository.heatmapQuartiles
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun UsageStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf(UsageStatsSnapshot(isLoading = true)) }

    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) {
            runCatching { UsageStatsRepository.load(context) }
                .getOrElse { UsageStatsSnapshot(isLoading = false) }
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.stats_page_title),
        onBack = onBack,
    ) {
        if (stats.isLoading) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator()
                }
            }
        } else {
            item(key = "heatmap") {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.stats_page_heatmap_title),
                            style = MiuixTheme.textStyles.headline1,
                        )
                        ChatHeatmap(conversationsPerDay = stats.conversationsPerDay)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.stats_page_heatmap_less),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                                HeatmapCell(alpha = alpha, sizeDp = 10)
                            }
                            Text(
                                text = stringResource(R.string.stats_page_heatmap_more),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            item(key = "grid") {
                StatsGrid(
                    stats = stats,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatHeatmap(conversationsPerDay: Map<LocalDate, Int>) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)
    val numWeeks = 53
    val (q1, q2, q3) = heatmapQuartiles(conversationsPerDay.values)
    val cellSize = 11.dp
    val cellSpacing = 2.dp
    val monthLabelHeight = 14.dp
    val dowLabels = listOf(
        "",
        stringResource(R.string.stats_page_dow_mon),
        "",
        stringResource(R.string.stats_page_dow_wed),
        "",
        stringResource(R.string.stats_page_dow_fri),
        "",
    )
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)
    val colors = MiuixTheme.colorScheme

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            modifier = Modifier.width(12.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            fontSize = 8.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            Text(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                fontSize = 9.sp,
                                color = colors.onSurfaceVariantSummary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacing)) {
                        for (dow in 0..6) {
                            val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                            val isFuture = date.isAfter(today)
                            val count = if (isFuture) 0 else (conversationsPerDay[date] ?: 0)
                            HeatmapCell(
                                alpha = heatmapAlpha(count, q1, q2, q3, isFuture),
                                sizeDp = cellSize.value.toInt(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(alpha: Float, sizeDp: Int) {
    val colors = MiuixTheme.colorScheme
    val color = when {
        alpha < 0f -> colors.surfaceVariant.copy(alpha = 0.3f)
        alpha == 0f -> colors.surfaceVariant
        else -> colors.primary.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

@Composable
private fun StatsGrid(stats: UsageStatsSnapshot, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Forum,
                label = stringResource(R.string.stats_page_total_conversations),
                value = formatStatCount(stats.totalConversations.toLong()),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ChatBubbleOutline,
                label = stringResource(R.string.stats_page_total_messages),
                value = formatStatCount(stats.totalMessages.toLong()),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Keyboard,
                label = stringResource(R.string.stats_page_input_tokens),
                value = formatTokenCount(stats.totalInputTokens),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Rounded.Notes,
                label = stringResource(R.string.stats_page_output_tokens),
                value = formatTokenCount(stats.totalOutputTokens),
            )
        }
        if (stats.totalCachedTokens > 0) {
            StatCard(
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Bolt,
                label = stringResource(R.string.stats_page_cached_tokens),
                value = formatTokenCount(stats.totalCachedTokens),
            )
        }
        StatCard(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            icon = Icons.Rounded.RocketLaunch,
            label = stringResource(R.string.stats_page_launch_count),
            value = formatStatCount(stats.launchCount.toLong()),
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = value,
                style = MiuixTheme.textStyles.headline1,
            )
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
