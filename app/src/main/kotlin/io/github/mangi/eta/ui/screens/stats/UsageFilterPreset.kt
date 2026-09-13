package io.github.mangi.eta.ui.screens.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal data class UsageTimeBound(
    val date: LocalDate? = null,
    val hour: Int? = null,
    val minute: Int? = null,
) {
    val isSet: Boolean get() = date != null

    fun toMillis(endOfBound: Boolean): Long? {
        val selectedDate = date ?: return null
        val time = when {
            hour == null || minute == null -> {
                if (endOfBound) LocalTime.of(23, 59, 59, 999_000_000) else LocalTime.MIN
            }
            endOfBound -> LocalTime.of(hour, minute, 59, 999_000_000)
            else -> LocalTime.of(hour, minute)
        }
        return LocalDateTime.of(selectedDate, time)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    fun toDateTime(endOfBound: Boolean, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val selectedDate = date ?: now.toLocalDate()
        val time = when {
            hour != null && minute != null -> LocalTime.of(hour, minute)
            endOfBound -> LocalTime.of(23, 59)
            else -> LocalTime.MIN
        }
        return LocalDateTime.of(selectedDate, time)
    }

    companion object {
        fun from(dateTime: LocalDateTime): UsageTimeBound =
            UsageTimeBound(
                date = dateTime.toLocalDate(),
                hour = dateTime.hour,
                minute = dateTime.minute,
            )
    }
}

internal enum class UsageFilterPreset {
    Today,
    Last7Days,
    ThisWeek,
    Last30Days,
    ThisMonth,
}

internal fun usageFilterPresetRange(
    preset: UsageFilterPreset,
    today: LocalDate,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
): Pair<LocalDateTime, LocalDateTime> {
    val end = today.atTime(23, 59)
    val startDate = when (preset) {
        UsageFilterPreset.Today -> today
        UsageFilterPreset.Last7Days -> today.minusDays(6)
        UsageFilterPreset.ThisWeek -> today.with(TemporalAdjusters.previousOrSame(weekStart))
        UsageFilterPreset.Last30Days -> today.minusDays(29)
        UsageFilterPreset.ThisMonth -> today.withDayOfMonth(1)
    }
    return startDate.atTime(0, 0) to end
}

internal fun matchingUsageFilterPreset(
    start: UsageTimeBound,
    end: UsageTimeBound,
    today: LocalDate,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
): UsageFilterPreset? {
    if (!start.isSet || !end.isSet) return null
    return UsageFilterPreset.entries.firstOrNull { preset ->
        val (expectedStart, expectedEnd) = usageFilterPresetRange(preset, today, weekStart)
        start == UsageTimeBound.from(expectedStart) && end == UsageTimeBound.from(expectedEnd)
    }
}
