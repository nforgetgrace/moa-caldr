package com.moa.calendar.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarWeekLayoutTest {
    private val sunday = LocalDate.of(2026, 9, 6)
    private fun event(id: String, start: String, end: String, allDay: Boolean = true, zone: ZoneId = ZoneOffset.UTC) = CalendarEvent(
        id, "calendar", id, java.time.LocalDateTime.parse(start).atZone(zone).toInstant().toEpochMilli(),
        java.time.LocalDateTime.parse(end).atZone(zone).toInstant().toEpochMilli(), allDay,
        source = CalendarSource.NAVER, color = 0xFFB39DDB.toInt())
    private fun layout(events: List<CalendarEvent>, lines: Int? = null) = calendarWeekLayout(events, sunday, lines, ZoneOffset.UTC)
    private fun daily(count: Int) = (1..count).map { event("event$it", "2026-09-11T00:00", "2026-09-12T00:00") }

    @Test fun september11Through12IsOneFridaySaturdayBar() {
        val item = event("trip", "2026-09-11T00:00", "2026-09-13T00:00")
        val result = layout(listOf(item))
        assertEquals(1, result.segments.size)
        assertEquals(5, result.segments.single().startColumn)
        assertEquals(6, result.segments.single().endColumn)
        assertEquals(listOf(0,0,0,0,0,1,1), result.eventCounts)
        assertEquals("trip", result.segments.single().label())
    }
    @Test fun exclusiveTimedMidnightDoesNotOccupyNextDate() {
        val item = event("late", "2026-09-11T22:00", "2026-09-12T00:00", false)
        val bar = layout(listOf(item)).segments.single()
        assertEquals(bar.startColumn, bar.endColumn)
        assertEquals(5, bar.endColumn)
    }
    @Test fun overnightTimedEventSpansBothDates() {
        val item = event("night", "2026-09-11T22:00", "2026-09-12T01:00", false)
        assertEquals(6, layout(listOf(item)).segments.single().endColumn)
    }
    @Test fun weekBoundaryClipsAndMarksBothSegments() {
        val item = event("trip", "2026-09-12T00:00", "2026-09-15T00:00")
        val first = layout(listOf(item)).segments.single()
        val next = calendarWeekLayout(listOf(item), sunday.plusWeeks(1)).segments.single()
        assertFalse(first.continuesBefore); assertTrue(first.continuesAfter)
        assertEquals(6, first.startColumn); assertEquals(6, first.endColumn)
        assertTrue(next.continuesBefore); assertFalse(next.continuesAfter)
        assertEquals(0, next.startColumn); assertEquals(1, next.endColumn)
        assertEquals("trip ›", first.label()); assertEquals("‹ trip", next.label())
    }
    @Test fun monthBoundaryDoesNotSplitAWeekBar() {
        val item = event("month", "2026-09-30T00:00", "2026-10-03T00:00")
        val result = calendarWeekLayout(listOf(item), LocalDate.of(2026,9,27))
        assertEquals(3, result.segments.single().startColumn)
        assertEquals(5, result.segments.single().endColumn)
    }
    @Test fun overlappingBarsNeverShareACellAndNonoverlapReusesLane() {
        val items = listOf(event("a", "2026-09-07T00:00", "2026-09-10T00:00"),
            event("b", "2026-09-08T00:00", "2026-09-12T00:00"),
            event("c", "2026-09-11T00:00", "2026-09-13T00:00"))
        val result = layout(items)
        assertEquals(2, result.rowCount)
        for (day in 0..6) {
            val shown = result.segments.filter { day in it.startColumn..it.endColumn }
            assertEquals(shown.size, shown.map { it.row }.distinct().size)
        }
        assertEquals(result.segments.first { it.event.id == "a" }.row, result.segments.first { it.event.id == "c" }.row)
    }
    @Test fun appShowsEveryEventWithoutOverflow() {
        val result = layout(daily(12))
        assertEquals(12, result.segments.size); assertEquals(12, result.rowCount)
        assertTrue(result.hiddenCounts.all { it == 0 })
    }
    @Test fun widgetShowsThreeTitlesAndCorrectCount() {
        val result = layout(daily(5), 4)
        assertEquals(3, result.segments.size); assertEquals(3, result.rowCount)
        assertEquals(2, result.hiddenCounts[5])
    }
    @Test fun smallWidgetReservesOverflowSpace() {
        for (lines in 0..4) {
            val result = layout(daily(8), lines)
            assertTrue(result.rowCount <= 3)
            if (lines > 0) assertTrue(result.rowCount + 1 <= lines)
            assertEquals(8, result.segments.size + result.hiddenCounts[5])
        }
    }
    @Test fun visibleMultiDayBarIsNeverFragmentedByOverflow() {
        val span = event("span", "2026-09-10T00:00", "2026-09-13T00:00")
        val result = layout(daily(5) + span, 4)
        val bar = result.segments.single { it.event.id == "span" }
        assertEquals(4, bar.startColumn); assertEquals(6, bar.endColumn)
        assertEquals(listOf(0,0,0,0,0,3,0), result.hiddenCounts)
    }
    @Test fun sameTitleOnDifferentCalendarsDoesNotMerge() {
        val first = daily(1).single()
        val result = layout(listOf(first, first.copy(calendarId = "other")))
        assertEquals(2, result.segments.size); assertEquals(2, result.rowCount)
    }
    @Test fun orderingDoesNotDependOnProviderResponseOrder() {
        assertEquals(layout(daily(5)), layout(daily(5).reversed()))
    }
    @Test fun allDayUsesUtcAndTimedUsesLocalDate() {
        val item = event("zone", "2026-09-11T00:00", "2026-09-12T00:00")
        val zone = ZoneId.of("America/Los_Angeles")
        assertEquals(5, calendarWeekLayout(listOf(item), sunday, zone = zone).segments.single().startColumn)
        val timed = item.copy(allDay = false)
        val result = calendarWeekLayout(listOf(timed), sunday, zone = zone)
        assertEquals(4, result.segments.single().startColumn)
        for (day in 0..6) assertEquals(if (timed.occursOn(sunday.plusDays(day.toLong()), zone)) 1 else 0, result.eventCounts[day])
    }
    @Test fun dstAndZeroDurationMatchExistingDateMembership() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.of(2026,3,8)
        val items = listOf(event("DST", "2026-03-08T00:00", "2026-03-09T00:00", false, zone),
            event("instant", "2026-03-10T00:00", "2026-03-10T00:00", false, zone))
        val result = calendarWeekLayout(items, start, zone = zone)
        for (day in 0..6) assertEquals(items.count { it.occursOn(start.plusDays(day.toLong()), zone) }, result.eventCounts[day])
    }
    @Test fun outsideWeekAndEmptyInputHaveNoPhantomRows() {
        assertEquals(0, layout(emptyList()).rowCount)
        assertTrue(layout(listOf(event("old", "2026-09-01T00:00", "2026-09-02T00:00"))).segments.isEmpty())
    }
}
