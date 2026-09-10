package com.moa.calendar.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Controlled HTTP fixture, not a measurement of the real Naver service or mobile network. */
class CalDavPerformanceTest {
    private val window = SyncWindow(Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(), Instant.parse("2026-10-01T00:00:00Z").toEpochMilli())

    @Test fun `same snapshot costs fewer bytes and less waiting`() = runBlocking {
        MockWebServer().use { server ->
            val bytes = AtomicLong(); val bodies = AtomicLong()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val requestBody = request.body.readUtf8()
                    val task = requestBody.contains("VTODO")
                    val includeData = requestBody.contains("calendar-data")
                    val entries = (1..if (task) 20 else 60).joinToString("") { i ->
                        val component = if (task) "VTODO" else "VEVENT"
                        val date = if (task) "" else "DTSTART:20260910T010000Z\r\nDTEND:20260910T020000Z\r\n"
                        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:$component\r\nUID:${component.lowercase()}-$i\r\n${date}SUMMARY:Fixture $i\r\nDESCRIPTION:${"x".repeat(if (task) 1000 else 2000)}\r\nEND:$component\r\nEND:VCALENDAR\r\n"
                        val data = if (includeData) { bodies.incrementAndGet(); "<c:calendar-data><![CDATA[$ics]]></c:calendar-data>" } else ""
                        """<d:response><d:href>${request.path}${component.lowercase()}-$i.ics</d:href><d:propstat><d:prop><d:getetag>"v1"</d:getetag>$data</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                    }
                    val xml = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">$entries</d:multistatus>"""
                    bytes.addAndGet(xml.toByteArray().size.toLong())
                    return MockResponse().setResponseCode(207).setBody(xml).setBodyDelay(100, TimeUnit.MILLISECONDS)
                        .throttleBody(32 * 1024, 20, TimeUnit.MILLISECONDS)
                }
            }
            server.start()
            val client = CalDavClient(server.url("/").toString(), "fixture", "fixture", allowLocalHttpForTests = true)
            val calendars = (1..3).map { CalendarInfo(server.url("/cal$it/").toString(), "Fixture $it", "fixture", CalendarSource.NAVER, 0, supportsTasks = true) }
            suspend fun measure(parallelism: Int, cached: NaverFetchResult? = null): Pair<NaverFetchResult, JSONObject> {
                bytes.set(0); bodies.set(0)
                val requestCount = server.requestCount
                val began = System.nanoTime()
                val result = fetchNaverSnapshot(client, calendars, window,
                    cached?.events?.mapKeys { it.key.id }.orEmpty(), cached?.tasks?.getOrThrow()?.mapKeys { it.key.id }.orEmpty(), parallelism)
                return result to JSONObject().put("elapsed_ms", (System.nanoTime() - began) / 1_000_000)
                    .put("requests", server.requestCount - requestCount).put("body_bytes", bytes.get()).put("calendar_bodies", bodies.get())
            }
            // Same full-data requests and sequential waits as the pre-optimization path.
            val (baseline, before) = measure(1)
            val (cold, first) = measure(3)
            val (warm, unchanged) = measure(3, cold)
            assertEquals(180, baseline.events.values.sumOf { it.size })
            assertEquals(60, baseline.tasks.getOrThrow().values.sumOf { it.size })
            assertEquals(baseline, cold); assertEquals(baseline, warm)
            assertEquals(0, unchanged.getInt("calendar_bodies"))
            assertEquals(6, unchanged.getInt("requests"))
            assertTrue(unchanged.getLong("body_bytes") < before.getLong("body_bytes") / 5)
            assertTrue("Controlled parallel cold load should beat sequential load", first.getLong("elapsed_ms") < before.getLong("elapsed_ms") * .8)
            println("SYNC_BENCHMARK=" + JSONObject().put("sequential", before).put("parallel_initial", first).put("unchanged_refresh", unchanged)
                .put("fixture", "3 calendars; 180 events + 60 tasks; 100ms body delay per response; 32KiB/20ms body throttle; discovery excluded"))
        }
    }
}
