package com.moa.calendar.data

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceCalendarCacheTest {
    private val calendar = CalendarInfo("device:1", "Work", "one@example.test", CalendarSource.GOOGLE, 0xFF03A86B.toInt())
    private val event = CalendarEvent("device:3@100", calendar.id, "Retained", 100, 200, source = calendar.source, color = calendar.color)
    private val cached = DeviceCalendarSnapshot(listOf(calendar), listOf(event), SyncWindow(0, 1000))

    @Test fun `failed calendar query retains last committed data`() {
        val result = readDeviceCalendars(cached, 0, 1000, { error("provider unavailable") }, { _, _, _ -> error("should not query") })
        assertEquals(cached, result.snapshot)
        assertNotNull(result.error)
    }
    @Test fun `failed instance query cannot publish partial calendar changes`() {
        val result = readDeviceCalendars(cached, 0, 1000, { listOf(calendar.copy(name = "Changed")) }, { _, _, _ -> error("provider unavailable") })
        assertEquals(cached, result.snapshot)
        assertNotNull(result.error)
    }
    @Test fun `successful empty result confirms deletion`() {
        val result = readDeviceCalendars(cached, 0, 1000, { emptyList() }, { _, _, _ -> emptyList() })
        assertTrue(result.snapshot.events.isEmpty())
        assertTrue(result.snapshot.calendars.isEmpty())
        assertNull(result.error)
    }
    @Test fun `successful update removes deleted and replaces changed events`() {
        val result = readDeviceCalendars(cached, 0, 1000, { listOf(calendar) }, { _, _, _ -> listOf(event.copy(title = "Updated")) })
        assertEquals(listOf("Updated"), result.snapshot.events.map { it.title })
        assertNull(result.error)
    }
    @Test fun `narrow widget read cannot discard previously viewed months`() {
        val result = readDeviceCalendars(cached, 300, 600, { listOf(calendar) }, { _, from, to ->
            assertEquals(0, from); assertEquals(1000, to); listOf(event)
        })
        assertEquals(cached, result.snapshot)
    }
    @Test fun `remote window expands and never shrinks on background refresh`() {
        val json = JSONObject().put("from", 0).put("to", 1000)
        assertEquals(SyncWindow(0, 1000), SyncWindow.read(json, 300, 600))
        assertEquals(SyncWindow(-500, 2000), SyncWindow.read(json, -500, 2000))
        assertEquals(SyncWindow(300, 600), SyncWindow.read(JSONObject(), 300, 600))
    }
    @Test fun `persistent cache restores source details and repeated instances`() {
        val extended = cached.copy(events = listOf(event.copy(description = "Memo", location = "Room", recurring = true),
            event.copy(id = "device:3@300", startMillis = 300, endMillis = 400)))
        assertEquals(extended, DeviceCalendarSnapshot.decode(extended.encode()))
    }
    @Test fun `persistent cache roundtrips recurrence metadata`() {
        val repeated = event.copy(
            recurring = true,
            recurrenceRule = "FREQ=WEEKLY",
            seriesStartMillis = 100,
            seriesEndMillis = 200,
            timeZone = "Asia/Seoul",
            recurrenceReadOnly = false,
        )
        assertEquals(repeated, DeviceCalendarSnapshot.decode(cached.copy(events = listOf(repeated)).encode()).events.single())
    }
    @Test fun `old repeated cache without metadata becomes read only fallback`() {
        val old = JSONObject(cached.copy(events = listOf(event.copy(recurring = true))).encode())
        val item = old.getJSONArray("events").getJSONObject(0)
        item.remove("recurrenceRule")
        item.remove("seriesStart")
        item.remove("seriesEnd")
        item.remove("timeZone")
        item.remove("recurrenceReadOnly")
        val restored = DeviceCalendarSnapshot.decode(old.toString()).events.single()
        assertTrue(restored.recurring)
        assertTrue(restored.recurrenceReadOnly)
    }
    @Test fun `account filtering also applies to fallback data`() {
        val second = calendar.copy(id = "device:2", account = "two@example.test")
        val snapshot = CalendarSnapshot(listOf(calendar, second), listOf(event, event.copy(calendarId = second.id)))
        val selected = snapshot.forGoogleAccount(second.account)
        assertEquals(listOf(second), selected.calendars)
        assertEquals(listOf(second.id), selected.events.map { it.calendarId })
    }
    @Test(expected = CancellationException::class) fun `cancelled read is not published as fallback`() {
        readDeviceCalendars(cached, 0, 1000, { throw CancellationException() }, { _, _, _ -> emptyList() })
    }
    @Test fun `provider duration parser handles event durations`() {
        assertEquals(86_400_000L, DeviceCalendars.durationMillis("P1D"))
        assertEquals(604_800_000L, DeviceCalendars.durationMillis("P1W"))
        assertEquals(1_209_600_000L, DeviceCalendars.durationMillis("P2W"))
        assertEquals(3_600_000L, DeviceCalendars.durationMillis("PT1H"))
        assertNull(DeviceCalendars.durationMillis(""))
    }
}
