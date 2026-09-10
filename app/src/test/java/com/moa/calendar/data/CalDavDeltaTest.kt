package com.moa.calendar.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CalDavDeltaTest {
    private val server = MockWebServer().apply { start() }
    private val client = CalDavClient(server.url("/").toString(), "fixture", "fixture", allowLocalHttpForTests = true)
    private val calendar = CalendarInfo(server.url("/cal/").toString(), "Fixture", "fixture", CalendarSource.NAVER, 0, supportsTasks = true)
    @After fun close() { server.shutdown() }
    private fun ics(title: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:$title\r\nDTSTART:20260910T010000Z\r\nSUMMARY:$title\r\nEND:VEVENT\r\nEND:VCALENDAR"
    private fun cached(name: String, etag: String = "\"v1\"") = DavResource(server.url("/cal/$name.ics").toString(), etag, ics(name))
    private fun entry(name: String, etag: String = "\"v1\"", body: String? = null): String =
        """<d:response><d:href>/cal/$name.ics</d:href><d:propstat><d:prop><d:resourcetype/><d:getetag>$etag</d:getetag>${body?.let { "<c:calendar-data><![CDATA[$it]]></c:calendar-data>" }.orEmpty()}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
    private fun reply(vararg entries: String) { server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">${entries.joinToString("")}</d:multistatus>""")) }
    private fun get(body: String, etag: String = "\"v2\"") { server.enqueue(MockResponse().setHeader("ETag", etag).setBody(body)) }
    private fun fetch(vararg old: DavResource) = client.fetch(calendar, 0, 86_400_000, old.toList())

    @Test fun `unchanged strong etags reuse bodies and remove confirmed deletions`() {
        reply(entry("a"), entry("b"))
        val result = fetch(cached("a"), cached("b"), cached("deleted"))
        assertEquals(listOf(cached("a"), cached("b")), result)
        val request = server.takeRequest()
        assertTrue(request.body.readUtf8().let { it.contains("getetag") && !it.contains("calendar-data") })
        assertEquals(1, server.requestCount)
    }
    @Test fun `single changed body uses fresh GET etag while others are reused`() {
        reply(entry("a"), entry("b", "\"listed-v2\""))
        get(ics("changed"), "\"get-v3\"")
        val result = fetch(cached("a"), cached("b"))
        assertEquals(cached("a"), result[0])
        assertEquals(ics("changed"), result[1].ics)
        assertEquals("\"get-v3\"", result[1].etag)
        server.takeRequest()
        assertEquals("GET", server.takeRequest().method)
        assertEquals(2, server.requestCount)
    }
    @Test fun `new resources and changed resources are retrieved together`() {
        reply(entry("a"), entry("b", "\"v2\""), entry("new"))
        reply(entry("new", body = ics("new")), entry("b", "\"v3\"", ics("edited")))
        val result = fetch(cached("a"), cached("b"), cached("deleted"))
        assertEquals(listOf("a", "edited", "new"), result.map { it.ics.substringAfter("SUMMARY:").substringBefore("\n").trimEnd('\r') })
        assertEquals("\"v3\"", result[1].etag)
        server.takeRequest()
        val batch = server.takeRequest()
        val xml = batch.body.readUtf8()
        assertTrue(xml.contains("calendar-multiget") && xml.contains("/cal/b.ics") && xml.contains("/cal/new.ics"))
        assertFalse(xml.contains("/cal/a.ics") || xml.contains("/cal/deleted.ics"))
        assertNull(batch.getHeader("Depth"))
        assertEquals(2, server.requestCount)
    }
    @Test fun `missing and weak server etags are never treated as unchanged`() {
        for (etag in listOf("", "W/\"v1\"")) {
            reply(entry("a"), entry("b", etag))
            get(ics("new-b"))
            val result = fetch(cached("a"), cached("b", etag))
            assertEquals(ics("new-b"), result[1].ics)
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun `cache without strong etags uses full query for compatibility`() {
        reply(entry("a", body = ics("new-a")))
        assertEquals(ics("new-a").replace("\r\n", "\n"), fetch(cached("a", "")).single().ics)
        assertTrue(server.takeRequest().body.readUtf8().contains("calendar-data"))
    }
    @Test fun `empty cached body is fetched even when etag matches`() {
        reply(entry("a"), entry("b")); get(ics("b"))
        assertEquals(ics("b"), fetch(cached("a"), cached("b").copy(ics = "")).last().ics)
        assertEquals(2, server.requestCount)
    }
    @Test fun `unsupported multiget falls back to one full query`() {
        for (status in listOf(400, 403, 405, 501)) {
            reply(entry("a", "\"v2\""), entry("new"))
            server.enqueue(MockResponse().setResponseCode(status))
            reply(entry("a", "\"v2\"", ics("edited")), entry("new", body = ics("new")))
            assertEquals(2, fetch(cached("a")).size)
            server.takeRequest(); server.takeRequest()
            assertTrue(server.takeRequest().body.readUtf8().let { it.contains("calendar-query") && it.contains("calendar-data") })
        }
        assertEquals(12, server.requestCount)
    }
    @Test fun `unsupported metadata query falls back to full data`() {
        server.enqueue(MockResponse().setResponseCode(400)); reply(entry("a", body = ics("a")))
        assertEquals(ics("a").replace("\r\n", "\n"), fetch(cached("a")).single().ics)
        assertEquals(2, server.requestCount)
    }
    @Test fun `missing multiget body falls back to GET with its own etag`() {
        reply(entry("a", "\"v2\""), entry("new")); reply(entry("a", "\"v2\""), entry("new", body = ics("new")))
        get(ics("a-new"), "\"v3\"")
        assertEquals("\"v3\"", fetch(cached("a")).first().etag)
        assertEquals(3, server.requestCount)
    }
    @Test fun `incomplete multiget cannot drop a missing resource`() {
        reply(entry("a", "\"v2\""), entry("new")); reply(entry("a", body = ics("new-a")))
        assertThrows(IOException::class.java) { fetch(cached("a")) }
        assertEquals(2, server.requestCount)
    }
    @Test fun `duplicate href or failed entry cannot confirm deletion`() {
        reply(entry("a"), entry("a"))
        assertThrows(IOException::class.java) { fetch(cached("a")) }
        reply(entry("a"), "<d:response><d:href>/cal/failed.ics</d:href><d:status>HTTP/1.1 503 Unavailable</d:status></d:response>")
        assertThrows(IOException::class.java) { fetch(cached("a")) }
    }
    @Test fun `foreign multiget href is rejected without following it`() {
        reply(entry("a", "\"v2\""), entry("new"))
        reply(entry("a", body = ics("a")).replace("/cal/a.ics", "https://evil.example/a.ics"), entry("new", body = ics("new")))
        assertThrows(IOException::class.java) { fetch(cached("a")) }
        assertEquals(2, server.requestCount)
    }
    @Test fun `deletion during body fetch fails then next complete listing clears it`() {
        reply(entry("a", "\"v2\"")); server.enqueue(MockResponse().setResponseCode(404))
        assertThrows(IOException::class.java) { fetch(cached("a")) }
        reply()
        assertTrue(fetch(cached("a")).isEmpty())
        assertEquals(3, server.requestCount)
    }
    @Test fun `authentication and throttling errors do not trigger fallback downloads`() {
        for (code in listOf(401, 403, 429, 503)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertThrows(IOException::class.java) { fetch(cached("a")) }
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun `expanded time window still lists and retrieves newly covered events`() {
        reply(entry("a"), entry("older")); get(ics("older"))
        val result = client.fetch(calendar, -86_400_000, 172_800_000, listOf(cached("a")))
        assertEquals(2, result.size)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("19691231T000000Z") && body.contains("19700103T000000Z"))
    }
    @Test fun `undated task cache uses the same etag optimization without a time range`() {
        val task = cached("todo").copy(ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:todo\r\nSUMMARY:Undated\r\nEND:VTODO\r\nEND:VCALENDAR")
        reply(entry("todo"))
        assertEquals(listOf(task), client.fetchTasks(calendar, listOf(task)))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("VTODO")); assertFalse(body.contains("time-range") || body.contains("calendar-data"))
    }
    @Test fun `batch retrieval stays bounded to fifty resources per request`() {
        reply(*(1..101).map { entry("new$it") }.toTypedArray())
        (1..101).toList().chunked(50).forEach { batch -> reply(*batch.map { entry("new$it", body = ics("new$it")) }.toTypedArray()) }
        assertEquals(101, fetch(cached("old")).size)
        server.takeRequest()
        repeat(3) { assertTrue(Regex("<d:href>").findAll(server.takeRequest().body.readUtf8()).count() <= 50) }
        assertEquals(4, server.requestCount)
    }
    @Test fun `missing etag property still downloads body from successful resource type`() {
        val missing = entry("b").replace("<d:getetag>\"v1\"</d:getetag>", "")
            .replace("</d:response>", "<d:propstat><d:prop><d:getetag/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>")
        reply(entry("a"), missing); get(ics("updated-b"))
        assertEquals(ics("updated-b"), fetch(cached("a"), cached("b")).last().ics)
    }

}
