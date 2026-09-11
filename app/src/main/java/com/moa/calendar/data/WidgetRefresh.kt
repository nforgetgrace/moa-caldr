package com.moa.calendar.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A widget refresh that never reports back (killed job, throttled scheduler) stops showing as in progress after this long. */
const val WIDGET_REFRESH_TIMEOUT_MILLIS = 90_000L

fun widgetRefreshInProgress(startedAtMillis: Long, nowMillis: Long): Boolean =
    startedAtMillis > 0 && nowMillis - startedAtMillis in 0 until WIDGET_REFRESH_TIMEOUT_MILLIS

/** Short local date and time, e.g. `9/11 00:14`, for the small status line. */
fun widgetSyncTime(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("M/d HH:mm"))

fun widgetStatusText(
    refreshing: Boolean,
    hasErrors: Boolean,
    hasCalendars: Boolean,
    lastSyncMillis: Long,
    zone: ZoneId,
): String {
    val synced = if (lastSyncMillis > 0) "최근 동기화 ${widgetSyncTime(lastSyncMillis, zone)}" else null
    return when {
        refreshing -> "동기화 중…"
        hasErrors -> "갱신 확인 필요 · 저장된 일정" + (synced?.let { " · $it" } ?: "")
        !hasCalendars -> "앱에서 캘린더를 연결해 주세요"
        synced != null -> synced
        else -> "기기에 저장된 일정"
    }
}
