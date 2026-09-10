package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class UsageStatsSnapshot(
    val isLoading: Boolean = false,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
)

internal object UsageStatsRepository {
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
        return UsageStatsSnapshot(
            totalConversations = dao.conversationCount(),
            totalMessages = dao.totalMessageCount(),
            totalInputTokens = dao.totalInputTokens(),
            totalOutputTokens = dao.totalOutputTokens(),
            totalCachedTokens = dao.totalCachedTokens(),
            conversationsPerDay = perDay,
            launchCount = SettingsDataStore.launchCount(),
        )
    }
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
