package com.moa.calendar.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CalDavClientTest {
    private val server = MockWebServer().apply { start() }
    private fun client() = CalDavClient(server.url("/").toString(), "user", "password", allowLocalHttpForTests = true)
    @After fun close() { server.shutdown() }
    private fun multi(href: String, properties: String) = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>$href</d:href><d:propstat><d:prop>$properties</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
    private fun reply(body: String) { server.enqueue(MockResponse().setResponseCode(207).setBody(body)) }
    private fun calendar() = CalendarInfo(server.url("/cal/").toString(), "개인", "test", CalendarSource.NAVER, 0)
    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:test\r\nDTSTART:20260909T010000Z\r\nDTEND:20260909T020000Z\r\nSUMMARY:Test\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    @Test fun `calendar collection entry is not an event with missing data`() {
        reply(multi("/cal/", ""))

        assertTrue(client().fetch(calendar(), 0, 86_400_000).isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun `etag only event listing downloads body and pairs it with fresh GET etag`() {
        val collection = multi("/cal/", "")
        val entry = multi("/cal/event.ics", "<d:getetag>\"listed-version\"</d:getetag>")
        reply(collection.replace("</d:multistatus>", entry.substringAfter(">", "").substringBeforeLast("</d:multistatus>") + "</d:multistatus>"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/calendar").setHeader("ETag", "\"current-version\"").setBody(ics))

        val event = client().fetch(calendar(), 0, 86_400_000).single()

        assertEquals(ics, event.ics)
        assertEquals("\"current-version\"", event.etag)
        assertEquals(server.url("/cal/event.ics").toString(), event.href)
        assertEquals("REPORT", server.takeRequest().method)
        val download = server.takeRequest()
        assertEquals("GET", download.method)
        assertEquals("/cal/event.ics", download.path)
        assertEquals("text/calendar", download.getHeader("Accept"))
        assertEquals(2, server.requestCount)
    }

    @Test fun `inline calendar data avoids extra requests`() {
        reply(multi("/cal/event.ics", "<d:getetag>\"v1\"</d:getetag><c:calendar-data><![CDATA[$ics]]></c:calendar-data>"))

        val event = client().fetch(calendar(), 0, 86_400_000).single()

        assertTrue(event.ics.contains("BEGIN:VEVENT"))
        assertEquals("\"v1\"", event.etag)
        assertEquals(1, server.requestCount)
    }

    @Test fun `missing GET etag cannot reuse potentially stale listing etag`() {
        reply(multi("/cal/event.ics", "<d:getetag>\"old\"</d:getetag>"))
        server.enqueue(MockResponse().setBody(ics))

        assertEquals("", client().fetch(calendar(), 0, 86_400_000).single().etag)
    }

    @Test fun `missing calendar-data property downloads individual resource`() {
        val xml = multi("/cal/event.ics", "<d:getetag>\"v1\"</d:getetag>").replace("</d:response>", "<d:propstat><d:prop><c:calendar-data/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>")
        reply(xml)
        server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody(ics))

        assertEquals(1, client().fetch(calendar(), 0, 86_400_000).size)
        assertEquals(2, server.requestCount)
    }

    @Test fun `bad downloaded body fails instead of silently dropping the event`() {
        reply(multi("/cal/event.ics", "<d:getetag>\"v1\"</d:getetag>"))
        server.enqueue(MockResponse().setBody("<html>Login required</html>"))

        try { client().fetch(calendar(), 0, 86_400_000); fail("Invalid calendar body accepted") }
        catch (e: IOException) { assertTrue(e.message.orEmpty().contains("일정 파일")) }
        assertEquals(2, server.requestCount)
    }

    @Test fun `missing event body cannot send credentials to a foreign resource`() {
        reply(multi("https://other.example/event.ics", "<d:getetag>\"v1\"</d:getetag>"))

        try { client().fetch(calendar(), 0, 86_400_000); fail("Foreign resource accepted") }
        catch (e: IOException) { assertTrue(e.message.orEmpty().contains("다른 서버")) }
        assertEquals(1, server.requestCount)
    }

    @Test fun `successful WebDAV status does not require reason phrase`() {
        reply(multi("/cal/event.ics", "<d:getetag>\"v1\"</d:getetag><c:calendar-data><![CDATA[$ics]]></c:calendar-data>").replace("HTTP/1.1 200 OK", "HTTP/1.1 200"))

        assertEquals(1, client().fetch(calendar(), 0, 86_400_000).size)
    }

    @Test fun `discovers principal home and writable event calendars`() {
        reply(multi("/", "<d:current-user-principal><d:href>/principal/u/</d:href></d:current-user-principal>"))
        reply(multi("/principal/u/", "<c:calendar-home-set><d:href>/home/u/</d:href></c:calendar-home-set>"))
        reply(multi("/home/u/personal/", "<d:displayname>개인</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:current-user-privilege-set><d:privilege><d:write/></d:privilege></d:current-user-privilege-set>"))
        val calendar = client().discover().single()
        assertEquals("개인", calendar.name)
        assertTrue(calendar.writable)
        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals("/principal/u/", server.takeRequest().path)
        assertEquals("1", server.takeRequest().getHeader("Depth"))
    }
    @Test fun `put new resource is conditional and sends calendar content type`() {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"new\""))
        assertEquals("\"new\"", client().put(server.url("/cal/a.ics").toString(), "BEGIN:VCALENDAR"))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("*", request.getHeader("If-None-Match"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("text/calendar"))
    }
    @Test fun `update sends expected etag`() {
        server.enqueue(MockResponse().setResponseCode(204))
        client().put(server.url("/cal/a.ics").toString(), "ics", "\"v1\"")
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-Match"))
    }
    @Test(expected = IOException::class) fun `conflict cannot overwrite remote event`() {
        server.enqueue(MockResponse().setResponseCode(412))
        client().put(server.url("/cal/a.ics").toString(), "ics", "\"old\"")
    }
    @Test(expected = IllegalArgumentException::class) fun `missing version cannot be used for deletion`() { client().delete(server.url("/cal/a.ics").toString(), "") }
    @Test(expected = IllegalArgumentException::class) fun `weak etag cannot be used for update`() { client().put(server.url("/cal/a.ics").toString(), "ics", "W/\"v1\"") }
    @Test fun `redirect cannot leak credentials to another host`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/calendar"))
        try { client().discover(); fail("Cross-host redirect accepted") } catch (_: IOException) { }
        assertEquals(1, server.requestCount)
    }
    @Test(expected = IOException::class) fun `discovery cannot leak credentials through foreign href`() {
        reply(multi("/", "<d:current-user-principal><d:href>https://evil.example/principal</d:href></d:current-user-principal>"))
        client().discover()
    }
    @Test(expected = IOException::class) fun `XML external entity is rejected`() {
        CalDavClient.parseResponses("<!DOCTYPE x [<!ENTITY ext SYSTEM 'file:///etc/passwd'>]><x>&ext;</x>")
    }
    @Test(expected = IllegalArgumentException::class) fun `production rejects unencrypted HTTP`() { CalDavClient("http://example.com/", "user", "pass") }
    @Test fun `unauthorized response explains authentication failure`() {
        server.enqueue(MockResponse().setResponseCode(401))
        try { client().discover(); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("로그인")) }
    }
    @Test(expected = IOException::class) fun `failed resource response cannot be treated as empty successful calendar`() {
        CalDavClient.parseResponses("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/a.ics</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response></d:multistatus>", strict = true)
    }
}
