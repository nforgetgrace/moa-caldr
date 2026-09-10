package com.moa.calendar

import android.app.Instrumentation
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import com.moa.calendar.data.CalendarEvent
import com.moa.calendar.data.CalendarSource
import com.moa.calendar.data.DeviceCalendars
import com.moa.calendar.data.EventDraft
import com.moa.calendar.data.RepeatFrequency
import java.time.Instant

/** Isolated-emulator regression for Android Calendar Provider recurrence handling. */
internal class ProviderRecurrenceRegression(private val runner: Instrumentation) {
    private val context = runner.targetContext
    private val resolver = context.contentResolver
    private val account = "moa-provider-recurrence@example.test"
    private val timeZone = "Asia/Seoul"
    private val provider = DeviceCalendars(context)
    private var calendarUri: android.net.Uri? = null

    fun run() {
        val result = android.os.Bundle()
        try {
            check(!context.getSharedPreferences("moa_vault", 0).contains("encrypted")) { "Use an isolated emulator without Naver credentials." }
            check(provider.calendars().none { it.source == CalendarSource.GOOGLE && !it.account.endsWith("@example.test") }) {
                "Use an isolated emulator without personal Google calendars."
            }
            val calendarId = createCalendar()
            val calendar = provider.calendars().single { it.id == "device:$calendarId" }
            val start = Instant.parse("2026-09-10T01:00:00Z").toEpochMilli()
            val end = Instant.parse("2026-09-10T02:00:00Z").toEpochMilli()
            listOf(RepeatFrequency.DAILY, RepeatFrequency.WEEKLY, RepeatFrequency.MONTHLY, RepeatFrequency.YEARLY).forEachIndexed { index, frequency ->
                val title = "Saved ${frequency.name}"
                provider.save(EventDraft(calendar.id, title, start + index * 3_600_000L, end + index * 3_600_000L,
                    recurrenceRule = "${frequency.rule};COUNT=4", timeZone = timeZone), null)
                val saved = provider.events(listOf(calendar), start - 1, start + 5 * 366 * 86_400_000L).filter { it.title == title }
                check(saved.size == 4) { "${frequency.name} saved recurrence expanded ${saved.size} times." }
                check(saved.all { it.recurrenceRule == "${frequency.rule};COUNT=4" && !it.recurrenceReadOnly })
            }
            val eventId = createEvent(calendarId, "Repeat", start, null, "PT3600S", "FREQ=DAILY;COUNT=4", timeZone, false)

            val expanded = provider.events(listOf(calendar), start - 86_400_000L, start + 6 * 86_400_000L)
                .filter { it.id.startsWith("device:$eventId@") }
            check(expanded.size == 4) { "Expected 4 expanded recurrence instances, got ${expanded.size}." }
            check(expanded.all { it.recurring && it.recurrenceRule == "FREQ=DAILY;COUNT=4" && !it.recurrenceReadOnly })
            check(expanded.all { it.seriesStartMillis == start && it.seriesEndMillis == end && it.timeZone == timeZone })

            val later = expanded[2]
            provider.save(EventDraft(calendar.id, "Series moved", start + 3_600_000L, end + 3_600_000L,
                recurrenceRule = "FREQ=DAILY;COUNT=4", timeZone = timeZone), later)
            val moved = readEvent(eventId)
            check(moved.title == "Series moved" && moved.dtStart == start + 3_600_000L && moved.duration == "PT3600S" && moved.dtEnd == null)

            val latest = provider.events(listOf(calendar), start - 86_400_000L, start + 6 * 86_400_000L)
                .first { it.id.startsWith("device:$eventId@") }
            provider.save(EventDraft(calendar.id, "Single now", latest.seriesStartMillis!!, latest.seriesEndMillis!!,
                recurrenceRule = "", timeZone = timeZone), latest)
            val single = readEvent(eventId)
            check(single.rrule.isBlank() && single.duration.isBlank() && single.dtEnd == latest.seriesEndMillis)

            provider.save(EventDraft(calendar.id, "Yearly again", single.dtStart, single.dtEnd!!,
                recurrenceRule = "FREQ=YEARLY", timeZone = timeZone), nonRecurringEvent(calendar, eventId, single))
            val yearly = readEvent(eventId)
            check(yearly.rrule == "FREQ=YEARLY" && yearly.dtEnd == null && yearly.duration == "PT3600S")

            val yearlyInstances = provider.events(listOf(calendar), yearly.dtStart - 1, yearly.dtStart + 370 * 86_400_000L).filter { it.id.startsWith("device:$eventId@") }
            check(yearlyInstances.size == 2) { "Converted yearly missing instances: $yearly; dates=${yearlyInstances.map { it.startMillis }}" }

            val allDayStart = Instant.parse("2026-09-12T00:00:00Z").toEpochMilli()
            provider.save(EventDraft(calendar.id, "Saved all day", allDayStart, allDayStart + 2 * 86_400_000L,
                allDay = true, recurrenceRule = "FREQ=WEEKLY;COUNT=2", timeZone = "UTC"), null)
            val allDay = provider.events(listOf(calendar), allDayStart - 1, allDayStart + 15 * 86_400_000L).first { it.title == "Saved all day" }
            val allDayId = allDay.id.removePrefix("device:").substringBefore('@').toLong()
            val rawAllDay = readEvent(allDayId)
            check(allDay.allDay && allDay.seriesEndMillis == allDayStart + 2 * 86_400_000L && allDay.timeZone == "UTC")
            check(rawAllDay.allDay && rawAllDay.timeZone == "UTC" && rawAllDay.duration == "P2D" && rawAllDay.dtEnd == null)
            provider.save(EventDraft(calendar.id, "Timed from all day", allDay.seriesStartMillis!! + 9 * 3_600_000L,
                allDay.seriesStartMillis!! + 10 * 3_600_000L, allDay = false,
                recurrenceRule = allDay.recurrenceRule, timeZone = timeZone), allDay)
            val timed = readEvent(allDayId)
            check(!timed.allDay && timed.timeZone == timeZone && timed.duration == "PT3600S")

            createException(calendarId, eventId, yearly.dtStart + 365 * 86_400_000L, yearly.dtStart + 366 * 86_400_000L)
            val exceptionEvents = provider.events(listOf(calendar), yearly.dtStart - 1, yearly.dtStart + 2 * 86_400_000L)
            val readOnly = exceptionEvents.firstOrNull { it.id.startsWith("device:$eventId@") }
                ?: error("Missing master after exception: ${readEvent(eventId)}; instances=${exceptionEvents.map { it.id + ":" + it.title }}")
            check(readOnly.recurrenceReadOnly) { "Series with override was editable." }
            check(runCatching {
                provider.save(EventDraft(calendar.id, "Should fail", readOnly.seriesStartMillis!!, readOnly.seriesEndMillis!!,
                    recurrenceRule = readOnly.recurrenceRule, timeZone = readOnly.timeZone), readOnly)
            }.isFailure)

            val staleId = createEvent(calendarId, "Stale", start, null, "PT3600S", "FREQ=DAILY;COUNT=2", timeZone, false)
            val stale = provider.events(listOf(calendar), start - 1, start + 3 * 86_400_000L).first { it.id.startsWith("device:$staleId@") }
            updateRaw(staleId, ContentValues().apply { put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;COUNT=2") })
            check(runCatching {
                provider.save(EventDraft(calendar.id, "Stale edit", stale.seriesStartMillis!!, stale.seriesEndMillis!!,
                    recurrenceRule = stale.recurrenceRule, timeZone = stale.timeZone), stale)
            }.isFailure)

            result.putString("stream", "PASS: Provider recurrence save/read for all frequencies, whole-series update, nonrepeat transitions, all-day duration/timezone conversion, exception readonly and stale guard verified.\n")
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}\n")
            result.putString("failure", e.javaClass.simpleName)
        } finally {
            calendarUri?.let { resolver.delete(it, null, null) }
        }
        runner.finish(if (result.containsKey("failure")) 0 else -1, result)
    }

    private data class RawEvent(val title: String, val dtStart: Long, val dtEnd: Long?, val duration: String, val rrule: String, val timeZone: String, val allDay: Boolean)

    private fun createCalendar(): Long {
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google").build()
        calendarUri = checkNotNull(resolver.insert(uri, ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google")
            put(CalendarContract.Calendars.NAME, "Recurrence fixture")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Recurrence fixture")
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, timeZone)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
        }))
        return ContentUris.parseId(calendarUri!!)
    }

    private fun createEvent(calendarId: Long, title: String, start: Long, end: Long?, duration: String?, rule: String?, zone: String, allDay: Boolean): Long {
        val uri = checkNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            if (end != null) put(CalendarContract.Events.DTEND, end)
            if (duration != null) put(CalendarContract.Events.DURATION, duration)
            if (rule != null) put(CalendarContract.Events.RRULE, rule)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        }))
        return ContentUris.parseId(uri)
    }

    private fun createException(calendarId: Long, originalId: Long, originalTime: Long, start: Long) {
        // Model an incoming cloud exception; this app deliberately does not create per-occurrence exceptions.
        fun syncUri(uri: android.net.Uri) = uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google").build()
        check(resolver.update(syncUri(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, originalId)),
            ContentValues().apply { put(CalendarContract.Events._SYNC_ID, "fixture-master") }, null, null) == 1)
        checkNotNull(resolver.insert(syncUri(CalendarContract.Events.CONTENT_URI), ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events._SYNC_ID, "fixture-exception")
            put(CalendarContract.Events.ORIGINAL_ID, originalId)
            put(CalendarContract.Events.ORIGINAL_SYNC_ID, "fixture-master")
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, originalTime)
            put(CalendarContract.Events.ORIGINAL_ALL_DAY, 0)
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.TITLE, "Override")
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + 3_600_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
        }))
    }

    private fun readEvent(id: Long): RawEvent {
        resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.DURATION, CalendarContract.Events.RRULE, CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.ALL_DAY), null, null, null).use { cursor ->
            check(cursor != null && cursor.moveToFirst()) { "Missing raw event $id." }
            return RawEvent(cursor.getString(0).orEmpty(), cursor.getLong(1), if (cursor.isNull(2)) null else cursor.getLong(2),
                cursor.getString(3).orEmpty(), cursor.getString(4).orEmpty(), cursor.getString(5).orEmpty(), cursor.getInt(6) == 1)
        }
    }

    private fun nonRecurringEvent(calendar: com.moa.calendar.data.CalendarInfo, id: Long, raw: RawEvent) =
        CalendarEvent("device:$id@${raw.dtStart}", calendar.id, raw.title, raw.dtStart, raw.dtEnd!!, source = CalendarSource.GOOGLE,
            color = calendar.color, timeZone = timeZone)

    private fun updateRaw(id: Long, values: ContentValues) {
        check(resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values, null, null) == 1)
    }
}
