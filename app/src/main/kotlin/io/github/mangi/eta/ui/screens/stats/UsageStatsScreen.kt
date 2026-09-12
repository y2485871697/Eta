package io.github.mangi.eta.ui.screens.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import android.text.format.DateFormat
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.ModelUsageModelUi
import io.github.mangi.eta.data.repository.ModelUsageSnapshot
import io.github.mangi.eta.data.repository.UsageStatsRepository
import io.github.mangi.eta.data.repository.formatBalanceDisplay
import io.github.mangi.eta.data.repository.UsageStatsSnapshot
import io.github.mangi.eta.data.repository.formatStatCount
import io.github.mangi.eta.data.repository.formatTokenCount
import io.github.mangi.eta.data.repository.heatmapAlpha
import io.github.mangi.eta.data.repository.heatmapQuartiles
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.pages.providers.ProviderBalanceAmount
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private enum class UsageStatsTab { Overview, Models }

@Composable
internal fun UsageStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf(UsageStatsSnapshot(isLoading = true)) }
    var selectedTab by remember { mutableStateOf(UsageStatsTab.Overview) }

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
        item(key = "tabs") {
            UsageStatsTabs(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            )
        }
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
        } else if (selectedTab == UsageStatsTab.Models) {
            item(key = "model-usage") {
                ModelUsagePane(
                    usage = stats.modelUsage,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                )
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
private fun UsageStatsTabs(
    selected: UsageStatsTab,
    onSelect: (UsageStatsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UsageTabButton(
            label = stringResource(R.string.stats_tab_overview),
            selected = selected == UsageStatsTab.Overview,
            onClick = { onSelect(UsageStatsTab.Overview) },
        )
        UsageTabButton(
            label = stringResource(R.string.stats_tab_models),
            selected = selected == UsageStatsTab.Models,
            onClick = { onSelect(UsageStatsTab.Models) },
        )
    }
}

@Composable
private fun RowScope.UsageTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primary else colors.primaryContainer)
            .clickable {
                TouchHaptics.click(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = if (selected) colors.onPrimary else colors.onPrimaryContainer,
        )
    }
}

private data class UsageTimeBound(
    val date: LocalDate? = null,
    val hour: Int? = null,
    val minute: Int? = null,
) {
    val isSet: Boolean get() = date != null

    fun toMillis(endOfBound: Boolean): Long? {
        val selectedDate = date ?: return null
        val time = when {
            hour == null || minute == null -> if (endOfBound) LocalTime.of(23, 59, 59, 999_000_000) else LocalTime.MIN
            endOfBound -> LocalTime.of(hour, minute, 59, 999_000_000)
            else -> LocalTime.of(hour, minute)
        }
        return LocalDateTime.of(selectedDate, time)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}

@Composable
private fun ModelUsagePane(
    usage: ModelUsageSnapshot,
    modifier: Modifier = Modifier,
) {
    var expandedModels by remember { mutableStateOf(emptySet<String>()) }
    var startBound by remember { mutableStateOf(UsageTimeBound()) }
    var endBound by remember { mutableStateOf(UsageTimeBound()) }
    val filtered = remember(usage, startBound, endBound) {
        usage.filtered(startBound.toMillis(endOfBound = false), endBound.toMillis(endOfBound = true))
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModelUsageFilterCard(
            start = startBound,
            end = endBound,
            onStartChange = { startBound = it },
            onEndChange = { endBound = it },
            onClear = {
                startBound = UsageTimeBound()
                endBound = UsageTimeBound()
            },
        )
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.stats_model_total_title),
                    style = MiuixTheme.textStyles.headline1,
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_total_input),
                    value = formatTokenCount(filtered.totalInputTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokenCount(filtered.totalOutputTokens),
                )
            }
        }
        if (filtered.providers.isEmpty()) {
            Text(
                text = stringResource(
                    if (startBound.isSet || endBound.isSet) {
                        R.string.stats_model_filter_empty
                    } else {
                        R.string.stats_model_empty
                    },
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        } else {
            filtered.providers.forEach { provider ->
                Card {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = provider.name,
                                style = MiuixTheme.textStyles.title3,
                                modifier = Modifier.weight(1f),
                            )
                            provider.billedCredits?.let { credits ->
                                ProviderBalanceAmount(amount = formatUsageCharge(credits))
                            }
                        }
                        provider.models.forEach { model ->
                            val key = "${provider.id}/${model.id}"
                            val expanded = key in expandedModels
                            ModelUsageRow(
                                model = model,
                                expanded = expanded,
                                onToggle = {
                                    expandedModels = if (expanded) {
                                        expandedModels - key
                                    } else {
                                        expandedModels + key
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class UsageBoundSide { Start, End }

@Composable
private fun ModelUsageFilterCard(
    start: UsageTimeBound,
    end: UsageTimeBound,
    onStartChange: (UsageTimeBound) -> Unit,
    onEndChange: (UsageTimeBound) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val use24Hour = DateFormat.is24HourFormat(context)
    var dateSide by remember { mutableStateOf<UsageBoundSide?>(null) }
    var timeSide by remember { mutableStateOf<UsageBoundSide?>(null) }
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.stats_model_filter_title),
                style = MiuixTheme.textStyles.headline1,
            )
            UsageBoundRow(
                label = stringResource(R.string.stats_model_filter_start),
                bound = start,
                use24Hour = use24Hour,
                onPickDate = {
                    TouchHaptics.click(view)
                    dateSide = UsageBoundSide.Start
                },
                onPickTime = {
                    TouchHaptics.click(view)
                    timeSide = UsageBoundSide.Start
                },
            )
            UsageBoundRow(
                label = stringResource(R.string.stats_model_filter_end),
                bound = end,
                use24Hour = use24Hour,
                onPickDate = {
                    TouchHaptics.click(view)
                    dateSide = UsageBoundSide.End
                },
                onPickTime = {
                    TouchHaptics.click(view)
                    timeSide = UsageBoundSide.End
                },
            )
            if (start.isSet || end.isSet) {
                Text(
                    text = stringResource(R.string.stats_model_filter_clear),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            TouchHaptics.click(view)
                            onClear()
                        }
                        .padding(top = 2.dp),
                )
            }
        }
    }
    UsageDatePickerDialog(
        show = dateSide != null,
        title = stringResource(
            if (dateSide == UsageBoundSide.End) {
                R.string.stats_model_filter_end
            } else {
                R.string.stats_model_filter_start
            },
        ),
        current = if (dateSide == UsageBoundSide.End) end else start,
        onDismiss = { dateSide = null },
        onConfirm = { picked ->
            if (dateSide == UsageBoundSide.End) onEndChange(picked) else onStartChange(picked)
            dateSide = null
        },
    )
    UsageTimePickerDialog(
        show = timeSide != null,
        title = stringResource(
            if (timeSide == UsageBoundSide.End) {
                R.string.stats_model_filter_end
            } else {
                R.string.stats_model_filter_start
            },
        ),
        current = if (timeSide == UsageBoundSide.End) end else start,
        use24Hour = use24Hour,
        onDismiss = { timeSide = null },
        onConfirm = { picked ->
            if (timeSide == UsageBoundSide.End) onEndChange(picked) else onStartChange(picked)
            timeSide = null
        },
    )
}

@Composable
private fun UsageBoundRow(
    label: String,
    bound: UsageTimeBound,
    use24Hour: Boolean,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            UsageBoundChip(
                text = bound.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    ?: stringResource(R.string.stats_model_filter_date_placeholder),
                modifier = Modifier.weight(1f),
                onClick = onPickDate,
            )
            UsageBoundChip(
                text = formatUsageTime(bound, use24Hour)
                    ?: stringResource(R.string.stats_model_filter_time_placeholder),
                modifier = Modifier.weight(1f),
                onClick = onPickTime,
            )
        }
    }
}

@Composable
private fun UsageBoundChip(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun formatUsageTime(bound: UsageTimeBound, use24Hour: Boolean): String? {
    val hour = bound.hour ?: return null
    val minute = bound.minute ?: return null
    val pattern = if (use24Hour) "HH:mm" else "h:mm a"
    return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageDatePickerDialog(
    show: Boolean,
    title: String,
    current: UsageTimeBound,
    onDismiss: () -> Unit,
    onConfirm: (UsageTimeBound) -> Unit,
) {
    if (!show) return
    val initial = current.date ?: LocalDate.now()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val colors = MiuixTheme.colorScheme
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DatePicker(
                state = state,
                title = null,
                headline = null,
                showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = colors.surfaceContainer,
                    selectedDayContainerColor = colors.primary,
                    selectedDayContentColor = colors.onPrimary,
                    selectedYearContainerColor = colors.primary,
                    selectedYearContentColor = colors.onPrimary,
                    todayDateBorderColor = colors.primary,
                    todayContentColor = colors.primary,
                    dayContentColor = colors.onSurface,
                    weekdayContentColor = colors.onSurfaceVariantSummary,
                    navigationContentColor = colors.onSurface,
                    yearContentColor = colors.onSurface,
                    currentYearContentColor = colors.primary,
                    disabledDayContentColor = colors.onSurfaceVariantSummary.copy(alpha = 0.38f),
                    dividerColor = colors.outline.copy(alpha = 0.35f),
                ),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_confirm),
                onCancel = onDismiss,
                onConfirm = {
                    val millis = state.selectedDateMillis ?: return@MiuixDialogActions
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    onConfirm(current.copy(date = date))
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageTimePickerDialog(
    show: Boolean,
    title: String,
    current: UsageTimeBound,
    use24Hour: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (UsageTimeBound) -> Unit,
) {
    if (!show) return
    val now = LocalTime.now()
    val state = rememberTimePickerState(
        initialHour = current.hour ?: now.hour,
        initialMinute = current.minute ?: now.minute,
        is24Hour = use24Hour,
    )
    val colors = MiuixTheme.colorScheme
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = colors.primaryContainer,
                    clockDialSelectedContentColor = colors.onPrimary,
                    clockDialUnselectedContentColor = colors.onPrimaryContainer,
                    selectorColor = colors.primary,
                    containerColor = colors.surfaceContainer,
                    periodSelectorBorderColor = colors.outline,
                    periodSelectorSelectedContainerColor = colors.primaryContainer,
                    periodSelectorUnselectedContainerColor = colors.surfaceContainer,
                    periodSelectorSelectedContentColor = colors.onPrimaryContainer,
                    periodSelectorUnselectedContentColor = colors.onSurfaceVariantSummary,
                    timeSelectorSelectedContainerColor = colors.primaryContainer,
                    timeSelectorUnselectedContainerColor = colors.surfaceContainer,
                    timeSelectorSelectedContentColor = colors.onPrimaryContainer,
                    timeSelectorUnselectedContentColor = colors.onSurface,
                ),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_confirm),
                onCancel = onDismiss,
                onConfirm = {
                    onConfirm(
                        current.copy(
                            date = current.date ?: LocalDate.now(),
                            hour = state.hour,
                            minute = state.minute,
                        ),
                    )
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ModelUsageRow(
    model: ModelUsageModelUi,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val view = LocalView.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    TouchHaptics.click(view)
                    onToggle()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = model.displayName,
                style = MiuixTheme.textStyles.body1,
                modifier = Modifier.weight(1f),
            )
            model.billedCredits?.let { credits ->
                ProviderBalanceAmount(amount = formatUsageCharge(credits))
            }
            androidx.compose.material3.Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_input_tokens),
                    value = formatTokenCount(model.inputTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokenCount(model.outputTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_daily_avg),
                    value = formatTokenCount(model.dailyAverageTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_conversation_avg),
                    value = formatTokenCount(model.conversationAverageTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_conversations),
                    value = formatStatCount(model.conversationCount.toLong()),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_active_days),
                    value = formatStatCount(model.activeDays.toLong()),
                )
            }
        }
    }
}

@Composable
private fun ModelMetricRow(label: String, value: String) {
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
                value = formatCurrentLifetime(
                    current = formatStatCount(stats.currentConversations.toLong()),
                    lifetime = formatStatCount(stats.lifetimeConversations.toLong()),
                ),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ChatBubbleOutline,
                label = stringResource(R.string.stats_page_total_messages),
                value = formatCurrentLifetime(
                    current = formatStatCount(stats.currentMessages.toLong()),
                    lifetime = formatStatCount(stats.lifetimeMessages.toLong()),
                ),
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
                value = formatCurrentLifetime(
                    current = formatTokenCount(stats.currentInputTokens),
                    lifetime = formatTokenCount(stats.lifetimeInputTokens),
                ),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Rounded.Notes,
                label = stringResource(R.string.stats_page_output_tokens),
                value = formatCurrentLifetime(
                    current = formatTokenCount(stats.currentOutputTokens),
                    lifetime = formatTokenCount(stats.lifetimeOutputTokens),
                ),
            )
        }
        if (stats.lifetimeCachedTokens > 0) {
            StatCard(
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Bolt,
                label = stringResource(R.string.stats_page_cached_tokens),
                value = formatCurrentLifetime(
                    current = formatTokenCount(stats.currentCachedTokens),
                    lifetime = formatTokenCount(stats.lifetimeCachedTokens),
                ),
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

private fun formatCurrentLifetime(current: String, lifetime: String): String =
    if (current == lifetime) current else "$current / $lifetime"

private fun formatUsageCharge(credits: Double): String {
    val magnitude = formatBalanceDisplay(credits.toString())
    return if (credits > 0) "-$magnitude" else magnitude
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
