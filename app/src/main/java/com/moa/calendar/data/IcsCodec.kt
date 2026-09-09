package com.moa.calendar.data

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.util.ICalDate
import java.time.LocalDate
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
            val recurring = event.recurrenceRule != null || event.recurrenceDates.isNotEmpty() || event.recurrenceId != null
            fun append(at: Long) {
                if (at >= to || at + duration.coerceAtLeast(1) <= from) return
                results += CalendarEvent(
                    id = "${resource.href}#$uid@$at", calendarId = calendar.id,
                    title = event.summary?.value?.ifBlank { "제목 없는 일정" } ?: "제목 없는 일정",
                    startMillis = at, endMillis = at + duration, allDay = allDay,
                    description = event.description?.value.orEmpty(), location = event.location?.value.orEmpty(),
                    source = calendar.source, color = calendar.color, recurring = recurring,
                    href = resource.href, etag = resource.etag, rawIcs = resource.ics,
                )
            }
            if (!recurring || event.recurrenceId != null) {
                append(start)
            } else {
                val overridden = exceptions.filter { it.uid?.value == uid }.map { millis(it.recurrenceId.value) }.toSet()
                // DATE values are parsed as floating midnight in the device zone.
                // Expand there, then normalize each occurrence to a UTC date boundary.
                val zone = if (allDay) TimeZone.getDefault() else
                    ical.timezoneInfo.getTimezone(dateStart)?.timeZone
                        ?: if (ical.timezoneInfo.isFloating(dateStart)) TimeZone.getDefault() else TimeZone.getTimeZone("UTC")
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
        require(existing?.recurring != true) { "반복 일정 변경은 원본 캘린더에서 해 주세요." }
        val ical = existing?.rawIcs?.takeIf { it.isNotBlank() }?.let { Biweekly.parse(it).first() } ?: ICalendar()
        require(ical.events.size <= 1) { "여러 일정이 묶인 항목은 원본에서 수정해 주세요." }
        val event = ical.events.firstOrNull() ?: VEvent().also { it.setUid(uid); ical.addEvent(it) }
        require(event.attendees.isEmpty() && event.organizer == null) { "초대 일정은 원본 캘린더에서 수정해 주세요." }
        event.setSummary(draft.title.trim())
        event.setDescription(draft.description)
        event.setLocation(draft.location)
        if (draft.allDay) {
            fun date(value: Long) = ICalDate(biweekly.util.DateTimeComponents(
                Date(value), TimeZone.getTimeZone("UTC")), false)
            event.setDateStart(biweekly.property.DateStart(date(draft.startMillis)))
            event.setDateEnd(biweekly.property.DateEnd(date(draft.endMillis)))
        } else {
            event.setDateStart(Date(draft.startMillis))
            event.setDateEnd(Date(draft.endMillis))
        }
        event.setDuration(null as biweekly.property.DurationProperty?)
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
}

fun validateDraft(draft: EventDraft) {
    require(draft.title.isNotBlank()) { "일정 제목을 입력해 주세요." }
    require(draft.title.length <= 500) { "제목은 500자까지 입력할 수 있어요." }
    require(draft.endMillis > draft.startMillis) { "종료 시간은 시작 시간보다 늦어야 해요." }
    if (draft.allDay) require(draft.startMillis % 86_400_000L == 0L && draft.endMillis % 86_400_000L == 0L) { "종일 일정의 날짜를 확인해 주세요." }
}
