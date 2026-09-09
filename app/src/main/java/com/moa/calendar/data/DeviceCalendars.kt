package com.moa.calendar.data

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.os.Bundle
import java.time.ZoneId

class DeviceCalendars(private val context: Context) {
    private val resolver get() = context.contentResolver
    fun hasReadPermission() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun hasWritePermission() = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(): List<CalendarInfo> {
        if (!hasReadPermission()) return emptyList()
        val result = mutableListOf<CalendarInfo>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf("_id", "calendar_displayName", "account_name", "account_type", "calendar_color", "calendar_access_level", "sync_events"),
            null, null, "calendar_displayName ASC")?.use { c ->
            while (c.moveToNext()) result += CalendarInfo(
                "device:${c.getLong(0)}", c.getString(1) ?: "캘린더", c.getString(2).orEmpty(),
                if (c.getString(3) == "com.google") CalendarSource.GOOGLE else CalendarSource.DEVICE,
                if (c.getString(3) == "com.google") CalendarSource.GOOGLE.displayColor(c.getInt(4))
                else c.getInt(4).let { if (it == 0) 0xFF4285F4.toInt() else it or 0xFF000000.toInt() },
                c.getInt(5) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR && hasWritePermission(),
                syncEnabled = c.getInt(6) == 1,
            )
        }
        return result
    }

    fun events(calendars: List<CalendarInfo>, from: Long, to: Long): List<CalendarEvent> {
        if (!hasReadPermission() || calendars.isEmpty()) return emptyList()
        val byId = calendars.filter { it.syncEnabled }.associateBy { it.id }
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from)
        ContentUris.appendId(builder, to)
        val result = mutableListOf<CalendarEvent>()
        val columns = arrayOf("event_id", "calendar_id", "title", "begin", "end", "allDay", "description", "eventLocation", "rrule", "rdate", "original_id")
        resolver.query(builder.build(), columns, "deleted=0 AND (eventStatus IS NULL OR eventStatus!=?)", arrayOf(CalendarContract.Events.STATUS_CANCELED.toString()), "begin ASC")?.use { c ->
            while (c.moveToNext()) {
                val calendar = byId["device:${c.getLong(1)}"] ?: continue
                result += CalendarEvent("device:${c.getLong(0)}@${c.getLong(3)}", calendar.id,
                    c.getString(2) ?: "제목 없는 일정", c.getLong(3), c.getLong(4), c.getInt(5) == 1,
                    c.getString(6).orEmpty(), c.getString(7).orEmpty(), calendar.source, calendar.color,
                    !c.getString(8).isNullOrEmpty() || !c.getString(9).isNullOrEmpty() || !c.isNull(10))
            }
        }
        return result
    }

    fun googleSyncNotice(accountName: String?): String {
        if (accountName == null) return "사용할 Google 계정을 선택해 주세요."
        if (!ContentResolver.getSyncAdapterTypes().any { it.accountType == "com.google" && it.authority == CalendarContract.AUTHORITY })
            return "Google 캘린더 동기화 기능이 기기에 없어요. Google Calendar 앱을 설치하고 선택한 계정으로 열어 주세요."
        val account = Account(accountName, "com.google")
        return when {
            !ContentResolver.getMasterSyncAutomatically() -> "기기의 자동 동기화가 꺼져 있어요. 계정 동기화 설정을 확인해 주세요."
            !ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY) -> "선택한 Google 계정의 캘린더 자동 동기화가 꺼져 있어요."
            else -> "선택한 Google 계정의 기기 동기화를 사용합니다."
        }
    }

    fun requestGoogleSync(accountName: String) {
        require(accountName.isNotBlank())
        check(ContentResolver.getSyncAdapterTypes().any { it.accountType == "com.google" && it.authority == CalendarContract.AUTHORITY }) {
            "Google Calendar 앱을 설치하고 선택한 계정으로 한 번 열어 주세요."
        }
        ContentResolver.requestSync(Account(accountName, "com.google"), CalendarContract.AUTHORITY, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
    }

    fun enableGoogleCalendarSync(id: String, selectedAccount: String?) {
        check(hasWritePermission()) { "캘린더 쓰기 권한이 필요해요." }
        val calendar = calendars().firstOrNull { it.id == id } ?: error("캘린더를 찾지 못했어요.")
        require(calendar.source == CalendarSource.GOOGLE &&
            (selectedAccount == null || calendar.account.equals(selectedAccount, true))) { "선택한 Google 계정의 캘린더만 변경할 수 있어요." }
        check(resolver.update(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id.removePrefix("device:").toLong()),
            ContentValues().apply { put(CalendarContract.Calendars.SYNC_EVENTS, 1); put(CalendarContract.Calendars.VISIBLE, 1) }, null, null) == 1) {
            "캘린더 동기화 설정을 바꾸지 못했어요."
        }
    }

    fun save(draft: EventDraft, existing: CalendarEvent?) {
        validateDraft(draft)
        check(hasWritePermission()) { "캘린더 쓰기 권한이 필요해요." }
        require(existing?.recurring != true) { "반복 일정은 원본 캘린더에서 수정해 주세요." }
        check(calendars().any { it.id == draft.calendarId && it.writable && it.syncEnabled }) { "이 캘린더에는 일정을 저장할 수 없어요." }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, draft.calendarId.removePrefix("device:").toLong())
            put(CalendarContract.Events.TITLE, draft.title.trim())
            put(CalendarContract.Events.DTSTART, draft.startMillis)
            put(CalendarContract.Events.DTEND, draft.endMillis)
            put(CalendarContract.Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else ZoneId.systemDefault().id)
            put(CalendarContract.Events.DESCRIPTION, draft.description)
            put(CalendarContract.Events.EVENT_LOCATION, draft.location)
        }
        if (existing == null) checkNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, values)) { "일정을 저장하지 못했어요." }
        else check(resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId(existing)), values, null, null) == 1) { "원본 일정이 변경됐어요. 새로고침해 주세요." }
    }

    fun delete(event: CalendarEvent) {
        check(hasWritePermission()) { "캘린더 쓰기 권한이 필요해요." }
        require(!event.recurring) { "반복 일정은 원본 캘린더에서 삭제해 주세요." }
        check(calendars().any { it.id == event.calendarId && it.writable }) { "읽기 전용 캘린더예요." }
        check(resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId(event)), null, null) == 1) { "이미 삭제되었거나 변경된 일정이에요." }
    }

    private fun eventId(event: CalendarEvent) = event.id.removePrefix("device:").substringBefore('@').toLong()
}
