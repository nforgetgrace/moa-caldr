package com.moa.calendar

import android.app.Activity
import android.app.Instrumentation
import android.app.job.JobScheduler
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.moa.calendar.data.CalDavClient
import com.moa.calendar.data.CalendarRepository
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
                        } else Text("로그인 완료")
                    }
                }
            }
            setField("네이버 아이디", "fixture-user")
            setField("비밀번호 또는 앱 비밀번호", "fixture-password")
            click("연결하기")
            check(http.reportEntered.await(10, TimeUnit.SECONDS)) { "Initial sync did not start." }
            await("로그인 완료")
            check(find(root(), "네이버 캘린더 연결") == null) { "Login dialog still open during REPORT." }
            check(repository.initialNaverSyncPending())
            check(prefs.getLong("last_sync", 0) == 0L)
            runBlocking {
                val cached = withTimeout(1500) { repository.load(from, to, false) }
                check(cached.calendars.size == 1 && cached.initialNaverSync)
            }
            pass("verified login closes the real dialog while the first REPORT remains blocked; cached reads stay responsive")
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            http.releaseReports.countDown()
            runBlocking { withTimeout(15_000) { checkNotNull(sync).await() } }
            runBlocking {
                val ready = repository.load(from, to, false)
                check(ready.events.any { it.title == "LoginFixtureEvent" })
                check(ready.tasks.any { it.title == "LoginFixtureTask" })
                check(!ready.initialNaverSync && !repository.initialNaverSyncPending())
                val reports = http.reports.get()
                repository.load(from, to, initialOnly = true)
                check(http.reports.get() == reports) { "Completed initial sync fetched twice." }
                pass("first sync finishes after leaving the activity; events/tasks publish together; completed initial job skips duplicate fetch")

                val remote = prefs.getString("remote", null)
                val tasks = prefs.getString("remote_tasks", null)
                repository.connectNaver("fixture-user", "new-fixture-password", FixtureHttp.SERVER)
                check(http.reports.get() == reports) { "Reauthentication downloaded events." }
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
        var node: AccessibilityNodeInfo? = await(text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
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
    val reports = AtomicInteger()
    val reportEntered = CountDownLatch(1)
    val releaseReports = CountDownLatch(1)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val code: Int
        val body: String
        if (request.method == "PROPFIND") {
            code = if (failAuthentication) 401 else 207
            body = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/calendar/</d:href><d:propstat><d:prop><d:resourcetype><c:calendar/></d:resourcetype><d:displayname>Login fixture</d:displayname><c:supported-calendar-component-set><c:comp name="VEVENT"/><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        } else {
            check(request.method == "REPORT")
            reports.incrementAndGet()
            reportEntered.countDown()
            if (blockReports) check(releaseReports.await(15, TimeUnit.SECONDS)) { "Fixture REPORT timed out." }
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val task = buffer.readUtf8().contains("VTODO")
            val date = LocalDate.now().toString().replace("-", "")
            val ics = if (task) "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:task\r\nSUMMARY:LoginFixtureTask\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
                else "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:event\r\nDTSTART;VALUE=DATE:$date\r\nSUMMARY:LoginFixtureEvent\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            code = if (failReports) 503 else 207
            body = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/calendar/${if (task) "task" else "event"}.ics</d:href><d:propstat><d:prop><d:getetag>"fixture"</d:getetag><c:calendar-data><![CDATA[$ics]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(body.toResponseBody("application/xml".toMediaType())).build()
    }
}
