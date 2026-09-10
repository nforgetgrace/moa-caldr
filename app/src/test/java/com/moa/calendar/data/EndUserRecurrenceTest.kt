package com.moa.calendar.data

import biweekly.Biweekly
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

class EndUserRecurrenceTest {
    private val calendar = CalendarInfo("cal", "Personal", "fixture", CalendarSource.NAVER, 0)
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private fun parse(ics: String, from: String, to: String) = IcsCodec.parse(DavResource("https://fixture.test/one.ics", "v1", ics), calendar, millis(from), millis(to))
    private fun draft(start: String, rule: String, allDay: Boolean = false, zone: String = "UTC", duration: Long = 3_600_000L) =
        EventDraft(calendar.id, "Recurring", millis(start), millis(start) + duration, allDay, recurrenceRule = rule, timeZone = zone)

    @Test fun `all four choices serialize and expand on the requested dates`() {
        val cases = listOf(
            Triple("FREQ=DAILY", "2026-01-19T00:00:00Z", listOf("2026-01-15", "2026-01-16", "2026-01-17", "2026-01-18")),
            Triple("FREQ=WEEKLY", "2026-02-06T00:00:00Z", listOf("2026-01-15", "2026-01-22", "2026-01-29", "2026-02-05")),
            Triple("FREQ=MONTHLY", "2026-05-01T00:00:00Z", listOf("2026-01-15", "2026-02-15", "2026-03-15", "2026-04-15")),
            Triple("FREQ=YEARLY", "2030-01-01T00:00:00Z", listOf("2026-01-15", "2027-01-15", "2028-01-15", "2029-01-15")),
        )
        for ((rule, to, dates) in cases) {
            val input = draft("2026-01-15T10:00:00Z", rule)
            val ics = IcsCodec.write(input)
            assertTrue(ics.contains("RRULE:$rule"))
            val events = parse(ics, "2026-01-01T00:00:00Z", to)
            assertEquals(dates, events.map { it.date(ZoneOffset.UTC).toString() })
            assertTrue(events.all { it.seriesStartMillis == input.startMillis && it.seriesEndMillis == input.endMillis })
        }
    }

    @Test fun `monthly thirty first skips short months and leap day skips ordinary years`() {
        val monthly = IcsCodec.write(draft("2026-01-31T10:00:00Z", "FREQ=MONTHLY"))
        assertEquals(listOf("2026-01-31", "2026-03-31", "2026-05-31"),
            parse(monthly, "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z").map { it.date(ZoneOffset.UTC).toString() })
        val yearly = IcsCodec.write(draft("2024-02-29T10:00:00Z", "FREQ=YEARLY"))
        assertEquals(listOf("2024-02-29", "2028-02-29", "2032-02-29"),
            parse(yearly, "2024-01-01T00:00:00Z", "2033-01-01T00:00:00Z").map { it.date(ZoneOffset.UTC).toString() })
    }

    @Test fun `new timed repetition preserves wall clock across daylight saving`() {
        val ics = IcsCodec.write(draft("2026-10-31T14:00:00Z", "FREQ=DAILY", zone = "America/New_York"))
        assertTrue(ics, ics.contains("America/New_York"))
        val events = parse(ics, "2026-10-31T00:00:00Z", "2026-11-03T00:00:00Z")
        assertEquals(listOf(10, 10, 10), events.map { Instant.ofEpochMilli(it.startMillis).atZone(ZoneId.of("America/New_York")).hour })
        assertEquals(listOf(14, 15, 15), events.map { Instant.ofEpochMilli(it.startMillis).atZone(ZoneOffset.UTC).hour })
    }

    @Test fun `multi day all day repetition retains UTC dates in east and west zones`() {
        val before = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Seoul", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val ics = IcsCodec.write(draft("2026-09-11T00:00:00Z", "FREQ=DAILY", allDay = true, duration = 2 * 86_400_000L))
                val events = parse(ics, "2026-09-11T00:00:00Z", "2026-09-14T00:00:00Z")
                assertEquals(listOf("2026-09-11", "2026-09-12", "2026-09-13"), events.map { it.date().toString() })
                assertTrue(events.all { it.allDay && it.endMillis - it.startMillis == 2 * 86_400_000L && it.startMillis % 86_400_000L == 0L })
            }
        } finally { TimeZone.setDefault(before) }
    }

    @Test fun `later occurrence can change frequency and then stop without duplicate or rebased first date`() {
        val input = draft("2026-09-11T10:00:00Z", "FREQ=WEEKLY")
        val original = IcsCodec.write(input, uid = "same-series")
        val later = parse(original, "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")[2]
        val monthly = IcsCodec.write(input.copy(recurrenceRule = "FREQ=MONTHLY"), later)
        val changed = parse(monthly, "2026-09-01T00:00:00Z", "2027-01-01T00:00:00Z")
        assertEquals(4, changed.size)
        assertEquals(input.startMillis, changed.first().startMillis)
        val single = IcsCodec.write(input.copy(recurrenceRule = ""), changed[2])
        val event = parse(single, "2026-09-01T00:00:00Z", "2027-01-01T00:00:00Z").single()
        assertFalse(event.recurring)
        assertEquals(input.startMillis, event.startMillis)
        assertEquals("same-series", Biweekly.parse(single).first().events.single().uid.value)
    }

    @Test fun `all day to timed repetition uses the newly selected timezone`() {
        val input = draft("2026-09-11T00:00:00Z", "FREQ=DAILY", allDay = true, duration = 86_400_000L)
        val first = parse(IcsCodec.write(input), "2026-09-11T00:00:00Z", "2026-09-12T00:00:00Z").single()
        val timed = draft("2026-09-11T01:00:00Z", "FREQ=DAILY", zone = "Asia/Seoul")
        val output = IcsCodec.write(timed, first)
        assertTrue(output, output.contains("Asia/Seoul"))
        assertFalse(parse(output, "2026-09-11T00:00:00Z", "2026-09-12T00:00:00Z").single().allDay)
    }
}
