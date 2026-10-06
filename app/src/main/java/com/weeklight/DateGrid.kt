package com.weeklight

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

internal object DateGrid {
    fun dates(
        today: LocalDate,
        weeks: Int,
        page: Int,
        locale: Locale = Locale.getDefault(),
    ): List<LocalDate> {
        require(weeks in 1..8)
        val start = today
            .with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek))
            .plusWeeks(weeks.toLong() * page)
        return (0 until weeks * 7).map { start.plusDays(it.toLong()) }
    }

    fun weekdayLabels(locale: Locale = Locale.getDefault()): List<String> {
        val firstDay = WeekFields.of(locale).firstDayOfWeek
        return (0 until 7).map { offset ->
            firstDay.plus(offset.toLong())
                .getDisplayName(TextStyle.SHORT_STANDALONE, locale)
                .take(2)
        }
    }
}

