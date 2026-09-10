package com.moa.calendar

import android.app.Activity
import android.app.Instrumentation
import android.app.job.JobScheduler
import android.content.Intent
import android.content.ContentValues
import android.content.ContentUris
import android.provider.CalendarContract
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.moa.calendar.data.CalDavClient
import com.moa.calendar.data.CalendarRepository
import com.moa.calendar.data.DeviceCalendars
import com.moa.calendar.data.EventDraft
import com.moa.calendar.ui.SyncRefreshAction
import com.moa.calendar.ui.EventEditor
import com.moa.calendar.ui.MoaTheme
import com.moa.calendar.ui.NaverDialog
import com.moa.calendar.widget.CalendarSyncJob
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Isolated-emulator regression: real repository/Keystore/dialog, synthetic HTTPS responses. */
internal class LoginRegression(private val runner: Instrumentation) {
    private val context = runner.targetContext
    private val http = FixtureHttp()
    private val repository = CalendarRepository(context) { server, user, password ->
        CalDavClient(server, user, password, OkHttpClient.Builder().addInterceptor(http).build())
    }
    private val from = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val to = from + 86_400_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activity: MainActivity? = null
    private var fixtureCalendar: android.net.Uri? = null

    fun run() {
        val result = Bundle()
        val prefs = context.getSharedPreferences("moa_calendar", 0)
        val original = prefs.all
        var safeToRestore = false
        try {
            check(!repository.naverConnected()) { "Use an isolated emulator without Naver credentials." }
            safeToRestore = true
            activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            var sync: Deferred<Unit>? = null
            http.blockReports = true
            runner.runOnMainSync {
                activity!!.setContent {
                    MoaTheme {
                        var open by remember { mutableStateOf(true) }
                        if (open) NaverDialog(onDismiss = { open = false }) { user, password ->
                            repository.connectNaver(user, password, FixtureHttp.SERVER)
                            open = false
                            // This scope survives dismissal of NaverDialog and leaving the activity.
                            sync = scope.async { repository.load(from, to, initialOnly = true); Unit }
                        } else androidx.compose.foundation.layout.Column {
                            Text("로그인 완료")
                            val running by CalendarRepository.naverSyncing.collectAsState()
                            SyncRefreshAction(running) {}
                        }
                    }
                }
            }
            setField("네이버 아이디", "fixture-user")
            setField("비밀번호 또는 앱 비밀번호", "fixture-password")
            click("연결하기")
            check(http.reportEntered.await(10, TimeUnit.SECONDS)) { "Initial sync did not start." }
            await("로그인 완료")
            await("동기화 중")
            check(CalendarRepository.naverSyncing.value)
            check(find(root(), "네이버 캘린더 연결") == null) { "Login dialog still open during REPORT." }
            check(repository.initialNaverSyncPending())
            check(prefs.getLong("last_sync", 0) == 0L)
            runBlocking {
                val cached = withTimeout(1500) { repository.load(from, to, false) }
                check(cached.calendars.size == 1 && cached.initialNaverSync)
            }
            pass("verified login closes the real dialog while the first REPORT remains blocked; cached reads stay responsive")
            // A slow background REPORT must not block either editor's foreground save.
            val naverCalendar = runBlocking { repository.load(from, to, false).calendars.single() }
            fun saveThroughEditor(calendar: com.moa.calendar.data.CalendarInfo, title: String) {
                runner.runOnMainSync {
                    activity!!.setContent { MoaTheme { key(calendar.id) {
                        var open by remember { mutableStateOf(true) }
                        if (open) EventEditor(LocalDate.now(), listOf(calendar), null, onDismiss = { open = false },
                            onSave = { draft -> repository.save(draft); open = false }, onDelete = {})
                        else Text("저장 완료")
                    } } }
                }
                setField("일정 제목", title)
                val began = SystemClock.uptimeMillis()
                click("일정 저장")
                await("저장 완료")
                check(SystemClock.uptimeMillis() - began < 2500) { "Save waited for background REPORT." }
                check(http.releaseReports.count == 1L) { "Save was not tested during blocked REPORT." }
            }
            saveThroughEditor(naverCalendar, "NaverSavedDuringSync")
            val fixtureAccount = "moa-save-fixture@example.test"
            val syncUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, fixtureAccount)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google").build()
            fixtureCalendar = checkNotNull(context.contentResolver.insert(syncUri, ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, fixtureAccount)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google")
                put(CalendarContract.Calendars.NAME, "Save fixture")
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Save fixture")
                put(CalendarContract.Calendars.OWNER_ACCOUNT, fixtureAccount)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.VISIBLE, 1)
            }))
            val deviceId = "device:${ContentUris.parseId(fixtureCalendar!!)}"
            val googleCalendar = DeviceCalendars(context).calendars().single { it.id == deviceId }
            saveThroughEditor(googleCalendar, "GoogleSavedDuringSync")
            runBlocking {
                val saved = repository.load(from, to, false)
                check(saved.events.any { it.title == "NaverSavedDuringSync" })
                check(saved.events.any { it.title == "GoogleSavedDuringSync" })
            }
            fun editThroughEditor(calendar: com.moa.calendar.data.CalendarInfo, previousTitle: String) {
                val before = runBlocking { repository.load(from, to, false) }
                val existing = before.events.single { it.title == previousTitle }
                val title = previousTitle.replace("Saved", "Edited")
                var mount = 0
                fun open() {
                    val instance = ++mount
                    runner.runOnMainSync {
                        activity!!.setContent { MoaTheme { key(calendar.id, instance) {
                            var open by remember { mutableStateOf(true) }
                            if (open) EventEditor(LocalDate.now(), listOf(calendar), existing, onDismiss = { open = false },
                                onSave = { draft -> repository.save(draft, existing); open = false }, onDelete = {})
                            else Text("수정 창 닫힘")
                        } } }
                    }
                    await("일정 수정")
                    await(previousTitle)
                }
                open()
                setField("일정 제목", "Discarded edit")
                click("닫기"); await("수정 창 닫힘")
                check(runBlocking { repository.load(from, to, false) }.events == before.events) { "Cancel changed events." }
                open()
                setField("일정 제목", "")
                click("변경사항 저장"); await("일정 제목을 입력해 주세요.")
                check(runBlocking { repository.load(from, to, false) }.events == before.events)
                setField("일정 제목", title)
                setField("장소", "수정한 장소")
                setField("메모", "수정한 메모")
                if (calendar.source.name == "NAVER") {
                    http.failWrites = true
                    click("변경사항 저장"); await("캘린더 서버 오류 (503). 다시 시도해 주세요.")
                    await(title)
                    check(runBlocking { repository.load(from, to, false) }.events == before.events) { "Failed edit changed cache." }
                    http.failWrites = false
                } else click("하루 종일")
                val began = SystemClock.uptimeMillis()
                click("변경사항 저장"); await("수정 창 닫힘")
                check(SystemClock.uptimeMillis() - began < 2500) { "Edit waited for background REPORT." }
                check(http.releaseReports.count == 1L)
                val after = runBlocking { repository.load(from, to, false) }
                check(after.events.size == before.events.size) { "Edit created a duplicate event." }
                val edited = after.events.single { it.title == title }
                check(edited.calendarId == existing.calendarId)
                check(edited.location == "수정한 장소" && edited.description == "수정한 메모")
                if (calendar.source.name == "NAVER") {
                    check(edited.href == existing.href && http.lastPutMatch == existing.etag)
                    fun uid(raw: String) = biweekly.Biweekly.parse(raw).first().events.single().uid.value
                    check(uid(edited.rawIcs) == uid(existing.rawIcs))
                    check(edited.startMillis == existing.startMillis && edited.endMillis == existing.endMillis)
                } else {
                    check(edited.id.substringBefore('@') == existing.id.substringBefore('@'))
                    check(edited.allDay && edited.startMillis == from && edited.endMillis == to)
                }
                pass("${calendar.source} editing preserves identity/count/account and fields; cancel, invalid input and retry preserve data; update finishes during blocked REPORT")
            }
            editThroughEditor(naverCalendar, "NaverSavedDuringSync")
            editThroughEditor(googleCalendar, "GoogleSavedDuringSync")
            context.contentResolver.delete(fixtureCalendar!!, null, null)
            fixtureCalendar = null
            pass("real Naver and Google-provider event editors save and close within 2.5 seconds while REPORT stays blocked")
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            http.releaseReports.countDown()
            runBlocking { withTimeout(15_000) { checkNotNull(sync).await() } }
            runBlocking {
                val afterStale = repository.load(from, to, false)
                check(afterStale.events.any { it.title == "NaverEditedDuringSync" })
                check(repository.initialNaverSyncPending()) { "Stale REPORT was published after the save." }
                val ready = repository.load(from, to, initialOnly = true)
                check(ready.events.any { it.title == "LoginFixtureEvent" })
                check(ready.tasks.any { it.title == "LoginFixtureTask" })
                check(!ready.initialNaverSync && !repository.initialNaverSyncPending())
                val reports = http.reports.get()
                repository.load(from, to, initialOnly = true)
                check(http.reports.get() == reports) { "Completed initial sync fetched twice." }
                pass("first sync finishes after leaving the activity; events/tasks publish together; completed initial job skips duplicate fetch")

                val previousTasks = prefs.getString("remote_tasks", null)
                val bodiesBefore = http.bodyQueries.get()
                http.failTasks = true
                val taskFailure = repository.load(from, to)
                check(taskFailure.events.any { it.title == "LoginFixtureEvent" })
                check(prefs.getString("remote_tasks", null) == previousTasks)
                check(taskFailure.taskNotice.contains("할 일 조회를 완료하지 못했어요."))
                http.failTasks = false
                val refreshed = repository.load(from, to)
                check(refreshed.taskNotice.contains("할 일 1개"))
                check(http.bodyQueries.get() == bodiesBefore) { "Warm sync downloaded unchanged bodies." }
                pass("warm repository sync requests only versions; task-only failure retains tasks and events, and retry recovers")

                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                runner.waitForIdleSync()
                runner.runOnMainSync {
                    activity!!.setContent { MoaTheme {
                        val running by CalendarRepository.naverSyncing.collectAsState()
                        SyncRefreshAction(running) {}
                    } }
                }
                await("일정 새로고침")
                for (outcome in listOf("success", "failure", "cancel")) {
                    http.reportEntered = CountDownLatch(1); http.releaseReports = CountDownLatch(1)
                    http.failReports = outcome == "failure"
                    val background = scope.async { repository.load(from, to) }
                    check(http.reportEntered.await(10, TimeUnit.SECONDS))
                    await("동기화 중")
                    check(CalendarRepository.naverSyncing.value)
                    if (outcome == "cancel") background.cancel()
                    http.releaseReports.countDown()
                    if (outcome == "cancel") background.join() else background.await()
                    check(!CalendarRepository.naverSyncing.value)
                    await("일정 새로고침")
                    check(repository.load(from, to, false).events.any { it.title == "NaverEditedDuringSync" })
                    http.failReports = false
                }
                pass("background sync spinner starts each time and stops after success, failure and cancellation while edited events remain cached")

                val remote = prefs.getString("remote", null)
                val tasks = prefs.getString("remote_tasks", null)
                val reportsBeforeReconnect = http.reports.get()
                repository.connectNaver("fixture-user", "new-fixture-password", FixtureHttp.SERVER)
                check(http.reports.get() == reportsBeforeReconnect) { "Reauthentication downloaded events." }
                check(prefs.getString("remote", null) == remote && prefs.getString("remote_tasks", null) == tasks)
                check(repository.initialNaverSyncPending())
                pass("same-account reconnect verifies access without REPORT and retains existing events/tasks")

                http.failAuthentication = true
                check(runCatching { repository.connectNaver("other-user", "wrong", FixtureHttp.SERVER) }.isFailure)
                check(repository.naverAccount() == "fixture-user")
                check(prefs.getString("remote", null) == remote)
                http.failAuthentication = false
                http.failReports = true
                val failed = repository.load(from, to, initialOnly = true)
                check(failed.errors.isNotEmpty() && failed.events.any { it.title == "LoginFixtureEvent" })
                check(repository.naverConnected() && repository.initialNaverSyncPending())
                http.failReports = false
                val recovered = repository.load(from, to, initialOnly = true)
                check(recovered.errors.isEmpty() && !repository.initialNaverSyncPending())
                pass("authentication failure preserves the account; background fetch failure preserves data and pending retry; retry recovers")

                repository.selectGoogleAccount("fixture-google@example.test")
                repository.connectNaver("other-user", "fixture-password", FixtureHttp.SERVER)
                check(repository.naverAccount() == "other-user")
                val switched = repository.load(from, to, false)
                check(switched.events.isEmpty() && switched.tasks.isEmpty())
                check(switched.calendars.filter { it.source.name == "NAVER" }.all { it.account == "other-user" })
                check(repository.selectedGoogleAccount() == "fixture-google@example.test")
                pass("switching Naver account removes only the old Naver cache and preserves Google selection")

                val current = repository.load(from, to, initialOnly = true)
                val created = current.events.single { it.title == "NaverEditedDuringSync" }
                http.reportEntered = CountDownLatch(1); http.releaseReports = CountDownLatch(1)
                val beforeDelete = scope.async { repository.load(from, to) }
                check(http.reportEntered.await(10, TimeUnit.SECONDS))
                withTimeout(2500) { scope.async { repository.delete(created) }.await() }
                http.releaseReports.countDown(); beforeDelete.await()
                check(repository.load(from, to, false).events.none { it.id == created.id })
                pass("delete proceeds during REPORT; an older response cannot resurrect the deleted event")

                http.reportEntered = CountDownLatch(1); http.releaseReports = CountDownLatch(1)
                http.failReports = true
                val beforeDisconnect = scope.async { repository.load(from, to) }
                check(http.reportEntered.await(10, TimeUnit.SECONDS))
                withTimeout(2500) { scope.async { repository.disconnectNaver() }.await() }
                http.releaseReports.countDown(); beforeDisconnect.await()
                check(!repository.naverConnected() && !prefs.contains("remote") && !prefs.contains("last_sync_error"))
                http.failReports = false
                repository.connectNaver("other-user", "fixture-password", FixtureHttp.SERVER)
                pass("disconnect proceeds during REPORT; stale results and errors cannot restore the old account")

                // Register while holding the production sync lock, inspect then cancel before any network can run.
                val mutex = CalendarRepository::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(null) as kotlinx.coroutines.sync.Mutex
                mutex.lock()
                try {
                    CalendarSyncJob.scheduleInitial(context, from, to)
                    val job = checkNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(CalendarSyncJob.INITIAL_JOB))
                    check(job.isPersisted && job.extras.getLong("from") == from && job.extras.getLong("to") == to)
                    context.getSystemService(JobScheduler::class.java).cancel(CalendarSyncJob.INITIAL_JOB)
                    delay(200)
                } finally { mutex.unlock() }
                pass("initial job registration is persisted and retains the requested calendar range")
            }
            result.putString("stream", "PASS: login lifecycle regression completed.\n")
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}\n")
            result.putString("failure", e.javaClass.simpleName)
        } finally {
            http.releaseReports.countDown()
            fixtureCalendar?.let { context.contentResolver.delete(it, null, null) }
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            activity?.let { runner.runOnMainSync { it.finish() } }
            if (safeToRestore) {
                context.getSystemService(JobScheduler::class.java).cancel(CalendarSyncJob.INITIAL_JOB)
                runBlocking { repository.disconnectNaver() }
                val editor = prefs.edit().clear()
                original.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                check(editor.commit())
                CalendarSyncJob.schedule(context)
            }
        }
        runner.finish(if (result.containsKey("failure")) 0 else Activity.RESULT_OK, result)
    }

    private fun pass(text: String) = runner.sendStatus(1, Bundle().apply { putString("stream", "PASS: $text.\n") })
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) runner.uiAutomation.clearCache()
        return runner.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do { find(root(), text)?.let { return it }; SystemClock.sleep(100) } while (SystemClock.uptimeMillis() < deadline)
        error("Missing UI: $text")
    }
    private fun click(text: String) {
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            await(text)
            fun target(candidate: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (candidate == null) return null
                if (candidate.text?.toString() == text || candidate.contentDescription?.toString() == text) {
                    var action: AccessibilityNodeInfo? = candidate
                    while (action != null && !action.isClickable) action = action.parent
                    if (action?.isEnabled == true) return action
                }
                for (i in 0 until candidate.childCount) target(candidate.getChild(i))?.let { return it }
                return null
            }
            if (target(root())?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Cannot click $text")
    }
    private fun setField(label: String, value: String) {
        var node: AccessibilityNodeInfo? = await(label)
        while (node != null && !node.isEditable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }) == true) { "Cannot edit $label" }
    }
}

private class FixtureHttp : Interceptor {
    companion object { const val SERVER = "https://fixture.example.test/calendar/" }
    @Volatile var blockReports = false
    @Volatile var failAuthentication = false
    @Volatile var failReports = false
    @Volatile var failWrites = false
    @Volatile var failTasks = false
    val bodyQueries = AtomicInteger()
    @Volatile var lastPutMatch: String? = null
    val reports = AtomicInteger()
    private val saved = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var reportEntered = CountDownLatch(1)
    @Volatile var releaseReports = CountDownLatch(1)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val code: Int
        val body: String
        if (request.method == "PROPFIND") {
            code = if (failAuthentication) 401 else 207
            body = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/calendar/</d:href><d:propstat><d:prop><d:resourcetype><c:calendar/></d:resourcetype><d:displayname>Login fixture</d:displayname><d:current-user-privilege-set><d:privilege><d:write/></d:privilege></d:current-user-privilege-set><c:supported-calendar-component-set><c:comp name="VEVENT"/><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        } else if (request.method == "PUT") {
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            lastPutMatch = request.header("If-Match")
            if (!failWrites) saved[request.url.encodedPath] = buffer.readUtf8()
            code = if (failWrites) 503 else 201; body = ""
        } else if (request.method == "GET") {
            val data = saved[request.url.encodedPath] ?: when (request.url.encodedPath) {
                "/calendar/event.ics" -> baseIcs(false)
                "/calendar/task.ics" -> baseIcs(true)
                else -> null
            }
            bodyQueries.incrementAndGet()
            code = if (data == null) 404 else 200; body = data.orEmpty()
        } else if (request.method == "DELETE") {
            saved.remove(request.url.encodedPath)
            code = 204; body = ""
        } else {
            check(request.method == "REPORT")
            val prior = saved.toMap()
            reports.incrementAndGet()
            reportEntered.countDown()
            if (blockReports) check(releaseReports.await(60, TimeUnit.SECONDS)) { "Fixture REPORT timed out." }
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val query = buffer.readUtf8()
            val task = query.contains("VTODO")
            val includeData = query.contains("calendar-data")
            if (includeData) bodyQueries.incrementAndGet()
            val ics = baseIcs(task)
            code = if (failReports || (task && failTasks)) 503 else 207
            val entries = (mapOf("/calendar/${if (task) "task" else "event"}.ics" to ics) + if (task) emptyMap() else prior).entries.joinToString("") { (href, data) ->
                """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:getetag>"fixture"</d:getetag>${if (includeData) "<c:calendar-data><![CDATA[$data]]></c:calendar-data>" else "<d:resourcetype/>"}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
            }
            body = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">$entries</d:multistatus>"""
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").header("ETag", "\"fixture\"")
            .body(body.toResponseBody("application/xml".toMediaType())).build()
    }
    private fun baseIcs(task: Boolean): String {
        val date = LocalDate.now().toString().replace("-", "")
        return if (task) "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:task\r\nSUMMARY:LoginFixtureTask\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
            else "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:event\r\nDTSTART;VALUE=DATE:$date\r\nSUMMARY:LoginFixtureEvent\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    }

}
