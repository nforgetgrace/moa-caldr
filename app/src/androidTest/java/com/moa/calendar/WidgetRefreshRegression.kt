package com.moa.calendar

import android.app.Instrumentation
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.moa.calendar.data.CalendarRepository
import com.moa.calendar.widget.MonthWidget
import com.moa.calendar.widget.WidgetUpdater
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Isolated-emulator regression: the widget refresh button shows a spinner and then the last sync time. */
internal class WidgetRefreshRegression(private val runner: Instrumentation) {
    private val context = runner.targetContext

    fun run() {
        val result = Bundle()
        val prefs = context.getSharedPreferences("moa_calendar", 0)
        val original = prefs.all
        // Holding the repository's sync lock stalls the refresh job, as a slow server would, so the spinner stays visible long enough to verify.
        val mutex = CalendarRepository::class.java.getDeclaredField("syncLock").apply { isAccessible = true }.get(null) as Mutex
        val owner = Any()
        var held = false
        try {
            check(!CalendarRepository(context).naverConnected()) { "Use an isolated emulator without Naver credentials." }
            check(AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, MonthWidget::class.java)).isNotEmpty()) {
                "Place a month widget on the isolated emulator home screen before this test."
            }
            val today = LocalDate.now()
            val id = "https://fixture.example.test/refresh/"
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:refresh-event\r\nDTSTART;VALUE=DATE:${today.toString().replace("-", "")}\r\nSUMMARY:새로고침 일정\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            prefs.edit()
                .putString("remote", JSONObject().put("calendars", JSONArray().put(JSONObject()
                    .put("id", id).put("name", "새로고침 캘린더").put("account", "fixture").put("color", 0xFFB39DDB.toInt())
                    .put("writable", true).put("events", true).put("tasks", false)))
                    .put("resources", JSONObject().put(id, JSONArray().put(JSONObject().put("href", "${id}one.ics").put("etag", "v1").put("ics", ics)))).toString())
                .putBoolean("tasks_checked", true)
                .remove("widget_refresh_started").remove("widget_last_sync")
                .commit()
            runBlocking { WidgetUpdater.update(context) }
            goHome()
            locateWidget()
            await("일정 새로고침", visible = true)
            check(find(root(), "동기화 중", visible = true) == null) { "Spinner must be hidden before refresh." }

            check(mutex.tryLock(owner)); held = true
            val pressed = System.currentTimeMillis()
            click("일정 새로고침")
            await("동기화 중", visible = true)
            SystemClock.sleep(1500)
            check(find(root(), "동기화 중", visible = true) != null) { "Spinner must stay while the sync job is blocked." }
            screenshot("widget-refreshing.png")
            check(prefs.getLong("widget_refresh_started", 0) >= pressed) { "Refresh start was not recorded." }

            // Releasing the lock lets the job sync, record the time and re-render without the spinner.
            mutex.unlock(owner); held = false
            awaitAbsent("동기화 중")
            val recorded = prefs.getLong("widget_last_sync", 0)
            check(recorded >= pressed) { "Last sync time was not recorded after the refresh: $recorded" }
            check(prefs.getLong("widget_refresh_started", 0) == 0L) { "Refresh flag must be cleared." }
            val expected = "최근 동기화 " + com.moa.calendar.data.widgetSyncTime(recorded, ZoneId.systemDefault())
            await(expected, visible = true)
            check(find(root(), "일정 새로고침", visible = true) != null) { "Refresh button must return after sync." }
            screenshot("widget-last-sync.png")

            // A refresh that never reports back must not leave the spinner forever.
            prefs.edit().putLong("widget_refresh_started", System.currentTimeMillis() - 120_000).commit()
            runBlocking { WidgetUpdater.update(context) }
            awaitAbsent("동기화 중")
            check(find(root(), "일정 새로고침", visible = true) != null)
            result.putString("stream", "PASS: Widget refresh shows a spinner immediately, the sync job clears it and records the last sync date/time in the status line, and a stale refresh flag expires.\n")
        } catch (e: Throwable) {
            runner.uiAutomation.takeScreenshot()?.let { bitmap ->
                context.openFileOutput("widget-refresh-regression-failure.png", 0).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            result.putString("failure", e.javaClass.simpleName)
        } finally {
            if (held) mutex.unlock(owner)
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
            runBlocking { WidgetUpdater.update(context) }
        }
        runner.finish(if (result.containsKey("failure")) 0 else -1, result)
    }

    private fun goHome() {
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(600)
    }
    private fun locateWidget() {
        // Both widgets have a refresh button; anchor on the month widget's arrows so screenshots show the month grid.
        repeat(4) {
            if (find(root(), "이전 달", visible = true) != null) return
            runner.uiAutomation.executeShellCommand("input swipe 950 1000 150 1000 350").close()
            SystemClock.sleep(600)
        }
        error("Place a month widget on the isolated emulator home screen before this test.")
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(300)
        runner.uiAutomation.takeScreenshot()?.let { bitmap ->
            context.openFileOutput(name, 0).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) runner.uiAutomation.clearCache()
        return runner.uiAutomation.rootInActiveWindow
    }
    private fun await(text: String, partial: Boolean = false, visible: Boolean = false): AccessibilityNodeInfo {
        repeat(150) {
            find(root(), text, partial, visible)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing node: $text")
    }
    private fun awaitAbsent(text: String) {
        repeat(150) {
            if (find(root(), text) == null) return
            SystemClock.sleep(100)
        }
        error("Unexpected stale node: $text")
    }
    private fun click(text: String) {
        repeat(80) {
            var node: AccessibilityNodeInfo? = find(root(), text, visible = true)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            SystemClock.sleep(100)
        }
        error("Could not click: $text")
    }
    private fun find(node: AccessibilityNodeInfo?, text: String, partial: Boolean = false, visible: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        fun matches(value: CharSequence?) = value != null && if (partial) value.contains(text) else value.toString() == text
        if ((matches(node.text) || matches(node.contentDescription)) && (!visible || node.isVisibleToUser)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text, partial, visible)?.let { return it }
        return null
    }
}
