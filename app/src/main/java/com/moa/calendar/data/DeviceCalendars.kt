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
import java.time.Duration
import java.time.ZoneId
import kotlin.math.max

class DeviceCalendars(private val context: Context) {
    private val resolver get() = context.contentResolver
    fun hasReadPermission() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun hasWritePermission() = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(): List<CalendarInfo> {
        if (!hasReadPermission()) return emptyList()
        val result = mutableListOf<CalendarInfo>()
        checkNotNull(resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf("_id", "calendar_displayName", "account_name", "account_type", "calendar_color", "calendar_access_level", "sync_events"),
            null, null, "calendar_displayName ASC")) { "기기 캘린더 조회 응답이 없어요." }.use { c ->
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
        val instances = mutableListOf<DeviceInstance>()
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        checkNotNull(resolver.query(builder.build(), columns, "deleted=0 AND (eventStatus IS NULL OR eventStatus!=?)", arrayOf(CalendarContract.Events.STATUS_CANCELED.toString()), "begin ASC")) { "기기 일정 조회 응답이 없어요." }.use { c ->
            while (c.moveToNext()) {
                val calendar = byId["device:${c.getLong(1)}"] ?: continue
                instances += DeviceInstance(c.getLong(0), calendar, c.getString(2) ?: "제목 없는 일정",
                    c.getLong(3), c.getLong(4), c.getInt(5) == 1, c.getString(6).orEmpty(), c.getString(7).orEmpty())
            }
        }
        val metadata = eventMetadata(instances.map { it.eventId }.distinct())
        return instances.map { instance ->
            val meta = metadata[instance.eventId] ?: error("기기 반복 일정 원본을 읽지 못했어요. 저장된 일정을 유지합니다.")
            val recurring = meta.recurring
            CalendarEvent("device:${instance.eventId}@${instance.begin}", instance.calendar.id,
                instance.title, instance.begin, instance.end, instance.allDay, instance.description, instance.location,
                instance.calendar.source, instance.calendar.color, recurring,
                recurrenceRule = meta.rrule,
                seriesStartMillis = if (recurring) meta.dtStart else null,
                seriesEndMillis = if (recurring) meta.seriesEndMillis else null,
                timeZone = meta.timeZone,
                recurrenceReadOnly = recurring && meta.readOnly,
            )
        }
    }

    fun isGoogleSyncActive(accountNames: List<String>): Boolean = accountNames.distinct().filter { it.isNotBlank() }.any { name ->
        runCatching { ContentResolver.isSyncActive(Account(name, "com.google"), CalendarContract.AUTHORITY) }.getOrDefault(false)
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
        check(calendars().any { it.id == draft.calendarId && it.writable && it.syncEnabled }) { "이 캘린더에는 일정을 저장할 수 없어요." }
        val live = existing?.let { liveEditableEvent(it) }
        val recurring = draft.recurrenceRule.isNotBlank()
        val eventTimeZone = when {
            draft.allDay -> "UTC"
            draft.timeZone.isNotBlank() -> draft.timeZone
            else -> ZoneId.systemDefault().id
        }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, draft.calendarId.removePrefix("device:").toLong())
            put(CalendarContract.Events.TITLE, draft.title.trim())
            put(CalendarContract.Events.DTSTART, draft.startMillis)
            put(CalendarContract.Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, eventTimeZone)
            put(CalendarContract.Events.DESCRIPTION, draft.description)
            put(CalendarContract.Events.EVENT_LOCATION, draft.location)
            if (recurring) {
                put(CalendarContract.Events.RRULE, draft.recurrenceRule)
                put(CalendarContract.Events.DURATION, recurrenceDuration(draft))
                putNull(CalendarContract.Events.DTEND)
            } else {
                put(CalendarContract.Events.DTEND, draft.endMillis)
                putNull(CalendarContract.Events.RRULE)
                putNull(CalendarContract.Events.RDATE)
                putNull(CalendarContract.Events.EXRULE)
                putNull(CalendarContract.Events.EXDATE)
                putNull(CalendarContract.Events.DURATION)
            }
        }
        if (existing == null) checkNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, values)) { "일정을 저장하지 못했어요." }
        else check(resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, checkNotNull(live).id), values, null, null) == 1) { "원본 일정이 변경됐어요. 새로고침해 주세요." }
    }

    fun delete(event: CalendarEvent) {
        check(hasWritePermission()) { "캘린더 쓰기 권한이 필요해요." }
        require(!event.recurring) { "반복 일정은 원본 캘린더에서 삭제해 주세요." }
        check(calendars().any { it.id == event.calendarId && it.writable }) { "읽기 전용 캘린더예요." }
        check(resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId(event)), null, null) == 1) { "이미 삭제되었거나 변경된 일정이에요." }
    }

    private fun eventId(event: CalendarEvent) = event.id.removePrefix("device:").substringBefore('@').toLong()

    private data class DeviceInstance(
        val eventId: Long, val calendar: CalendarInfo, val title: String, val begin: Long, val end: Long,
        val allDay: Boolean, val description: String, val location: String,
    )

    private data class EventMeta(
        val id: Long, val calendarId: Long, val dtStart: Long, val dtEnd: Long?, val duration: String,
        val rrule: String, val rdate: String, val exrule: String, val exdate: String, val originalId: Long?,
        val timeZone: String, val allDay: Boolean, val hasOverrides: Boolean,
    ) {
        val recurring get() = rrule.isNotBlank() || rdate.isNotBlank() || originalId != null
        val readOnly get() = originalId != null || hasOverrides || rdate.isNotBlank() || exrule.isNotBlank() || exdate.isNotBlank()
        val seriesEndMillis get() = dtEnd ?: durationMillis(duration)?.let { dtStart + it }
    }

    private fun eventMetadata(ids: List<Long>): Map<Long, EventMeta> {
        if (ids.isEmpty()) return emptyMap()
        val requested = ids.toSet()
        val rows = queryEventRows(ids, includeOverrides = true)
        val overrideMasters = rows.mapNotNull { it.originalId }.toSet()
        return rows.filter { it.id in requested }.associate { row -> row.id to row.copy(hasOverrides = row.hasOverrides || row.id in overrideMasters) }
    }

    private fun queryEventRows(ids: List<Long>, includeOverrides: Boolean): List<EventMeta> {
        val rows = mutableListOf<EventMeta>()
        ids.distinct().chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            val selection = if (includeOverrides) "(${CalendarContract.Events._ID} IN ($marks) OR ${CalendarContract.Events.ORIGINAL_ID} IN ($marks))"
                else "${CalendarContract.Events._ID} IN ($marks)"
            val args = (if (includeOverrides) chunk + chunk else chunk).map { it.toString() }.toTypedArray()
            checkNotNull(resolver.query(CalendarContract.Events.CONTENT_URI, EVENT_COLUMNS, selection, args, null)) { "기기 일정 원본 조회 응답이 없어요." }.use { c ->
                while (c.moveToNext()) rows += EventMeta(c.getLong(0), c.getLong(1), c.getLong(2), if (c.isNull(3)) null else c.getLong(3),
                    c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getString(6).orEmpty(), c.getString(7).orEmpty(),
                    c.getString(8).orEmpty(), if (c.isNull(9)) null else c.getLong(9), c.getString(10).orEmpty(),
                    c.getInt(11) == 1, false)
            }
        }
        return rows
    }

    private fun liveEditableEvent(event: CalendarEvent): EventMeta {
        val id = eventId(event)
        val live = eventMetadata(listOf(id))[id] ?: error("원본 일정이 변경됐어요. 새로고침해 주세요.")
        check(live.calendarId == event.calendarId.removePrefix("device:").toLong()) { "일정의 원본 캘린더는 변경할 수 없어요." }
        if (event.recurring) {
            require(!event.recurrenceReadOnly && event.seriesStartMillis != null && event.seriesEndMillis != null) {
                "개별 변경·예외가 있거나 원본 반복 정보를 확인할 수 없는 일정은 원본 캘린더에서 수정해 주세요."
            }
            check(live.recurring && !live.readOnly && live.rrule == event.recurrenceRule &&
                live.dtStart == event.seriesStartMillis && live.seriesEndMillis == event.seriesEndMillis &&
                live.allDay == event.allDay && (event.timeZone.isBlank() || live.timeZone == event.timeZone)) {
                "원본 반복 일정이 변경됐어요. 새로고침 후 다시 확인해 주세요."
            }
        } else check(!live.recurring) { "원본 일정이 반복 일정으로 변경됐어요. 새로고침 후 다시 확인해 주세요." }
        return live
    }

    private fun recurrenceDuration(draft: EventDraft): String {
        val millis = max(1L, draft.endMillis - draft.startMillis)
        if (draft.allDay) return "P${max(1L, Duration.ofMillis(millis).toDays())}D"
        return "PT${max(1L, Duration.ofMillis(millis).seconds)}S"
    }

    companion object {
        private val EVENT_COLUMNS = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE,
            CalendarContract.Events.EXRULE,
            CalendarContract.Events.EXDATE,
            CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.ALL_DAY,
        )

        internal fun durationMillis(value: String): Long? = runCatching {
            if (value.isBlank()) return null
            if (value.matches(Regex("P\\d+W"))) return value.removePrefix("P").removeSuffix("W").toLong() * 7L * 86_400_000L
            val duration = Duration.parse(value)
            duration.toMillis()
        }.getOrNull()
    }
}
