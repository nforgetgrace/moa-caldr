package com.moa.calendar.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class CalendarSource { GOOGLE, NAVER, DEVICE }

fun CalendarSource.displayColor(original: Int): Int = when (this) {
    CalendarSource.NAVER -> 0xFFB39DDB.toInt()
    CalendarSource.GOOGLE -> 0xFF03A86B.toInt()
    CalendarSource.DEVICE -> original
}

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

fun CalendarInfo.selectionSubtitle(): String =
    if (account.trim().equals(name.trim(), ignoreCase = true)) copy(account = "").accountLabel() else accountLabel()

fun calendarsForGoogleAccount(calendars: List<CalendarInfo>, account: String?): List<CalendarInfo> =
    calendars.filter { it.source != CalendarSource.GOOGLE || account == null || it.account.equals(account.trim(), ignoreCase = true) }

fun CalendarSnapshot.forGoogleAccount(account: String?): CalendarSnapshot {
    val selected = calendarsForGoogleAccount(calendars, account)
    val ids = selected.map { it.id }.toSet()
    return copy(calendars = selected, events = events.filter { it.calendarId in ids }, tasks = tasks.filter { it.calendarId in ids })
}

fun calendarMonthLineCapacity(weekEventCounts: List<Int>, gridHeight: Int, lineHeight: Int): Int =
    (4 downTo 0).firstOrNull { lines ->
        weekEventCounts.sumOf { 22 + it.coerceIn(0, lines) * (lineHeight + 1) } <= gridHeight
    } ?: 0

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

/** Shared by editor and entry actions so read-only entries never promise editing. */
fun CalendarEvent.editRestriction(calendar: CalendarInfo?): String? = when {
    task -> "할 일은 원본 캘린더에서 수정해 주세요."
    recurring -> "반복 일정은 원본 캘린더에서 수정해 주세요."
    calendar == null || calendar.id != calendarId || !calendar.writable || !calendar.syncEnabled || !calendar.supportsEvents ->
        "이 캘린더는 앱에서 수정할 수 없어요. 원본 캘린더의 권한과 동기화 설정을 확인해 주세요."
    rawIcs.contains("ATTENDEE", true) || rawIcs.contains("ORGANIZER", true) ->
        "초대 일정은 원본 캘린더에서 수정해 주세요."
    else -> null
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
    val initialNaverSync: Boolean = false,
)
