package com.moa.calendar.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class CalendarSource { GOOGLE, NAVER, DEVICE }

data class CalendarInfo(
    val id: String,
    val name: String,
    val account: String,
    val source: CalendarSource,
    val color: Int,
    val writable: Boolean = true,
    val syncEnabled: Boolean = true,
    val supportsEvents: Boolean = true,
    val supportsTasks: Boolean = false,
)

fun CalendarInfo.accountLabel(): String {
    val service = when (source) {
        CalendarSource.GOOGLE -> "Google"
        CalendarSource.NAVER -> "NAVER"
        CalendarSource.DEVICE -> "기기 캘린더"
    }
    return if (account.isBlank()) service else "$service · $account"
}

fun calendarsForGoogleAccount(calendars: List<CalendarInfo>, account: String?): List<CalendarInfo> =
    calendars.filter { it.source != CalendarSource.GOOGLE || account == null || it.account.equals(account.trim(), ignoreCase = true) }

data class CalendarTask(
    val id: String, val calendarId: String, val title: String, val description: String,
    val dueMillis: Long?, val dueAllDay: Boolean, val completed: Boolean, val recurring: Boolean,
    val source: CalendarSource, val color: Int,
) {
    fun asCalendarEvent(): CalendarEvent? {
        val due = dueMillis ?: return null
        if (completed) return null
        return CalendarEvent(id, calendarId, "□ $title", due, due + if (dueAllDay) 86_400_000 else 60_000,
            dueAllDay, description, source = source, color = color, recurring = recurring, task = true)
    }
}

data class CalendarEvent(
    val id: String,
    val calendarId: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val description: String = "",
    val location: String = "",
    val source: CalendarSource,
    val color: Int,
    val recurring: Boolean = false,
    val href: String = "",
    val etag: String = "",
    val rawIcs: String = "",
    val task: Boolean = false,
) {
    fun occursOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val actualZone = if (allDay) ZoneId.of("UTC") else zone
        val from = date.atStartOfDay(actualZone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(actualZone).toInstant().toEpochMilli()
        return startMillis < to && endMillis.coerceAtLeast(startMillis + 1) > from
    }
    fun date(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(startMillis).atZone(if (allDay) ZoneId.of("UTC") else zone).toLocalDate()

    fun isUpcoming(nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val boundary = if (allDay) Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() else nowMillis
        return endMillis.coerceAtLeast(startMillis + 1) > boundary
    }
}

data class EventDraft(
    val calendarId: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val description: String = "",
    val location: String = "",
)

data class CalendarSnapshot(
    val calendars: List<CalendarInfo> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val lastSyncMillis: Long = 0,
    val errors: List<String> = emptyList(),
    val googleAccounts: List<String> = emptyList(),
    val tasks: List<CalendarTask> = emptyList(),
    val taskNotice: String = "네이버 계정을 연결하면 서버가 제공하는 할 일을 확인할 수 있어요.",
    val googleSyncNotice: String = "",
)
