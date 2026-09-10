package com.moa.calendar.data

import org.junit.Assert.*
import org.junit.Test

class EventEditingTest {
    private val calendar = CalendarInfo("cal", "Calendar", "user", CalendarSource.NAVER, 0)
    private val event = CalendarEvent("event", "cal", "Event", 1000, 2000, source = CalendarSource.NAVER, color = 0)

    @Test fun `ordinary events in writable source calendars offer editing`() {
        CalendarSource.entries.forEach { source ->
            assertNull(event.copy(source = source).editRestriction(calendar.copy(source = source)))
        }
    }
    @Test fun `missing wrong disabled and read only calendars cannot offer editing`() {
        listOf(null, calendar.copy(id = "other"), calendar.copy(writable = false),
            calendar.copy(syncEnabled = false), calendar.copy(supportsEvents = false)).forEach {
            assertNotNull(event.editRestriction(it))
        }
    }
    @Test fun `recurrence and tasks explain their own restrictions`() {
        assertNotNull(event.copy(recurring = true).editRestriction(calendar))
        assertTrue(event.copy(task = true).editRestriction(calendar)!!.contains("할 일"))
    }
    @Test fun `whole series can be edited only with verified master metadata`() {
        val recurring = event.copy(recurring = true, recurrenceRule = "FREQ=DAILY", seriesStartMillis = 1000, seriesEndMillis = 2000)
        assertNull(recurring.editRestriction(calendar))
        assertNotNull(recurring.copy(recurrenceReadOnly = true).editRestriction(calendar))
        assertNotNull(recurring.copy(seriesStartMillis = null).editRestriction(calendar))
        assertNotNull(recurring.copy(seriesEndMillis = null).editRestriction(calendar))
        assertNotNull(recurring.editRestriction(calendar.copy(writable = false)))
    }

    @Test fun `custom finite rules are never mistaken for unlimited basic repetition`() {
        assertEquals(RepeatFrequency.MONTHLY, RepeatFrequency.fromRule("FREQ=MONTHLY"))
        assertNull(RepeatFrequency.fromRule("FREQ=DAILY;COUNT=4"))
        assertNull(RepeatFrequency.fromRule("FREQ=WEEKLY;BYDAY=MO,WE"))
    }
    @Test fun `invitation restriction remains visible`() {
        listOf("ATTENDEE:mailto:user@example.test", "ORGANIZER:mailto:owner@example.test").forEach {
            assertTrue(event.copy(rawIcs = it).editRestriction(calendar)!!.contains("초대 일정"))
        }
    }
}
