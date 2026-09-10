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
    fun heatmapQuartilesAndAlphaMatchRikkahubBuckets() {
        val (q1, q2, q3) = heatmapQuartiles(listOf(1, 2, 4, 8))
        assertEquals(0.25f, heatmapAlpha(1, q1, q2, q3, isFuture = false))
        assertEquals(0f, heatmapAlpha(0, q1, q2, q3, isFuture = false))
        assertEquals(-1f, heatmapAlpha(8, q1, q2, q3, isFuture = true))
        assertEquals("1.5K", formatStatCount(1500))
        assertEquals("1.50M", formatTokenCount(1_500_000))
    }
}
