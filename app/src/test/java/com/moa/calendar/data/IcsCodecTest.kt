package com.moa.calendar.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

class IcsCodecTest {
    private val calendar = CalendarInfo("https://example.com/cal/", "Test", "user", CalendarSource.NAVER, 1)
    private fun instant(text: String) = Instant.parse(text).toEpochMilli()
    private fun parse(body: String, from: String = "2026-09-01T00:00:00Z", to: String = "2026-10-01T00:00:00Z") =
        IcsCodec.parse(DavResource("https://example.com/cal/a.ics", "\"v1\"", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:test\r\n${body.replace("\n", "\r\n")}\r\nEND:VCALENDAR\r\n"), calendar, instant(from), instant(to))

    @Test fun `reads timed event and escaped text`() {
        val event = parse("BEGIN:VEVENT\nUID:a\nDTSTART:20260909T010000Z\nDTEND:20260909T020000Z\nSUMMARY:팀 미팅\\, 리뷰\nDESCRIPTION:첫 줄\\n둘째 줄\nEND:VEVENT").single()
        assertEquals("팀 미팅, 리뷰", event.title)
        assertEquals("첫 줄\n둘째 줄", event.description)
        assertEquals(instant("2026-09-09T01:00:00Z"), event.startMillis)
    }
    @Test fun `weekly recurrence handles excluded and overridden occurrences`() {
        val events = parse("""BEGIN:VEVENT
UID:weekly
DTSTART:20260902T010000Z
DTEND:20260902T020000Z
RRULE:FREQ=WEEKLY;COUNT=4
EXDATE:20260909T010000Z
SUMMARY:원래 일정
END:VEVENT
BEGIN:VEVENT
UID:weekly
RECURRENCE-ID:20260916T010000Z
DTSTART:20260917T020000Z
DTEND:20260917T030000Z
SUMMARY:변경된 일정
END:VEVENT""")
        assertEquals(listOf(2, 17, 23), events.map { it.date(ZoneOffset.UTC).dayOfMonth })
        assertEquals(1, events.count { it.title == "변경된 일정" })
        assertTrue(events.all { it.recurring })
    }
    @Test fun `cancelled exception suppresses original occurrence`() {
        val events = parse("BEGIN:VEVENT\nUID:a\nDTSTART:20260909T010000Z\nDURATION:PT1H\nRRULE:FREQ=DAILY;COUNT=2\nSUMMARY:A\nEND:VEVENT\nBEGIN:VEVENT\nUID:a\nRECURRENCE-ID:20260910T010000Z\nSTATUS:CANCELLED\nEND:VEVENT")
        assertEquals(1, events.size)
        assertEquals(9, events.single().date(ZoneOffset.UTC).dayOfMonth)
    }
    @Test fun `all day remains same date across device time zones`() {
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Seoul", "America/Los_Angeles", "Pacific/Auckland")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val event = parse("BEGIN:VEVENT\nUID:a\nDTSTART;VALUE=DATE:20260909\nDTEND;VALUE=DATE:20260911\nSUMMARY:휴가\nEND:VEVENT").single()
                assertEquals(instant("2026-09-09T00:00:00Z"), event.startMillis)
                assertTrue(event.occursOn(LocalDate.of(2026, 9, 10), ZoneId.of(zone)))
                assertFalse(event.occursOn(LocalDate.of(2026, 9, 11), ZoneId.of(zone)))
            }
        } finally { TimeZone.setDefault(previous) }
    }
    @Test fun `all day recurrence has UTC midnight boundaries`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            val events = parse("BEGIN:VEVENT\nUID:a\nDTSTART;VALUE=DATE:20260909\nDTEND;VALUE=DATE:20260910\nRRULE:FREQ=DAILY;COUNT=3\nSUMMARY:휴가\nEND:VEVENT")
            assertEquals(listOf(9, 10, 11), events.map { it.date().dayOfMonth })
            assertTrue(events.all { it.startMillis % 86_400_000 == 0L })
        } finally { TimeZone.setDefault(previous) }
    }
    @Test fun `timezone recurrence observes daylight saving`() {
        val events = parse("BEGIN:VEVENT\nUID:a\nDTSTART;TZID=America/New_York:20261031T100000\nDTEND;TZID=America/New_York:20261031T110000\nRRULE:FREQ=DAILY;COUNT=3\nSUMMARY:DST\nEND:VEVENT", "2026-10-30T00:00:00Z", "2026-11-05T00:00:00Z")
        assertEquals(3, events.size)
        assertEquals(instant("2026-10-31T14:00:00Z"), events[0].startMillis)
        assertEquals(instant("2026-11-01T15:00:00Z"), events[1].startMillis)
    }
    @Test fun `writes and roundtrips Korean event without losing linebreaks`() {
        val draft = EventDraft(calendar.id, "한글, 일정; 테스트", instant("2026-09-09T01:00:00Z"), instant("2026-09-09T02:00:00Z"), description = "첫 줄\n두 번째 줄", location = "서울")
        val result = IcsCodec.parse(DavResource("test", "v1", IcsCodec.write(draft)), calendar, instant("2026-09-01T00:00:00Z"), instant("2026-10-01T00:00:00Z")).single()
        assertEquals(draft.title, result.title)
        assertEquals(draft.description, result.description)
        assertEquals(draft.startMillis, result.startMillis)
    }
    @Test fun `editing preserves unknown fields and alarms`() {
        val existing = parse("BEGIN:VEVENT\nUID:a\nDTSTART:20260909T010000Z\nDTEND:20260909T020000Z\nSUMMARY:Old\nX-MOA-TEST:keep\nBEGIN:VALARM\nACTION:DISPLAY\nTRIGGER:-PT10M\nDESCRIPTION:reminder\nEND:VALARM\nEND:VEVENT").single()
        val raw = IcsCodec.write(EventDraft(calendar.id, "New", existing.startMillis, existing.endMillis), existing)
        assertTrue(raw.contains("X-MOA-TEST:keep"))
        assertTrue(raw.contains("BEGIN:VALARM"))
        assertTrue(raw.contains("UID:a"))
        assertTrue(raw.contains("SUMMARY:New"))
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects editing recurring instances`() {
        val existing = parse("BEGIN:VEVENT\nUID:a\nDTSTART:20260909T010000Z\nDTEND:20260909T020000Z\nRRULE:FREQ=DAILY;COUNT=1\nSUMMARY:A\nEND:VEVENT").single()
        IcsCodec.write(EventDraft(calendar.id, "New", existing.startMillis, existing.endMillis), existing)
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects invalid ranges`() { validateDraft(EventDraft("a", "Title", 1000, 900)) }
    @Test(expected = IllegalArgumentException::class) fun `rejects empty title`() { validateDraft(EventDraft("a", "  ", 1000, 2000)) }
    @Test fun `midnight ending event does not appear the next day`() {
        val event = CalendarEvent("a", "a", "test", instant("2026-09-09T23:00:00Z"), instant("2026-09-10T00:00:00Z"), source = CalendarSource.NAVER, color = 0)
        assertTrue(event.occursOn(LocalDate.parse("2026-09-09"), ZoneOffset.UTC))
        assertFalse(event.occursOn(LocalDate.parse("2026-09-10"), ZoneOffset.UTC))
    }
    @Test fun `all day widget removes yesterday at local midnight`() {
        val event = CalendarEvent("a", "a", "test", instant("2026-09-09T00:00:00Z"), instant("2026-09-10T00:00:00Z"), allDay = true, source = CalendarSource.NAVER, color = 0)
        assertFalse(event.isUpcoming(instant("2026-09-09T16:00:00Z"), ZoneId.of("Asia/Seoul")))
        assertTrue(event.isUpcoming(instant("2026-09-10T05:00:00Z"), ZoneId.of("America/Los_Angeles")))
    }
    @Test fun `all day write roundtrip preserves inclusive start exclusive end`() {
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Seoul", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val draft = EventDraft(calendar.id, "휴가", instant("2026-09-09T00:00:00Z"), instant("2026-09-11T00:00:00Z"), allDay = true)
                val result = IcsCodec.parse(DavResource("a", "v1", IcsCodec.write(draft)), calendar, instant("2026-09-01T00:00:00Z"), instant("2026-10-01T00:00:00Z")).single()
                assertEquals(draft.startMillis, result.startMillis)
                assertEquals(draft.endMillis, result.endMillis)
            }
        } finally { TimeZone.setDefault(previous) }
    }
}
