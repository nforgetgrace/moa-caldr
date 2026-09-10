package com.moa.calendar.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class WidgetMonthTest {
    private val today = LocalDate.of(2026, 9, 10)
    private val september = YearMonth.of(2026, 9)

    @Test fun noSelectionShowsCurrentMonth() {
        assertEquals(september, resolveWidgetMonth(null, today))
    }

    @Test fun previousAndNextMoveOneMonthFromCurrent() {
        val previous = shiftWidgetMonth(null, today, -1)
        assertEquals(YearMonth.of(2026, 8), previous.month)
        assertEquals(today, previous.anchorDay)
        assertEquals(YearMonth.of(2026, 7), shiftWidgetMonth(previous, today, -1).month)
        assertEquals(YearMonth.of(2026, 10), shiftWidgetMonth(null, today, 1).month)
    }

    @Test fun selectionExpiresWhenTheDateChanges() {
        val selection = WidgetMonthSelection(YearMonth.of(2026, 8), today)
        assertEquals(YearMonth.of(2026, 8), resolveWidgetMonth(selection, today))
        assertEquals(september, resolveWidgetMonth(selection, today.plusDays(1)))
        assertEquals(YearMonth.of(2026, 10), resolveWidgetMonth(WidgetMonthSelection(YearMonth.of(2026, 8), LocalDate.of(2026, 9, 9)), LocalDate.of(2026, 10, 1)))
    }

    @Test fun shiftAfterMidnightStartsFromTheNewCurrentMonth() {
        val stale = WidgetMonthSelection(YearMonth.of(2026, 6), LocalDate.of(2026, 9, 9))
        assertEquals(YearMonth.of(2026, 8), shiftWidgetMonth(stale, today, -1).month)
    }

    @Test fun navigationIsLimitedToTwelveMonthsEachWay() {
        var selection: WidgetMonthSelection? = null
        repeat(20) { selection = shiftWidgetMonth(selection, today, -1) }
        assertEquals(YearMonth.of(2025, 9), selection!!.month)
        selection = null
        repeat(20) { selection = shiftWidgetMonth(selection, today, 1) }
        assertEquals(YearMonth.of(2027, 9), selection!!.month)
        assertEquals(YearMonth.of(2025, 9), resolveWidgetMonth(WidgetMonthSelection(YearMonth.of(2020, 1), today), today))
    }

    @Test fun titleNeverShowsTheYear() {
        assertEquals("9월", widgetMonthTitle(september))
        assertEquals("8월", widgetMonthTitle(YearMonth.of(2026, 8)))
        assertEquals("1월", widgetMonthTitle(YearMonth.of(2027, 1)))
        assertEquals("12월", widgetMonthTitle(YearMonth.of(2025, 12)))
    }

    @Test fun gridRangeCoversLeadingAndTrailingDays() {
        val (start, end) = widgetMonthRange(september)
        assertEquals(LocalDate.of(2026, 8, 30), start)
        assertEquals(LocalDate.of(2026, 10, 11), end)
        val (februaryStart, februaryEnd) = widgetMonthRange(YearMonth.of(2026, 2))
        assertEquals(LocalDate.of(2026, 2, 1), februaryStart)
        assertEquals(LocalDate.of(2026, 3, 15), februaryEnd)
    }

    @Test fun storedFormRoundTrips() {
        val selection = WidgetMonthSelection(YearMonth.of(2026, 8), today)
        assertEquals(selection, parseWidgetMonthSelection(selection.encode()))
        assertNull(parseWidgetMonthSelection(null))
        assertNull(parseWidgetMonthSelection("garbage"))
        assertNull(parseWidgetMonthSelection("2026-08|not-a-date"))
    }
}
