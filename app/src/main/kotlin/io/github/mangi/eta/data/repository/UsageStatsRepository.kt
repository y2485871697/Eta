package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.UsageContentRow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UsageStatsSnapshot(
    val isLoading: Boolean = false,
    val currentConversations: Int = 0,
    val lifetimeConversations: Int = 0,
    val currentMessages: Int = 0,
    val lifetimeMessages: Int = 0,
    val currentInputTokens: Long = 0L,
    val lifetimeInputTokens: Long = 0L,
    val currentOutputTokens: Long = 0L,
    val lifetimeOutputTokens: Long = 0L,
    val currentCachedTokens: Long = 0L,
    val lifetimeCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
    val modelUsage: ModelUsageSnapshot = ModelUsageSnapshot(),
) {
    val totalConversations: Int get() = currentConversations
    val totalMessages: Int get() = currentMessages
    val totalInputTokens: Long get() = lifetimeInputTokens
    val totalOutputTokens: Long get() = lifetimeOutputTokens
    val totalCachedTokens: Long get() = lifetimeCachedTokens
}

internal object UsageStatsRepository {
    private val modelUsageLock = Mutex()

    suspend fun load(context: Context): UsageStatsSnapshot {
        val dao = EtaDatabase.get(context.applicationContext).conversationDao()
        val today = LocalDate.now()
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
        val startAt = startDate
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val perDay = dao.conversationCountPerDay(startAt)
            .mapNotNull { entry ->
                runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
            }
            .toMap()
        val tokenTotals = aggregateVisibleTokens(dao.usageContentRows())
        val retired = SettingsDataStore.retiredUsage()
        val liveConversations = dao.conversationCount()
        val liveMessages = dao.totalMessageCount()
        return UsageStatsSnapshot(
            currentConversations = liveConversations,
            lifetimeConversations = liveConversations + retired.conversations,
            currentMessages = liveMessages,
            lifetimeMessages = liveMessages + retired.messages,
            currentInputTokens = tokenTotals.input,
            lifetimeInputTokens = tokenTotals.input + retired.inputTokens,
            currentOutputTokens = tokenTotals.output,
            lifetimeOutputTokens = tokenTotals.output + retired.outputTokens,
            currentCachedTokens = tokenTotals.cached,
            lifetimeCachedTokens = tokenTotals.cached + retired.cachedTokens,
            conversationsPerDay = mergeHeatmap(perDay, retired.heatmap, startDate),
            launchCount = SettingsDataStore.launchCount(),
            modelUsage = decodeModelUsageSnapshot(SettingsDataStore.modelUsageJson())
                .withBalanceConversions(
                    ProviderRepository.allProviders().associate { provider ->
                        provider.id to tokenBalanceConversion(provider.balanceOption)
                    }.mapNotNull { (id, conversion) ->
                        conversion?.let { id to it }
                    }.toMap(),
                ),
        )
    }

    suspend fun recordModelUsage(delta: ModelUsageDelta) {
        modelUsageLock.withLock {
            val current = SettingsDataStore.modelUsageJson()
            SettingsDataStore.addModelUsage(applyModelUsageDelta(current, delta))
        }
    }
}


internal fun aggregateVisibleTokens(rows: List<UsageContentRow>): TokenTotals {
    var input = 0L
    var output = 0L
    var cached = 0L
    for (row in rows) {
        when (row.type) {
            "assistant" -> {
                input += (row.inputTokens ?: 0).toLong()
                output += (row.outputTokens ?: 0).toLong()
                cached += (row.cachedTokens ?: 0).toLong()
            }
            // 旧压缩行曾把 resumeRound 写进 input_tokens，且 output_tokens 为空，不能当用量。
            "context_compacted" -> {
                if (row.outputTokens == null) continue
                input += (row.inputTokens ?: 0).toLong()
                output += row.outputTokens.toLong()
                cached += (row.cachedTokens ?: 0).toLong()
            }
        }
    }
    return TokenTotals(input = input, output = output, cached = cached)
}

internal data class TokenTotals(
    val input: Long,
    val output: Long,
    val cached: Long,
)


internal fun mergeHeatmap(
    live: Map<LocalDate, Int>,
    retired: Map<LocalDate, Int>,
    startDate: LocalDate,
): Map<LocalDate, Int> {
    if (retired.isEmpty()) return live
    val merged = live.toMutableMap()
    retired.forEach { (day, count) ->
        if (count > 0 && !day.isBefore(startDate)) {
            merged[day] = (merged[day] ?: 0) + count
        }
    }
    return merged
}

internal fun heatmapAlpha(count: Int, q1: Int, q2: Int, q3: Int, isFuture: Boolean): Float = when {
    isFuture -> -1f
    count <= 0 -> 0f
    count <= q1 -> 0.25f
    count <= q2 -> 0.5f
    count <= q3 -> 0.75f
    else -> 1f
}

internal fun heatmapQuartiles(counts: Collection<Int>): Triple<Int, Int, Int> {
    val active = counts.filter { it > 0 }.sorted()
    if (active.isEmpty()) return Triple(1, 2, 3)
    fun at(fraction: Double, fallback: Int): Int =
        active.getOrElse((active.size * fraction).toInt().coerceAtMost(active.lastIndex)) { fallback }
    return Triple(at(0.25, 1), at(0.50, 2), at(0.75, 3))
}

internal fun formatStatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(Locale.US, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(Locale.US, count / 1_000.0)
    else -> count.toString()
}

internal fun formatTokenCount(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(Locale.US, count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(Locale.US, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(Locale.US, count / 1_000.0)
    else -> count.toString()
}
