package com.moa.calendar.data

import biweekly.Biweekly
import biweekly.util.ICalDate
import java.time.LocalDate
import java.time.ZoneOffset

object TaskCodec {
    fun parse(resource: DavResource, calendar: CalendarInfo): List<CalendarTask> {
        val ical = Biweekly.parse(resource.ics).first() ?: error("할 일 형식을 읽을 수 없어요.")
        return ical.todos.filter { it.status?.isCancelled != true }.map { todo ->
            val due = todo.dateDue?.value
            val start = todo.dateStart?.value
            val duration = todo.duration?.value?.toMillis()
            val deadline = due?.let(::millis) ?: if (start != null && duration != null) millis(start) + duration else null
            CalendarTask(
                "task:${resource.href}#${todo.uid?.value.orEmpty()}#${todo.recurrenceId?.value?.time ?: "master"}", calendar.id,
                todo.summary?.value?.takeIf { it.isNotBlank() } ?: "제목 없는 할 일", todo.description?.value.orEmpty(),
                deadline, (due ?: start)?.hasTime() != true,
                todo.status?.isCompleted == true || todo.completed != null || todo.percentComplete?.value == 100,
                todo.recurrenceRule != null || todo.recurrenceDates.isNotEmpty() || todo.recurrenceId != null,
                calendar.source, calendar.color,
            )
        }
    }

    private fun millis(date: ICalDate): Long {
        if (date.hasTime()) return date.time
        val raw = date.rawComponents
        return LocalDate.of(raw.year, raw.month, raw.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}
