package com.moa.calendar.data

import android.content.SharedPreferences
import org.json.JSONObject

data class CalendarColorChoice(val name: String, val color: Int)

class CalendarColors(private val prefs: SharedPreferences) {
    fun set(calendar: CalendarInfo, color: Int?) {
        val values = read().toMutableMap()
        val key = key(calendar)
        if (color == null) values.remove(key) else values[key] = color
        check(prefs.edit().putString(PREF_KEY, encode(values)).commit()) { "캘린더 색상을 저장하지 못했어요." }
    }

    fun defaultColor(calendar: CalendarInfo): Int = calendar.defaultColor
    fun isCustomized(calendar: CalendarInfo): Boolean = key(calendar) in read()

    fun apply(snapshot: CalendarSnapshot): CalendarSnapshot {
        val calendars = applyCalendars(snapshot.calendars)
        val colorByCalendar = calendars.associate { it.id to it.color }
        return snapshot.copy(
            calendars = calendars,
            events = snapshot.events.map { event -> event.copy(color = colorByCalendar[event.calendarId] ?: event.color) },
            tasks = snapshot.tasks.map { task -> task.copy(color = colorByCalendar[task.calendarId] ?: task.color) },
        )
    }

    fun applyCalendars(calendars: List<CalendarInfo>): List<CalendarInfo> {
        val values = read()
        return calendars.map { calendar ->
            val key = key(calendar)
            calendar.copy(color = values[key] ?: calendar.defaultColor)
        }
    }

    private fun read(): Map<String, Int> = read(PREF_KEY)
    private fun read(prefKey: String): Map<String, Int> {
        val json = JSONObject(prefs.getString(prefKey, "{}") ?: "{}")
        return json.keys().asSequence().associateWith { json.getInt(it) }
    }

    private fun encode(values: Map<String, Int>): String = JSONObject().apply {
        values.toSortedMap().forEach { (key, value) -> put(key, value) }
    }.toString()

    companion object {
        private const val PREF_KEY = "calendar_color_overrides"

        val choices = listOf(
            CalendarColorChoice("초록", 0xFF03A86B.toInt()),
            CalendarColorChoice("연보라", 0xFFB39DDB.toInt()),
            CalendarColorChoice("파랑", 0xFF4285F4.toInt()),
            CalendarColorChoice("인디고", 0xFF5965D8.toInt()),
            CalendarColorChoice("코랄", 0xFFEA8D77.toInt()),
            CalendarColorChoice("노랑", 0xFFF6B73C.toInt()),
            CalendarColorChoice("레드", 0xFFE05252.toInt()),
            CalendarColorChoice("민트", 0xFF31B7A6.toInt()),
        )

        fun key(calendar: CalendarInfo): String =
            listOf(calendar.source.name, calendar.account.trim().lowercase(), calendar.id.trim()).joinToString("\n")
    }
}
