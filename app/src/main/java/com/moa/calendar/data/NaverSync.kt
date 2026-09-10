package com.moa.calendar.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class NaverFetchResult(
    val events: Map<CalendarInfo, List<DavResource>>,
    val tasks: Result<Map<CalendarInfo, List<DavResource>>>,
)

/** Bound server load while allowing independent event/task requests to overlap. */
internal suspend fun fetchNaverSnapshot(
    client: CalDavClient,
    calendars: List<CalendarInfo>,
    window: SyncWindow,
    cachedEvents: Map<String, List<DavResource>> = emptyMap(),
    cachedTasks: Map<String, List<DavResource>> = emptyMap(),
    parallelism: Int = 3,
): NaverFetchResult = coroutineScope {
    require(parallelism in 1..3)
    val permits = Semaphore(parallelism)
    val events = calendars.filter { it.supportsEvents }.map { calendar ->
        async(Dispatchers.IO) {
            permits.withPermit {
                calendar to client.fetch(calendar, window.from, window.to, cachedEvents[calendar.id].orEmpty()).also { resources ->
                    resources.forEach { IcsCodec.parse(it, calendar, window.from, window.to) }
                }
            }
        }
    }
    val tasks = calendars.filter { it.supportsTasks }.map { calendar ->
        async(Dispatchers.IO) {
            try {
                Result.success(permits.withPermit {
                    calendar to client.fetchTasks(calendar, cachedTasks[calendar.id].orEmpty()).also { resources ->
                        resources.forEach { TaskCodec.parse(it, calendar) }
                    }
                })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }
    }
    val eventResults = events.awaitAll().toMap()
    val taskResults = tasks.awaitAll()
    val taskError = taskResults.firstNotNullOfOrNull { it.exceptionOrNull() }
    NaverFetchResult(eventResults, if (taskError != null) Result.failure(taskError) else Result.success(taskResults.map { it.getOrThrow() }.toMap()))
}
