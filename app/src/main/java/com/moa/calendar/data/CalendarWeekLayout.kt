package com.moa.calendar.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class CalendarWeekSegment(
    val event: CalendarEvent,
    val startColumn: Int,
    val endColumn: Int,
    val row: Int,
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
) {
    fun label(): String = (if (continuesBefore) "‹ " else "") + event.title.replace('\n', ' ') + if (continuesAfter) " ›" else ""
}

data class CalendarWeekLayout(
    val segments: List<CalendarWeekSegment>,
    val rowCount: Int,
    val hiddenCounts: List<Int>,
    val eventCounts: List<Int>,
)

/** One interval per event and week. Null capacity shows everything; widget capacity includes +N. */
fun calendarWeekLayout(events: List<CalendarEvent>, weekStart: LocalDate, availableLines: Int? = null,
    zone: ZoneId = ZoneId.systemDefault()): CalendarWeekLayout {
    val weekEnd = weekStart.plusDays(6)
    val candidates = events.mapNotNull { event ->
        val actualZone = if (event.allDay) ZoneOffset.UTC else zone
        val start = event.date(zone)
        // Calendar end timestamps are exclusive, including all-day DTEND and exact local midnight.
        val end = Instant.ofEpochMilli(event.endMillis.coerceAtLeast(event.startMillis + 1) - 1).atZone(actualZone).toLocalDate()
        if (end < weekStart || start > weekEnd) null else CalendarWeekSegment(event,
            ChronoUnit.DAYS.between(weekStart, maxOf(start, weekStart)).toInt(),
            ChronoUnit.DAYS.between(weekStart, minOf(end, weekEnd)).toInt(), 0, continuesBefore = start < weekStart, continuesAfter = end > weekEnd)
    }.sortedWith(compareBy<CalendarWeekSegment> { it.startColumn }.thenByDescending { it.endColumn }
        .thenBy { !it.event.allDay }.thenBy { it.event.startMillis }.thenBy { it.event.calendarId }.thenBy { it.event.id })
    val occupiedThrough = mutableListOf<Int>()
    val placed = candidates.map { segment ->
        val row = occupiedThrough.indexOfFirst { it < segment.startColumn }.let { if (it < 0) occupiedThrough.size else it }
        if (row == occupiedThrough.size) occupiedThrough.add(segment.endColumn) else occupiedThrough[row] = segment.endColumn
        segment.copy(row = row)
    }
    val counts = List(7) { day -> placed.count { day in it.startColumn..it.endColumn } }
    if (availableLines == null) return CalendarWeekLayout(placed, occupiedThrough.size, List(7) { 0 }, counts)
    val lines = availableLines.coerceIn(0, 4)
    var titleRows = minOf(3, lines)
    if (occupiedThrough.size > titleRows) titleRows = minOf(3, (lines - 1).coerceAtLeast(0))
    val visible = placed.filter { it.row < titleRows }
    val hidden = List(7) { day -> counts[day] - visible.count { day in it.startColumn..it.endColumn } }
    return CalendarWeekLayout(visible, (visible.maxOfOrNull { it.row } ?: -1) + 1, hidden, counts)
}
