package com.moa.calendar.data

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Refreshes never narrow a previously downloaded date window. */
internal data class SyncWindow(val from: Long, val to: Long) {
    init { require(from < to) }
    fun including(from: Long, to: Long) = SyncWindow(minOf(this.from, from), maxOf(this.to, to))
    companion object {
        fun read(json: JSONObject, from: Long, to: Long): SyncWindow {
            val oldFrom = json.optLong("from", from)
            val oldTo = json.optLong("to", to)
            return if (oldFrom < oldTo) SyncWindow(oldFrom, oldTo).including(from, to) else SyncWindow(from, to)
        }
    }
}

internal data class DeviceCalendarSnapshot(
    val calendars: List<CalendarInfo>, val events: List<CalendarEvent>, val window: SyncWindow,
) {
    fun encode(): String = JSONObject().put("from", window.from).put("to", window.to)
        .put("calendars", JSONArray().apply { calendars.forEach { c ->
            put(JSONObject().put("id", c.id).put("name", c.name).put("account", c.account)
                .put("source", c.source.name).put("color", c.color).put("writable", c.writable).put("syncEnabled", c.syncEnabled))
        } }).put("events", JSONArray().apply { events.forEach { e ->
            put(JSONObject().put("id", e.id).put("calendarId", e.calendarId).put("title", e.title)
                .put("start", e.startMillis).put("end", e.endMillis).put("allDay", e.allDay)
                .put("description", e.description).put("location", e.location).put("recurring", e.recurring)
                .put("recurrenceRule", e.recurrenceRule)
                .put("seriesStart", e.seriesStartMillis ?: JSONObject.NULL)
                .put("seriesEnd", e.seriesEndMillis ?: JSONObject.NULL)
                .put("timeZone", e.timeZone)
                .put("recurrenceReadOnly", e.recurrenceReadOnly))
        } }).toString()

    companion object {
        fun decode(text: String): DeviceCalendarSnapshot {
            val json = JSONObject(text)
            val calendars = json.getJSONArray("calendars").let { array -> (0 until array.length()).map { index ->
                val c = array.getJSONObject(index)
                val source = CalendarSource.valueOf(c.getString("source"))
                CalendarInfo(c.getString("id"), c.getString("name"), c.getString("account"), source,
                    source.displayColor(c.getInt("color")), c.getBoolean("writable"), c.getBoolean("syncEnabled"))
            } }
            val byId = calendars.associateBy { it.id }
            val events = json.getJSONArray("events").let { array -> (0 until array.length()).map { index ->
                val e = array.getJSONObject(index)
                val c = byId.getValue(e.getString("calendarId"))
                val recurring = e.getBoolean("recurring")
                val hasRecurrenceMetadata = e.has("recurrenceRule") || e.has("seriesStart") || e.has("seriesEnd")
                CalendarEvent(e.getString("id"), c.id, e.getString("title"), e.getLong("start"), e.getLong("end"),
                    e.getBoolean("allDay"), e.getString("description"), e.getString("location"), c.source, c.color, recurring,
                    recurrenceRule = e.optString("recurrenceRule"),
                    seriesStartMillis = if (e.isNull("seriesStart")) null else e.optLong("seriesStart"),
                    seriesEndMillis = if (e.isNull("seriesEnd")) null else e.optLong("seriesEnd"),
                    timeZone = e.optString("timeZone"),
                    recurrenceReadOnly = e.optBoolean("recurrenceReadOnly", recurring && !hasRecurrenceMetadata))
            } }
            return DeviceCalendarSnapshot(calendars, events, SyncWindow(json.getLong("from"), json.getLong("to")))
        }
    }
}

internal data class DeviceCalendarRead(val snapshot: DeviceCalendarSnapshot, val error: String? = null)

/** A failed query is not a confirmed deletion. Publish calendars and instances together. */
internal fun readDeviceCalendars(
    previous: DeviceCalendarSnapshot?, from: Long, to: Long,
    calendars: () -> List<CalendarInfo>, events: (List<CalendarInfo>, Long, Long) -> List<CalendarEvent>,
): DeviceCalendarRead {
    val window = previous?.window?.including(from, to) ?: SyncWindow(from, to)
    return try {
        val current = calendars()
        DeviceCalendarRead(DeviceCalendarSnapshot(current, events(current, window.from, window.to), window))
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        DeviceCalendarRead(previous ?: DeviceCalendarSnapshot(emptyList(), emptyList(), window),
            "기기 캘린더를 읽지 못했어요. 저장된 일정을 표시합니다. 잠시 후 다시 시도해 주세요.")
    }
}
