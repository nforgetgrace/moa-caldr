package com.moa.calendar.data

import org.junit.Assert.*
import org.junit.Test

class CalendarWidgetCapacityTest {
    private fun events(count: Int) = (1..count).map { CalendarEvent("$it", "cal", "Event $it", it * 1000L, it * 1000L + 100,
        source = CalendarSource.NAVER, color = 1) }

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
