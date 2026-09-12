package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36])
class UsageStatsRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        runBlocking { SettingsDataStore.clearRetiredUsage() }
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun aggregatesConversationsMessagesAndTokens() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        val day = LocalDate.of(2026, 1, 15)
        val createdAt = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        dao.insertConversations(
            listOf(
                ConversationEntity(
                    id = "c1",
                    title = "one",
                    thinkingEnabled = false,
                    reasoningEffort = ReasoningEffort.DEFAULT.wireValue,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
                ConversationEntity(
                    id = "c2",
                    title = "two",
                    thinkingEnabled = false,
                    reasoningEffort = ReasoningEffort.DEFAULT.wireValue,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            ),
        )
        dao.insertMessages(
            listOf(
                ConversationMessageEntity(
                    id = "m1",
                    conversationId = "c1",
                    sortIndex = 0,
                    type = "user",
                    content = "hi",
                ),
                ConversationMessageEntity(
                    id = "m2",
                    conversationId = "c1",
                    sortIndex = 1,
                    type = "assistant",
                    content = "hello",
                    inputTokens = 10,
                    outputTokens = 20,
                    cachedTokens = 3,
                ),
                ConversationMessageEntity(
                    id = "m3",
                    conversationId = "c2",
                    sortIndex = 0,
                    type = "assistant",
                    content = "ok",
                    inputTokens = 5,
                    outputTokens = 7,
                ),
                ConversationMessageEntity(
                    id = "m4",
                    conversationId = "c1",
                    sortIndex = 2,
                    type = "thinking",
                    content = "thought",
                ),
                ConversationMessageEntity(
                    id = "m5",
                    conversationId = "c1",
                    sortIndex = 3,
                    type = "tool",
                    content = "tool",
                    toolName = "read_file",
                ),
                ConversationMessageEntity(
                    id = "m6",
                    conversationId = "c1",
                    sortIndex = 4,
                    type = "tool_summary",
                    content = "done",
                ),
            ),
        )

        val stats = UsageStatsRepository.load(context)
        assertEquals(2, stats.totalConversations)
        assertEquals(3, stats.totalMessages)
        assertEquals(15L, stats.totalInputTokens)
        assertEquals(27L, stats.totalOutputTokens)
        assertEquals(3L, stats.totalCachedTokens)
        assertEquals(2, stats.conversationsPerDay[day])
    }

    @Test
    fun statsInputUsesAssistantReportedTokens() {
        val totals = aggregateVisibleTokens(
            listOf(
                io.github.mangi.eta.data.db.UsageContentRow(
                    type = "user",
                ),
                io.github.mangi.eta.data.db.UsageContentRow(
                    type = "assistant",
                    inputTokens = 120,
                    outputTokens = 8,
                    cachedTokens = 40,
                ),
            ),
        )
        assertEquals(120L, totals.input)
        assertEquals(8L, totals.output)
        assertEquals(40L, totals.cached)
    }

    @Test
    fun statsIncludeCompactedPreservedTokensAndSkipLegacyResumeRound() {
        val totals = aggregateVisibleTokens(
            listOf(
                io.github.mangi.eta.data.db.UsageContentRow(
                    type = "context_compacted",
                    inputTokens = 3,
                    outputTokens = null,
                    cachedTokens = null,
                ),
                io.github.mangi.eta.data.db.UsageContentRow(
                    type = "context_compacted",
                    inputTokens = 1500,
                    outputTokens = 40,
                    cachedTokens = 200,
                ),
                io.github.mangi.eta.data.db.UsageContentRow(
                    type = "assistant",
                    inputTokens = 80,
                    outputTokens = 12,
                    cachedTokens = 10,
                ),
            ),
        )
        assertEquals(1580L, totals.input)
        assertEquals(52L, totals.output)
        assertEquals(210L, totals.cached)
    }

    @Test
    fun tokenStatsKeepRetiredTotalsAfterConversationsAreGone() = runBlocking {
        val day = LocalDate.now().minusDays(1)
        SettingsDataStore.addRetiredUsage(
            inputTokens = 1500,
            outputTokens = 40,
            cachedTokens = 200,
            conversations = 3,
            messages = 12,
            heatmap = mapOf(day to 3),
        )
        val stats = UsageStatsRepository.load(context)
        assertEquals(0L, stats.currentInputTokens)
        assertEquals(1500L, stats.lifetimeInputTokens)
        assertEquals(0L, stats.currentOutputTokens)
        assertEquals(40L, stats.lifetimeOutputTokens)
        assertEquals(0L, stats.currentCachedTokens)
        assertEquals(200L, stats.lifetimeCachedTokens)
        assertEquals(0, stats.currentConversations)
        assertEquals(3, stats.lifetimeConversations)
        assertEquals(0, stats.currentMessages)
        assertEquals(12, stats.lifetimeMessages)
        assertEquals(3, stats.conversationsPerDay[day])
    }

    @Test
    fun heatmapMergesLiveAndRetiredDays() {
        val start = LocalDate.of(2026, 1, 1)
        val liveDay = LocalDate.of(2026, 1, 10)
        val retiredDay = LocalDate.of(2026, 1, 11)
        val tooOld = LocalDate.of(2025, 12, 1)
        val merged = mergeHeatmap(
            live = mapOf(liveDay to 1, retiredDay to 2),
            retired = mapOf(retiredDay to 3, tooOld to 9),
            startDate = start,
        )
        assertEquals(1, merged[liveDay])
        assertEquals(5, merged[retiredDay])
        assertEquals(null, merged[tooOld])
    }

    @Test
    fun modelUsageGroupsByProviderAndCountsDistinctConversations() {
        val first = applyModelUsageDelta(
            raw = null,
            delta = ModelUsageDelta(
                providerId = "openai",
                providerName = "OPENAI",
                modelId = "grok-4.6",
                modelDisplayName = "grok-4.6",
                inputTokens = 10_400,
                outputTokens = 40,
                conversationId = "conv-1",
                day = LocalDate.of(2026, 9, 12),
            ),
        )
        val second = applyModelUsageDelta(
            raw = first,
            delta = ModelUsageDelta(
                providerId = "openai",
                providerName = "OPENAI",
                modelId = "grok-4.6",
                modelDisplayName = "grok-4.6",
                inputTokens = 200,
                outputTokens = 20,
                conversationId = "conv-1",
                day = LocalDate.of(2026, 9, 12),
            ),
        )
        val third = applyModelUsageDelta(
            raw = second,
            delta = ModelUsageDelta(
                providerId = "openai",
                providerName = "OPENAI",
                modelId = "grok-4.6",
                modelDisplayName = "grok-4.6",
                inputTokens = 100,
                outputTokens = 10,
                conversationId = "conv-2",
                day = LocalDate.of(2026, 9, 13),
            ),
        )
        val snapshot = decodeModelUsageSnapshot(third)
        val model = snapshot.providers.single().models.single()
        assertEquals("OPENAI", snapshot.providers.single().name)
        assertEquals(10_700L, model.inputTokens)
        assertEquals(70L, model.outputTokens)
        assertEquals(2, model.conversationCount)
        assertEquals(2, model.activeDays)
        assertEquals(5_350L, model.dailyAverageTokens)
        assertEquals(5_350L, model.conversationAverageTokens)
    }

    @Test
    fun modelUsageFiltersEventsByTimeRange() {
        val zone = java.time.ZoneId.systemDefault()
        val inside = java.time.LocalDateTime.of(2026, 8, 26, 13, 40)
            .atZone(zone).toInstant().toEpochMilli()
        val outside = java.time.LocalDateTime.of(2026, 8, 26, 14, 10)
            .atZone(zone).toInstant().toEpochMilli()
        val raw = applyModelUsageDelta(
            raw = applyModelUsageDelta(
                raw = null,
                delta = ModelUsageDelta(
                    providerId = "openai",
                    providerName = "OPENAI",
                    modelId = "grok-4.6",
                    modelDisplayName = "grok-4.6",
                    inputTokens = 10_400,
                    outputTokens = 40,
                    conversationId = "conv-1",
                    atMillis = inside,
                ),
            ),
            delta = ModelUsageDelta(
                providerId = "openai",
                providerName = "OPENAI",
                modelId = "grok-4.6",
                modelDisplayName = "grok-4.6",
                inputTokens = 800,
                outputTokens = 20,
                conversationId = "conv-2",
                atMillis = outside,
            ),
        )
        val start = java.time.LocalDateTime.of(2026, 8, 26, 13, 28)
            .atZone(zone).toInstant().toEpochMilli()
        val end = java.time.LocalDateTime.of(2026, 8, 26, 14, 0, 59, 999_000_000)
            .atZone(zone).toInstant().toEpochMilli()
        val filtered = decodeModelUsageSnapshot(raw).filtered(start, end)
        val model = filtered.providers.single().models.single()
        assertEquals(10_400L, model.inputTokens)
        assertEquals(40L, model.outputTokens)
        assertEquals(1, model.conversationCount)
        assertEquals(1, model.activeDays)
    }

    @Test
    fun heatmapQuartilesAndAlphaMatchRikkahubBuckets() {
        val (q1, q2, q3) = heatmapQuartiles(listOf(1, 2, 4, 8))
        assertEquals(0.25f, heatmapAlpha(1, q1, q2, q3, isFuture = false))
        assertEquals(0f, heatmapAlpha(0, q1, q2, q3, isFuture = false))
        assertEquals(-1f, heatmapAlpha(8, q1, q2, q3, isFuture = true))
        assertEquals("1.5K", formatStatCount(1500))
        assertEquals("1.50M", formatTokenCount(1_500_000))
    }
}
