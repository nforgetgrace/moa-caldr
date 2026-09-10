package com.moa.calendar.data

import java.time.LocalDate
import java.time.YearMonth

/** How far a home month widget may move away from the current month in either direction. */
const val WIDGET_MONTH_LIMIT = 12L

/** A month chosen inside a widget. It only counts on the calendar day it was chosen, so widgets return to today after midnight. */
data class WidgetMonthSelection(val month: YearMonth, val anchorDay: LocalDate) {
    fun encode(): String = "$month|$anchorDay"
}

fun parseWidgetMonthSelection(value: String?): WidgetMonthSelection? {
    val parts = value?.split('|') ?: return null
    if (parts.size != 2) return null
    return try { WidgetMonthSelection(YearMonth.parse(parts[0]), LocalDate.parse(parts[1])) } catch (_: Exception) { null }
}

fun resolveWidgetMonth(selection: WidgetMonthSelection?, today: LocalDate): YearMonth {
    val current = YearMonth.from(today)
    if (selection == null || selection.anchorDay != today) return current
    return clampWidgetMonth(selection.month, current)
}

fun shiftWidgetMonth(selection: WidgetMonthSelection?, today: LocalDate, deltaMonths: Long): WidgetMonthSelection =
    WidgetMonthSelection(clampWidgetMonth(resolveWidgetMonth(selection, today).plusMonths(deltaMonths), YearMonth.from(today)), today)

private fun clampWidgetMonth(month: YearMonth, current: YearMonth): YearMonth {
    val earliest = current.minusMonths(WIDGET_MONTH_LIMIT)
    val latest = current.plusMonths(WIDGET_MONTH_LIMIT)
    return when {
        month < earliest -> earliest
        month > latest -> latest
        else -> month
    }
}

/** Widget titles show only the month name in every year; the arrows and reset action carry the navigation context. */
fun widgetMonthTitle(month: YearMonth): String = "${month.monthValue}월"

/** Dates covered by a Sunday-first six-week widget grid: inclusive start, exclusive end. */
fun widgetMonthRange(month: YearMonth): Pair<LocalDate, LocalDate> {
    val first = month.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
    return start to start.plusWeeks(6)
}
