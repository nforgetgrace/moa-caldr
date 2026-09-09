package com.moa.calendar.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

class TaskCodecTest {
    private val calendar = CalendarInfo("https://example.test/tasks/", "내 할 일", "tester", CalendarSource.NAVER, 1, supportsEvents = false, supportsTasks = true)
    private fun parse(properties: String): CalendarTask = TaskCodec.parse(resource(properties), calendar).single()
    private fun resource(properties: String) = DavResource("https://example.test/tasks/1.ics", "\"v1\"", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:task-1\r\nSUMMARY:서류 제출\r\n$properties\r\nEND:VTODO\r\nEND:VCALENDAR\r\n")

    @Test fun undatedTasksAreRetainedWithoutInventingADate() {
        val task = parse("DESCRIPTION:기한 없이 보관")
        assertEquals("서류 제출", task.title)
        assertNull(task.dueMillis)
        assertNull(task.asCalendarEvent())
    }
    @Test fun dateOnlyDeadlineRemainsOnTheSameDateAcrossTimezones() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Seoul", "America/Los_Angeles", "Pacific/Auckland")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val task = parse("DUE;VALUE=DATE:20260910")
                assertTrue(task.dueAllDay)
                assertEquals(Instant.parse("2026-09-10T00:00:00Z").toEpochMilli(), task.dueMillis)
                val event = task.asCalendarEvent()!!
                assertTrue(event.task)
                assertTrue(event.occursOn(LocalDate.parse("2026-09-10")))
                assertFalse(event.occursOn(LocalDate.parse("2026-09-11")))
            }
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun timedDeadlineAndDurationAreRead() {
        val dated = parse("DUE:20260910T030000Z")
        val duration = parse("DTSTART:20260910T020000Z\r\nDURATION:PT1H")
        assertEquals(Instant.parse("2026-09-10T03:00:00Z").toEpochMilli(), dated.dueMillis)
        assertEquals(dated.dueMillis, duration.dueMillis)
        assertFalse(dated.dueAllDay)
    }
    @Test fun completedTasksRemainInListButNotInCalendarAgenda() {
        for (completion in listOf("STATUS:COMPLETED", "COMPLETED:20260909T000000Z", "PERCENT-COMPLETE:100")) {
            val task = parse("DUE;VALUE=DATE:20260910\r\n$completion")
            assertTrue(task.completed)
            assertNull(task.asCalendarEvent())
        }
    }
    @Test fun cancelledTasksAreNotShown() {
        assertTrue(TaskCodec.parse(resource("STATUS:CANCELLED"), calendar).isEmpty())
    }
    @Test fun recurringTasksAreMarkedAndExceptionsHaveDistinctIds() {
        val master = parse("DUE:20260910T030000Z\r\nRRULE:FREQ=DAILY")
        val exception = parse("DUE:20260911T040000Z\r\nRECURRENCE-ID:20260911T030000Z")
        assertTrue(master.recurring)
        assertTrue(exception.recurring)
        assertNotEquals(master.id, exception.id)
    }
    @Test fun eventOnlyResourcesAreNotMisrepresentedAsTasks() {
        val resource = DavResource("https://example.test/e.ics", "v1", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:e\r\nDTSTART:20260910T030000Z\r\nSUMMARY:Event\r\nEND:VEVENT\r\nEND:VCALENDAR")
        assertTrue(TaskCodec.parse(resource, calendar).isEmpty())
    }
}
