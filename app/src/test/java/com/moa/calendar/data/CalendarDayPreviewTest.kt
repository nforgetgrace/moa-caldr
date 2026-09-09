package com.moa.calendar.data

import org.junit.Assert.*
import org.junit.Test

class CalendarDayPreviewTest {
    private fun events(count: Int) = (1..count).map { CalendarEvent("$it", "cal", "Event $it", it * 1000L, it * 1000L + 100,
        source = CalendarSource.NAVER, color = 1) }

    @Test fun fiveEventsShowThreeTitlesAndTwoMore() {
        val result = calendarDayPreview(events(5))
        assertEquals(listOf("Event 1", "Event 2", "Event 3"), result.events.map { it.title })
        assertEquals(2, result.remaining)
    }
    @Test fun threeEventsNeedNoOverflowRow() {
        assertEquals(3, calendarDayPreview(events(3), 3).events.size)
        assertEquals(0, calendarDayPreview(events(3), 3).remaining)
    }
    @Test fun smallWidgetsReserveRoomForTheCorrectHiddenCount() {
        for (lines in 0..4) {
            val result = calendarDayPreview(events(8), lines)
            assertEquals(8, result.events.size + result.remaining)
            assertTrue(result.events.size <= 3)
            if (lines > 0) assertTrue(result.events.size + 1 <= lines)
        }
        assertEquals(8, calendarDayPreview(events(8), 1).remaining)
    }
    @Test fun allDayEventsStayBeforeTimedEvents() {
        val result = calendarDayPreview(events(4) + events(1).single().copy(id = "all", allDay = true, startMillis = 9000))
        assertEquals("all", result.events.first().id)
        assertEquals(2, result.remaining)
    }
    @Test fun emptyDayHasNoPhantomCount() {
        assertEquals(CalendarDayPreview(emptyList(), 0), calendarDayPreview(emptyList()))
    }
    @Test fun sparseMonthUsesEmptyWeeksSpaceForThreeTitlesAndCount() {
        assertEquals(4, calendarMonthLineCapacity(listOf(0, 5, 0, 0, 0), 190, 14))
    }
    @Test fun crowdedSmallMonthFitsWithoutOverflowingTheGrid() {
        assertEquals(2, calendarMonthLineCapacity(List(6) { 5 }, 320, 14))
        assertEquals(0, calendarMonthLineCapacity(List(6) { 5 }, 100, 14))
    }
    @Test fun accountSwitchKeepsNaverButRemovesOtherGoogleEvents() {
        val a = CalendarInfo("a", "A", "a@example.test", CalendarSource.GOOGLE, 1)
        val b = a.copy(id = "b", account = "b@example.test")
        val n = a.copy(id = "n", source = CalendarSource.NAVER)
        val snapshot = CalendarSnapshot(listOf(a, b, n), events(3).mapIndexed { i, e -> e.copy(calendarId = listOf("a", "b", "n")[i]) })
        assertEquals(listOf("b", "n"), snapshot.forGoogleAccount("b@example.test").events.map { it.calendarId })
        assertEquals(listOf("n"), snapshot.forGoogleAccount("new@example.test").events.map { it.calendarId })
    }
}
