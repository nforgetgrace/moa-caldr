package com.moa.calendar

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.moa.calendar.data.CalendarRepository
import com.moa.calendar.widget.WidgetUpdater
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

/** Run on an isolated emulator: exercises the production repository and SharedPreferences. */
class RefreshRegressionRunner : Instrumentation() {
    private var scenario: String? = null
    override fun onCreate(arguments: Bundle?) {
        scenario = arguments?.getString("scenario")
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val prefs = targetContext.getSharedPreferences("moa_calendar", 0)
        val original = prefs.all
        val mutex = CalendarRepository::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(null) as Mutex
        val owner = Any()
        var acquired = false
        try {
            check(!targetContext.getSharedPreferences("moa_vault", 0).contains("encrypted")) { "Use an emulator without a connected Naver account." }
            val today = LocalDate.now()
            val from = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val date = today.toString().replace("-", "")
            val id = "https://fixture.example.test/calendar/"
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:retained\r\nDTSTART;VALUE=DATE:$date\r\nSUMMARY:RefreshRetentionFixture\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            val remote = JSONObject().put("calendars", JSONArray().put(JSONObject()
                .put("id", id).put("name", "Refresh fixture").put("account", "fixture-user").put("color", -16734005)))
                .put("resources", JSONObject().put(id, JSONArray().put(JSONObject().put("href", "${id}one.ics").put("ics", ics))))
            prefs.edit().putString("remote", remote.toString()).commit()
            val repository = CalendarRepository(targetContext)
            fun openApp(): Activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            var retainedActivity: Activity? = null
            if (scenario == "manual" || scenario == "widget") {
                retainedActivity = openApp()
                awaitNode("RefreshRetentionFixture")
                SystemClock.sleep(500)
                if (scenario == "widget") {
                    clickNode("설정")
                    goHome()
                }
            }
            runBlocking {
                check(repository.load(from, from + 86_400_000, false).events.any { it.title == "RefreshRetentionFixture" })
                // Hold the same write lock used for the entire remote request, as a slow sync would.
                check(mutex.tryLock(owner))
                acquired = true
                val duringSync = withTimeout(1500) { repository.load(from, from + 86_400_000, false) }
                check(duringSync.calendars.any { it.id == id })
                check(duringSync.events.any { it.title == "RefreshRetentionFixture" })
                check(duringSync.calendars.first { it.id == id }.color == 0xFFB39DDB.toInt())
                withTimeout(1500) { WidgetUpdater.update(targetContext) }
                if (scenario != null) {
                    if (scenario == "background") openApp()
                    if (scenario == "widget") {
                        clickWidgetDate(today)
                        awaitNode("나의 캘린더")
                        awaitNode("${today.monthValue}월 ${today.dayOfMonth}일 일정")
                        check(retainedActivity?.isDestroyed == false) { "Widget click recreated the recent activity." }
                    }
                    awaitNode("RefreshRetentionFixture")
                    if (scenario == "manual") {
                        var refresh: AccessibilityNodeInfo? = awaitNode("일정 새로고침")
                        while (refresh != null && !refresh.isClickable) refresh = refresh.parent
                        check(refresh?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                        awaitNode("수동 동기화 중")
                    } else {
                        check(findNode(uiAutomation.rootInActiveWindow, "수동 동기화 중") == null)
                    }
                    check(findNode(uiAutomation.rootInActiveWindow, "RefreshRetentionFixture") != null)
                    sendStatus(1, Bundle().apply { putString("stream", "READY: $scenario sync retains the event; spinner visibility verified.\n") })
                    SystemClock.sleep(20_000) // Bounded screenshot capture window; no production delay.
                }
                mutex.unlock(owner)
                acquired = false
                if (scenario == "widget") {
                    // A second date click must reach the same recent activity and change selection.
                    goHome()
                    clickWidgetDate(today.plusDays(1))
                    awaitNode("나의 캘린더")
                    val tomorrow = today.plusDays(1)
                    awaitNode("${tomorrow.monthValue}월 ${tomorrow.dayOfMonth}일 일정")
                    check(retainedActivity?.isDestroyed == false)
                    runOnMainSync { retainedActivity?.finish() }
                    goHome()
                    check(mutex.tryLock(owner))
                    acquired = true
                    clickWidgetDate(today)
                    awaitNode("나의 캘린더")
                    awaitNode("RefreshRetentionFixture")
                    check(findNode(uiAutomation.rootInActiveWindow, "수동 동기화 중") == null)
                    mutex.unlock(owner)
                    acquired = false
                    sendStatus(1, Bundle().apply { putString("stream", "PASS: real home-widget date clicks reuse the recent activity, change the selected date and retain cache after closing/reopening during sync.\n") })
                }
                if (scenario == "manual") {
                    awaitNode("일정 새로고침")
                    targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    SystemClock.sleep(500)
                    check(mutex.tryLock(owner))
                    acquired = true
                    targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    awaitNode("RefreshRetentionFixture")
                    SystemClock.sleep(500)
                    check(findNode(uiAutomation.rootInActiveWindow, "수동 동기화 중") == null)
                    mutex.unlock(owner)
                    acquired = false
                    sendStatus(1, Bundle().apply { putString("stream", "PASS: returning to the app after manual refresh starts a silent refresh.\n") })
                }
                // A genuinely empty successful result must still remove deleted events.
                remote.getJSONObject("resources").put(id, JSONArray())
                prefs.edit().putString("remote", remote.toString()).commit()
                check(repository.load(from, from + 86_400_000, false).events.none { it.calendarId == id })
            }
            result.putString("stream", "PASS: cached calendars/events and widget updates stay available during a held sync lock; confirmed empty cache removes deleted events.\n")
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            result.putString("failure", e.javaClass.simpleName)
        } finally {
            if (acquired) mutex.unlock(owner)
            val restore = prefs.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Long -> restore.putLong(key, value)
                is Int -> restore.putInt(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Float -> restore.putFloat(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            restore.commit()
        }
        finish(if (result.containsKey("failure")) 0 else -1, result)
    }

    private fun awaitNode(text: String): AccessibilityNodeInfo {
        repeat(60) {
            findNode(uiAutomation.rootInActiveWindow, text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing UI node: $text")
    }

    private fun goHome() {
        targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(500)
    }

    private fun clickNode(text: String) {
        var node: AccessibilityNodeInfo? = awaitNode(text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Could not click: $text" }
    }

    private fun clickWidgetDate(date: LocalDate) {
        val label = "${date.monthValue}월 ${date.dayOfMonth}일 일정"
        repeat(4) {
            if (findNode(uiAutomation.rootInActiveWindow, label) != null) { clickNode(label); return }
            uiAutomation.executeShellCommand("input swipe 950 1000 150 1000 350").close()
            SystemClock.sleep(600)
        }
        error("Place a month widget on the isolated emulator home screen before this test.")
    }

    private fun findNode(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) return node
        for (index in 0 until node.childCount) findNode(node.getChild(index), text)?.let { return it }
        return null
    }
}
