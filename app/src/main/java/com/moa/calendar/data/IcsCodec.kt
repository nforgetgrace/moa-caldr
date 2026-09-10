package com.moa.calendar.data

import biweekly.Biweekly
import biweekly.ICalVersion
import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.io.TimezoneInfo
import biweekly.io.WriteContext
import biweekly.io.scribe.property.RecurrenceRuleScribe
import biweekly.property.DateEnd
import biweekly.property.DateStart
import biweekly.property.DurationProperty
import biweekly.property.RecurrenceRule
import biweekly.util.Frequency
import biweekly.util.ICalDate
import biweekly.util.Recurrence
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone
import java.util.UUID

object IcsCodec {
    fun parse(resource: DavResource, calendar: CalendarInfo, from: Long, to: Long): List<CalendarEvent> {
        val ical = Biweekly.parse(resource.ics).first() ?: error("일정 형식을 읽을 수 없어요.")
        val results = mutableListOf<CalendarEvent>()
        val exceptions = ical.events.filter { it.recurrenceId != null }
        require(!resource.ics.contains("RANGE=THISANDFUTURE", true)) { "이후 전체 변경이 있는 반복 일정은 원본 캘린더에서 확인해 주세요." }
        for (event in ical.events) {
            if (event.status?.isCancelled == true) continue
            val dateStart = event.dateStart ?: error("시작 시간이 없는 일정이에요.")
            val date = dateStart.value
            val allDay = !date.hasTime()
            val start = millis(date)
            val end = event.dateEnd?.value?.let(::millis)
                ?: (start + (event.duration?.value?.toMillis() ?: if (allDay) 86_400_000 else 0))
            val duration = (end - start).coerceAtLeast(if (allDay) 86_400_000 else 0)
            val uid = event.uid?.value ?: resource.href
            val recurrenceRule = event.recurrenceRule?.value?.let(::formatRecurrence).orEmpty()
            val recurring = event.recurrenceRule != null || event.recurrenceDates.isNotEmpty() || event.recurrenceId != null
            val hasOverrides = exceptions.any { it.uid?.value == uid }
            val recurrenceReadOnly = event.recurrenceId != null || ical.events.size > 1 || hasOverrides || event.recurrenceDates.isNotEmpty() ||
                event.exceptionDates.isNotEmpty() || event.exceptionRules.isNotEmpty()
            val zone = if (allDay) TimeZone.getDefault() else
                ical.timezoneInfo.getTimezone(dateStart)?.timeZone
                    ?: if (ical.timezoneInfo.isFloating(dateStart)) TimeZone.getDefault() else TimeZone.getTimeZone("UTC")
            fun append(at: Long) {
                if (at >= to || at + duration.coerceAtLeast(1) <= from) return
                results += CalendarEvent(
                    id = "${resource.href}#$uid@$at", calendarId = calendar.id,
                    title = event.summary?.value?.ifBlank { "제목 없는 일정" } ?: "제목 없는 일정",
                    startMillis = at, endMillis = at + duration, allDay = allDay,
                    description = event.description?.value.orEmpty(), location = event.location?.value.orEmpty(),
                    source = calendar.source, color = calendar.color, recurring = recurring,
                    href = resource.href, etag = resource.etag, rawIcs = resource.ics,
                    recurrenceRule = recurrenceRule,
                    seriesStartMillis = if (recurring && !recurrenceReadOnly) start else null,
                    seriesEndMillis = if (recurring && !recurrenceReadOnly) end else null,
                    timeZone = if (allDay) "UTC" else zone.id,
                    recurrenceReadOnly = recurring && recurrenceReadOnly,
                )
            }
            if (!recurring || event.recurrenceId != null) {
                append(start)
            } else {
                val overridden = exceptions.filter { it.uid?.value == uid }.map { millis(it.recurrenceId.value) }.toSet()
                // DATE values are parsed as floating midnight in the device zone.
                // Expand there, then normalize each occurrence to a UTC date boundary.
                val iterator = event.getDateIterator(zone)
                iterator.advanceTo(Date(from - duration))
                var count = 0
                while (iterator.hasNext()) {
                    require(count++ < 10_000) { "반복 일정이 너무 많아요. 조회 기간을 줄여 주세요." }
                    val next = iterator.next()
                    val at = if (allDay) {
                        val local = next.toInstant().atZone(zone.toZoneId()).toLocalDate()
                        local.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    } else next.time
                    if (at >= to) break
                    if (at !in overridden) append(at)
                }
            }
        }
        return results.sortedBy { it.startMillis }
    }

    fun write(draft: EventDraft, existing: CalendarEvent? = null, uid: String = UUID.randomUUID().toString()): String {
        validateDraft(draft)
        require(existing?.recurrenceReadOnly != true) { "개별 변경·예외가 있는 반복 일정은 원본 캘린더에서 수정해 주세요." }
        val ical = existing?.rawIcs?.takeIf { it.isNotBlank() }?.let { Biweekly.parse(it).first() } ?: ICalendar()
        require(ical.events.size <= 1) { "여러 일정이 묶인 항목은 원본에서 수정해 주세요." }
        val event = ical.events.firstOrNull() ?: VEvent().also { it.setUid(uid); ical.addEvent(it) }
        require(event.attendees.isEmpty() && event.organizer == null) { "초대 일정은 원본 캘린더에서 수정해 주세요." }
        require(event.recurrenceId == null) { "반복 일정의 개별 회차는 원본 캘린더에서 수정해 주세요." }
        require(event.recurrenceDates.isEmpty() && event.exceptionDates.isEmpty() && event.exceptionRules.isEmpty()) {
            "개별 변경·예외가 있는 반복 일정은 원본 캘린더에서 수정해 주세요."
        }
        if (existing?.recurring == true) {
            val sourceStart = event.dateStart?.value?.let(::millis)
            val sourceEnd = event.dateEnd?.value?.let(::millis)
                ?: sourceStart?.let { it + (event.duration?.value?.toMillis() ?: if (existing.allDay) 86_400_000 else 0) }
            require(existing.seriesStartMillis == sourceStart && existing.seriesEndMillis == sourceEnd &&
                existing.allDay != event.dateStart?.value?.hasTime() &&
                existing.recurrenceRule == event.recurrenceRule?.value?.let(::formatRecurrence)) {
                "원본 반복 일정이 변경됐어요. 새로고침 후 다시 확인해 주세요."
            }
        }
        event.setSummary(draft.title.trim())
        event.setDescription(draft.description)
        event.setLocation(draft.location)
        if (draft.allDay) {
            fun date(value: Long) = ICalDate(biweekly.util.DateTimeComponents(
                Date(value), TimeZone.getTimeZone("UTC")), false)
            event.setDateStart(DateStart(date(draft.startMillis)))
            event.setDateEnd(DateEnd(date(draft.endMillis)))
        } else {
            val zone = TimeZone.getTimeZone(existing?.takeUnless { it.allDay }?.timeZone?.takeIf { it.isNotBlank() }
                ?: draft.timeZone.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id)
            val start = DateStart(Date(draft.startMillis))
            val end = DateEnd(Date(draft.endMillis))
            if (zone.id != "UTC") {
                start.setParameter("TZID", zone.id)
                end.setParameter("TZID", zone.id)
                ical.timezoneInfo.setTimezone(start, biweekly.io.TimezoneAssignment(zone, zone.id))
                ical.timezoneInfo.setTimezone(end, biweekly.io.TimezoneAssignment(zone, zone.id))
            }
            event.setDateStart(start)
            event.setDateEnd(end)
        }
        if (draft.recurrenceRule.isBlank()) event.setRecurrenceRule(null as biweekly.property.RecurrenceRule?)
        else if (draft.recurrenceRule != existing?.recurrenceRule || event.recurrenceRule == null) {
            require(RepeatFrequency.fromRule(draft.recurrenceRule) != null) { "사용자 지정 반복은 원본 캘린더에서 수정해 주세요." }
            event.setRecurrenceRule(recurrenceFromRule(draft.recurrenceRule))
        }
        event.setDuration(null as DurationProperty?)
        event.setLastModified(Date())
        event.setDateTimeStamp(Date())
        event.incrementSequence()
        if (existing == null) ical.setProductId("-//MOA//Calendar 0.1//KO")
        return Biweekly.write(ical).go()
    }

    private fun millis(date: ICalDate): Long {
        if (date.hasTime()) return date.time
        val raw = date.rawComponents
        return LocalDate.of(raw.year, raw.month, raw.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    private fun recurrenceFromRule(rule: String): Recurrence =
        Recurrence.Builder(Frequency.valueOf(rule.substringAfter("FREQ=").substringBefore(';').uppercase())).build()

    private fun formatRecurrence(recurrence: Recurrence): String =
        RecurrenceRuleScribe().writeText(RecurrenceRule(recurrence), WriteContext(ICalVersion.V2_0, TimezoneInfo(), null))
}

fun validateDraft(draft: EventDraft) {
    require(draft.title.isNotBlank()) { "일정 제목을 입력해 주세요." }
    require(draft.title.length <= 500) { "제목은 500자까지 입력할 수 있어요." }
    require(draft.endMillis > draft.startMillis) { "종료 시간은 시작 시간보다 늦어야 해요." }
    if (draft.allDay) require(draft.startMillis % 86_400_000L == 0L && draft.endMillis % 86_400_000L == 0L) { "종일 일정의 날짜를 확인해 주세요." }
    if (draft.timeZone.isNotBlank()) require(runCatching { ZoneId.of(draft.timeZone) }.isSuccess) { "시간대 정보를 확인해 주세요." }
    require(draft.recurrenceRule.isBlank() || draft.recurrenceRule.split(';').any {
        it.uppercase() in setOf("FREQ=SECONDLY", "FREQ=MINUTELY", "FREQ=HOURLY", "FREQ=DAILY", "FREQ=WEEKLY", "FREQ=MONTHLY", "FREQ=YEARLY")
    }) {
        "반복 설정을 확인해 주세요."
    }
}
