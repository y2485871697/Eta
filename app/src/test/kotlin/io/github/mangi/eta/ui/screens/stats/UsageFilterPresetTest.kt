package io.github.mangi.eta.ui.screens.stats

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageFilterPresetTest {
    private val wednesday = LocalDate.of(2026, 9, 9)

    @Test
    fun todayCoversTheCurrentCalendarDay() {
        val (start, end) = usageFilterPresetRange(UsageFilterPreset.Today, wednesday)
        assertEquals(LocalDateTime.of(2026, 9, 9, 0, 0), start)
        assertEquals(LocalDateTime.of(2026, 9, 9, 23, 59), end)
    }

    @Test
    fun last7DaysIncludesToday() {
        val (start, end) = usageFilterPresetRange(UsageFilterPreset.Last7Days, wednesday)
        assertEquals(LocalDateTime.of(2026, 9, 3, 0, 0), start)
        assertEquals(LocalDateTime.of(2026, 9, 9, 23, 59), end)
    }

    @Test
    fun thisWeekStartsOnMonday() {
        val (start, end) = usageFilterPresetRange(UsageFilterPreset.ThisWeek, wednesday)
        assertEquals(LocalDateTime.of(2026, 9, 7, 0, 0), start)
        assertEquals(LocalDateTime.of(2026, 9, 9, 23, 59), end)
    }

    @Test
    fun thisMonthStartsOnTheFirst() {
        val (start, end) = usageFilterPresetRange(UsageFilterPreset.ThisMonth, wednesday)
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), start)
        assertEquals(LocalDateTime.of(2026, 9, 9, 23, 59), end)
    }

    @Test
    fun matchingPresetRequiresExactStartAndEnd() {
        val (start, end) = usageFilterPresetRange(UsageFilterPreset.Last30Days, wednesday)
        assertEquals(
            UsageFilterPreset.Last30Days,
            matchingUsageFilterPreset(UsageTimeBound.from(start), UsageTimeBound.from(end), wednesday),
        )
        assertNull(
            matchingUsageFilterPreset(UsageTimeBound.from(start), UsageTimeBound(), wednesday),
        )
    }
}
