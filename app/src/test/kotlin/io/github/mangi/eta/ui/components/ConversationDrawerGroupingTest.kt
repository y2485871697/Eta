package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.app.ConversationTimeLabels
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import java.time.LocalDateTime
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDrawerGroupingTest {
    private val timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val beforeMidnight = millis(10, 23, 59, 59)
    private val afterMidnight = millis(11, 0, 0, 1)

    @Test
    fun mergesNonAdjacentSectionsInFirstOccurrenceOrder() {
        val rows = listOf(
            summary("y1", "昨天"),
            summary("older", "周五"),
            summary("y2", "昨天"),
            summary("p1", "昨天", isPinned = true),
            summary("t1", "09:00", isToday = true),
            summary("p2", "周五", isPinned = true, isToday = true),
            summary("t2", "10:00", isToday = true),
        )

        val groups = rows.groupForDrawer()

        assertEquals(
            listOf(
                ConversationDrawerSection.Dated("昨天"),
                ConversationDrawerSection.Dated("周五"),
                ConversationDrawerSection.Pinned,
                ConversationDrawerSection.Today,
            ),
            groups.map { it.section },
        )
        assertEquals(
            listOf(listOf("y1", "y2"), listOf("older"), listOf("p1", "p2"), listOf("t1", "t2")),
            groups.map { group -> group.items.map { it.id } },
        )
        assertUniqueKeys(groups)
    }

    @Test
    fun staleSnapshotKeepsTodayClassificationUntilItsLabelIsRefreshed() {
        val timestamps = listOf(millis(10, 9), millis(9, 20))
        val staleRows = timestamps.mapIndexed { index, timestamp ->
            snapshot("c$index", timestamp, beforeMidnight)
        }
        // Rendering this snapshot after midnight must not reclassify its clock label
        // with a live Calendar. Classification and label travel together in the summary.
        assertTrue(staleRows.first().isToday)
        assertEquals("09:00", staleRows.first().timeLabel)
        assertFalse(ConversationTimeLabels.isToday(timestamps.first(), afterMidnight, timeZone))
        val staleGroups = staleRows.groupForDrawer()
        assertEquals(
            listOf(ConversationDrawerSection.Today, ConversationDrawerSection.Dated("昨天")),
            staleGroups.map { it.section },
        )
        assertUniqueKeys(staleGroups)

        val refreshedRows = timestamps.mapIndexed { index, timestamp ->
            snapshot("c$index", timestamp, afterMidnight)
        }
        assertFalse(refreshedRows.first().isToday)
        assertEquals("昨天", refreshedRows.first().timeLabel)
        val refreshedGroups = refreshedRows.groupForDrawer()
        assertEquals(
            listOf(ConversationDrawerSection.Dated("昨天"), ConversationDrawerSection.Dated("周五")),
            refreshedGroups.map { it.section },
        )
        assertUniqueKeys(refreshedGroups)
    }

    @Test
    fun mixedStaleLabelsAcrossMidnightProduceOnlyOneYesterdayHeader() {
        // Descending createdAt order with one stale streaming summary: Y / Friday / Y / Friday.
        val groups = listOf(
            snapshot("fresh-yesterday", millis(10, 12), afterMidnight),
            snapshot("fresh-older", millis(9, 22), afterMidnight),
            snapshot("stale-yesterday", millis(9, 20), beforeMidnight),
            snapshot("fresh-oldest", millis(9, 8), afterMidnight),
        ).groupForDrawer()

        assertEquals(2, groups.size)
        val yesterday = groups.single { it.section == ConversationDrawerSection.Dated("昨天") }
        assertEquals(listOf("fresh-yesterday", "stale-yesterday"), yesterday.items.map { it.id })
        assertEquals(ConversationDrawerSection.Dated("周五"), groups[1].section)
        assertUniqueKeys(groups)
    }

    @Test
    fun updatedAtDoesNotOverrideTheCreatedAtClassification() {
        val createdEarlier = snapshot("created-earlier", millis(9, 8), afterMidnight)
            .copy(updatedAtMillis = afterMidnight)
        val createdToday = snapshot("created-today", millis(11, 0), afterMidnight)
            .copy(updatedAtMillis = millis(9, 8))
        val groups = listOf(createdEarlier, createdToday).groupForDrawer()

        assertEquals(
            listOf(ConversationDrawerSection.Dated("周五"), ConversationDrawerSection.Today),
            groups.map { it.section },
        )
        assertUniqueKeys(groups)
    }

    @Test
    fun emptyListHasNoSections() {
        assertTrue(emptyList<ConversationSummaryUi>().groupForDrawer().isEmpty())
    }

    private fun snapshot(id: String, timestamp: Long, now: Long): ConversationSummaryUi = summary(
        id = id,
        label = ConversationTimeLabels.label(
            timestampMillis = timestamp,
            nowMillis = now,
            locale = Locale.CHINA,
            timeZone = timeZone,
            yesterdayLabel = "昨天",
            recentLabel = "最近",
        ),
        isToday = ConversationTimeLabels.isToday(timestamp, now, timeZone),
    ).copy(createdAtMillis = timestamp, updatedAtMillis = timestamp)

    private fun summary(
        id: String,
        label: String,
        isPinned: Boolean = false,
        isToday: Boolean = false,
    ) = ConversationSummaryUi(
        id = id,
        title = id,
        preview = "",
        timeLabel = label,
        mode = ConversationModeUi.Chat,
        isPinned = isPinned,
        isToday = isToday,
    )

    private fun assertUniqueKeys(groups: List<ConversationDrawerGroup>) {
        val sectionKeys = groups.map { it.key }
        assertEquals(sectionKeys.size, sectionKeys.toSet().size)
        val allKeys = groups.flatMap { group -> listOf(group.key) + group.items.map { it.id } }
        assertEquals(allKeys.size, allKeys.toSet().size)
    }

    private fun millis(day: Int, hour: Int, minute: Int = 0, second: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute, second)
            .atZone(timeZone.toZoneId()).toInstant().toEpochMilli()
}
