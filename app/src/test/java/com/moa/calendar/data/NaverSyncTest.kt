package com.moa.calendar.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NaverSyncTest {
    private val window = SyncWindow(Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(), Instant.parse("2026-10-01T00:00:00Z").toEpochMilli())
    private val calendars = (1..4).map { CalendarInfo("https://fixture.example/cal$it/", "Fixture", "user", CalendarSource.NAVER, 0, supportsTasks = true) }
    private fun client(intercept: (Request, Boolean) -> Unit = { _, _ -> }, status: (Boolean) -> Int = { 207 }): CalDavClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val task = buffer.readUtf8().contains("VTODO")
            intercept(request, task)
            val component = if (task) "VTODO" else "VEVENT"
            val date = if (task) "" else "DTSTART:20260910T010000Z\r\n"
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:$component\r\nUID:test\r\n${date}SUMMARY:Fixture\r\nEND:$component\r\nEND:VCALENDAR"
            val body = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>${request.url}${if (task) "task" else "event"}.ics</d:href><d:propstat><d:prop><d:getetag>"v1"</d:getetag><c:calendar-data><![CDATA[$ics]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status(task)).message("Fixture")
                .body(body.toResponseBody("application/xml".toMediaType())).build()
        }.build()
        return CalDavClient("https://fixture.example/", "fixture", "fixture", http)
    }
    @Test fun `concurrent components overlap without exceeding three`() = runBlocking {
        val current = AtomicInteger(); val maximum = AtomicInteger(); val started = CountDownLatch(3)
        val client = client({ _, _ ->
            val active = current.incrementAndGet(); maximum.accumulateAndGet(active, ::maxOf)
            started.countDown()
            try {
                check(started.await(3, TimeUnit.SECONDS)) { "Independent requests were serialized." }
                Thread.sleep(30)
            } finally { current.decrementAndGet() }
        })
        val result = fetchNaverSnapshot(client, calendars, window)
        assertEquals(3, maximum.get())
        assertEquals(4, result.events.size); assertEquals(4, result.tasks.getOrThrow().size)
    }
    @Test fun `a single calendar can load events and tasks together`() = runBlocking {
        val started = CountDownLatch(2)
        val client = client({ _, _ -> started.countDown(); check(started.await(3, TimeUnit.SECONDS)) })
        val result = fetchNaverSnapshot(client, calendars.take(1), window)
        assertEquals(1, result.events.size); assertEquals(1, result.tasks.getOrThrow().size)
    }
    @Test fun `task failure still returns validated events`() = runBlocking {
        val result = fetchNaverSnapshot(client(status = { if (it) 503 else 207 }), calendars, window)
        assertEquals(4, result.events.size)
        assertTrue(result.tasks.isFailure)
    }
    @Test fun `event failure cannot produce a partial successful snapshot`() {
        assertThrows(IOException::class.java) {
            runBlocking { fetchNaverSnapshot(client(status = { if (!it) 503 else 207 }), calendars, window) }
        }
    }
    @Test fun `cancelled sync cannot return a snapshot to publish`() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        var returned = false
        val job = launch(Dispatchers.Default) {
            fetchNaverSnapshot(client({ _, _ -> started.countDown(); check(release.await(3, TimeUnit.SECONDS)) }), calendars, window)
            returned = true
        }
        check(started.await(3, TimeUnit.SECONDS))
        job.cancel(); release.countDown(); job.join()
        assertTrue(job.isCancelled); assertFalse(returned)
    }
}
