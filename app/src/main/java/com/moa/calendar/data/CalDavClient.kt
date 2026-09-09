package com.moa.calendar.data

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class DavResource(val href: String, val etag: String, val ics: String)

class CalDavClient(
    server: String,
    private val username: String,
    private val password: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build(),
    private val allowLocalHttpForTests: Boolean = false,
) {
    private val root = server.trim().toHttpUrl()
    init {
        require(root.isHttps || (allowLocalHttpForTests && root.host == "localhost")) { "HTTPS 서버 주소가 필요합니다." }
        require(root.username.isEmpty() && root.password.isEmpty()) { "서버 주소에 로그인 정보를 넣을 수 없습니다." }
        require(username.isNotBlank() && password.isNotBlank()) { "아이디와 비밀번호를 입력해 주세요." }
    }

    fun discover(): List<CalendarInfo> {
        val first = propfind(root, "0")
        val principalHref = propertyHref(first, "current-user-principal")
        val principal = principalHref?.let { safeUrl(root, it) } ?: root
        val principalProps = if (principal != root) propfind(principal, "0") else first
        val homeHref = propertyHref(principalProps, "calendar-home-set")
            ?: propertyHref(first, "calendar-home-set")
        val home = homeHref?.let { safeUrl(principal, it) } ?: principal
        // A partially failed listing cannot confirm that cached calendars were removed.
        val responses = parseResponses(propfind(home, "1"), strict = true)
        val calendars = responses.mapNotNull { (href, prop) ->
            if (prop.getElementsByTagNameNS(CAL, "calendar").length == 0) return@mapNotNull null
            val url = safeUrl(home, href)
            val componentSets = prop.getElementsByTagNameNS(CAL, "supported-calendar-component-set")
            val supported = if (componentSets.length > 0) {
                val components = (componentSets.item(0) as Element).getElementsByTagNameNS(CAL, "comp")
                (0 until components.length).map { (components.item(it) as Element).getAttribute("name").uppercase() }.toSet()
            } else setOf("VEVENT", "VTODO") // RFC 4791 §5.2.3: absent means all component types.
            if (supported.none { it == "VEVENT" || it == "VTODO" }) return@mapNotNull null
            val privileges = prop.getElementsByTagNameNS(DAV, "privilege")
            val rights = (0 until privileges.length).flatMap { i ->
                val nodes = privileges.item(i).childNodes
                (0 until nodes.length).mapNotNull { (nodes.item(it) as? Element)?.localName }
            }.toSet()
            val writable = "all" in rights || "write" in rights || rights.containsAll(setOf("write-content", "bind", "unbind"))
            CalendarInfo(url.toString(), prop.text(DAV, "displayname").ifBlank { "네이버 캘린더" },
                username, CalendarSource.NAVER, CalendarSource.NAVER.displayColor(0), writable,
                supportsEvents = "VEVENT" in supported, supportsTasks = "VTODO" in supported)
        }.distinctBy { it.id }
        if (calendars.isEmpty()) throw IOException("연결 가능한 캘린더를 찾지 못했어요. 네이버 Android CalDAV 호환 여부를 확인해 주세요.")
        return calendars
    }

    fun fetch(calendar: CalendarInfo, from: Long, to: Long): List<DavResource> {
        if (!calendar.supportsEvents) return emptyList()
        val format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        return fetchComponent(calendar, """<c:comp-filter name="VEVENT"><c:time-range start="${format.format(Instant.ofEpochMilli(from))}" end="${format.format(Instant.ofEpochMilli(to))}"/></c:comp-filter>""")
    }

    fun fetchTasks(calendar: CalendarInfo): List<DavResource> {
        if (!calendar.supportsTasks) return emptyList()
        // No time range: tasks can be overdue or have no DTSTART/DUE at all.
        return fetchComponent(calendar, """<c:comp-filter name="VTODO"/>""")
    }

    private fun fetchComponent(calendar: CalendarInfo, filter: String): List<DavResource> {
        val body = """<?xml version="1.0" encoding="UTF-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop><d:getetag/><c:calendar-data/></d:prop>
              <c:filter><c:comp-filter name="VCALENDAR">$filter</c:comp-filter></c:filter>
            </c:calendar-query>""".trimIndent()
        val url = safeUrl(root, calendar.id)
        val xml = request(url, "REPORT", body, mapOf("Depth" to "1")).first
        return parseResponses(xml, strict = true).mapNotNull { (href, prop) ->
            val resourceUrl = safeUrl(url, href)
            // Some servers include the queried collection alongside its event resources.
            // A collection has no calendar-data and must not be treated as a failed event.
            val isQueriedCollection = resourceUrl.encodedPath.trimEnd('/') == url.encodedPath.trimEnd('/') && resourceUrl.query == url.query
            val resourceType = prop.getElementsByTagNameNS(DAV, "resourcetype").item(0) as? Element
            if (isQueriedCollection || resourceType?.getElementsByTagNameNS(DAV, "collection")?.length?.let { it > 0 } == true) return@mapNotNull null
            val ics = prop.text(CAL, "calendar-data")
            if (ics.isNotBlank()) DavResource(resourceUrl.toString(), prop.text(DAV, "getetag"), ics)
            else downloadResource(resourceUrl)
        }
    }

    private fun downloadResource(url: HttpUrl): DavResource {
        // CalDAV resources can be read by GET when REPORT supplies only href/getetag.
        val (ics, etag) = request(url, "GET", null, mapOf("Accept" to "text/calendar"))
        val content = ics.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!content.lineSequence().first().trim().equals("BEGIN:VCALENDAR", ignoreCase = true) ||
            !content.trimEnd().endsWith("END:VCALENDAR", ignoreCase = true)) {
            throw IOException("일정 파일을 가져오지 못했어요. 서버가 올바른 캘린더 본문을 반환하지 않았습니다.")
        }
        // Use the version returned with this body, never an older listing's ETag.
        // An absent ETag keeps the event readable but blocks unsafe writes.
        return DavResource(url.toString(), etag, ics)
    }

    fun put(href: String, ics: String, etag: String? = null): String {
        if (etag != null) require(etag.isNotBlank() && !etag.startsWith("W/")) { "안전한 수정을 위한 서버 버전 정보가 없어요. 새로고침해 주세요." }
        val headers = if (etag == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to etag)
        return request(safeUrl(root, href), "PUT", ics, headers, "text/calendar; charset=utf-8").second
    }

    fun delete(href: String, etag: String) {
        require(etag.isNotBlank() && !etag.startsWith("W/")) { "서버 버전 정보가 없어요. 새로고침 후 다시 시도해 주세요." }
        request(safeUrl(root, href), "DELETE", null, mapOf("If-Match" to etag))
    }

    private fun propfind(url: HttpUrl, depth: String): String = request(url, "PROPFIND", """
        <?xml version="1.0" encoding="UTF-8"?>
        <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
          <d:prop><d:current-user-principal/><c:calendar-home-set/><d:displayname/>
          <d:resourcetype/><d:current-user-privilege-set/><c:supported-calendar-component-set/></d:prop>
        </d:propfind>""".trimIndent(), mapOf("Depth" to depth)).first

    private fun request(url: HttpUrl, method: String, body: String?, headers: Map<String, String>, type: String = "application/xml; charset=utf-8", redirects: Int = 0): Pair<String, String> {
        val builder = Request.Builder().url(url).method(method, body?.toRequestBody(type.toMediaType()))
            .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
            .header("User-Agent", "MoaCalendar/0.1.4 (Android; CalDAV)")
        headers.forEach { (key, value) -> builder.header(key, value) }
        http.newCall(builder.build()).execute().use { response ->
            if (response.code in listOf(301, 302, 307, 308)) {
                if (redirects >= 3) throw IOException("서버 이동이 너무 많아요.")
                val next = response.header("Location") ?: throw IOException("서버 이동 주소가 없어요.")
                return request(safeUrl(url, next), method, body, headers, type, redirects + 1)
            }
            when (response.code) {
                401 -> throw IOException("로그인에 실패했어요. 아이디와 앱 비밀번호를 확인해 주세요.")
                403 -> throw IOException("서버가 접근을 허용하지 않아요. 네이버의 Android CalDAV 지원 제한일 수 있어요.")
                409, 412 -> throw IOException("다른 곳에서 변경된 일정이에요. 새로고침 후 다시 확인해 주세요.")
                429 -> throw IOException("요청이 많아 잠시 쉬고 있어요. 잠시 후 다시 시도해 주세요.")
            }
            if (!response.isSuccessful) throw IOException("캘린더 서버 오류 (${response.code}). 다시 시도해 주세요.")
            val source = response.body?.source()
            if (source != null && source.request(MAX_RESPONSE + 1) && source.buffer.size > MAX_RESPONSE) throw IOException("일정 응답이 너무 커요. 기간을 줄여 주세요.")
            return (source?.readUtf8() ?: "") to response.header("ETag", "").orEmpty()
        }
    }

    private fun safeUrl(base: HttpUrl, href: String): HttpUrl {
        val url = base.resolve(href) ?: throw IOException("캘린더 주소를 읽을 수 없어요.")
        if (url.scheme != root.scheme || url.host != root.host || url.port != root.port || url.username.isNotEmpty() || url.password.isNotEmpty())
            throw IOException("보안을 위해 다른 서버로 로그인 정보를 보내지 않았어요.")
        return url
    }

    companion object {
        private const val DAV = "DAV:"
        private const val CAL = "urn:ietf:params:xml:ns:caldav"
        private const val MAX_RESPONSE = 8L * 1024 * 1024

        internal fun parseResponses(xml: String, strict: Boolean = false): List<Pair<String, Element>> {
            if (xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) throw IOException("허용하지 않는 XML 형식이에요.")
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val document = builder.parse(InputSource(StringReader(xml)))
            require(document.documentElement.localName == "multistatus" && document.documentElement.namespaceURI == DAV) { "CalDAV 응답 형식이 올바르지 않아요." }
            val responses = document.getElementsByTagNameNS(DAV, "response")
            return (0 until responses.length).mapNotNull { i ->
                val response = responses.item(i) as Element
                val href = response.text(DAV, "href")
                val propstats = response.getElementsByTagNameNS(DAV, "propstat")
                val merged = document.createElementNS(DAV, "d:prop")
                var successful = false
                for (j in 0 until propstats.length) {
                    val stat = propstats.item(j) as Element
                    if (stat.text(DAV, "status").split(Regex("\\s+"), limit = 3).getOrNull(1) != "200") continue
                    successful = true
                    val props = (stat.getElementsByTagNameNS(DAV, "prop").item(0) as? Element)?.childNodes ?: continue
                    for (k in 0 until props.length) merged.appendChild(props.item(k).cloneNode(true))
                }
                if (strict && (!successful || href.isBlank())) throw IOException("서버가 일부 일정을 반환하지 않았어요. 기존 일정을 유지합니다.")
                if (successful && href.isNotBlank()) href to merged else null
            }
        }

        private fun propertyHref(xml: String, name: String): String? = parseResponses(xml).firstNotNullOfOrNull { (_, prop) ->
            val ns = if (name == "calendar-home-set") CAL else DAV
            (prop.getElementsByTagNameNS(ns, name).item(0) as? Element)?.text(DAV, "href")?.takeIf { it.isNotBlank() }
        }

        private fun Element.text(ns: String, name: String): String = getElementsByTagNameNS(ns, name).item(0)?.textContent?.trim().orEmpty()
    }
}
