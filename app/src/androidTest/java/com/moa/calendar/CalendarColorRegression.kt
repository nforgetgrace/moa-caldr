package com.moa.calendar

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.moa.calendar.data.CalendarRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import com.moa.calendar.widget.WidgetUpdater

/** Isolated-emulator regression for local per-calendar display colors. */
internal class CalendarColorRegression(private val runner: Instrumentation) {
    private val context = runner.targetContext
    private var activity: MainActivity? = null
    private val lavender = 0xFFB39DDB.toInt()
    private val coral = 0xFFEA8D77.toInt()

    fun run() {
        val result = Bundle()
        val prefs = context.getSharedPreferences("moa_calendar", 0)
        val original = prefs.all
        try {
            check(!CalendarRepository(context).naverConnected()) { "Use an isolated emulator without Naver credentials." }
            val id = "https://fixture.example.test/colors/"
            val date = LocalDate.now()
            val day = date.toString().replace("-", "")
            val from = date.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val to = date.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val event = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:color-event\r\nDTSTART:${day}T010000Z\r\nDTEND:${day}T020000Z\r\nSUMMARY:색상 일정\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            val task = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:color-task\r\nDUE;VALUE=DATE:$day\r\nSUMMARY:색상 할 일\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
            prefs.edit()
                .putString("remote", JSONObject().put("calendars", JSONArray().put(JSONObject()
                    .put("id", id).put("name", "색상 캘린더").put("account", "fixture").put("color", lavender)
                    .put("writable", true).put("events", true).put("tasks", true)))
                    .put("resources", JSONObject().put(id, JSONArray().put(JSONObject().put("href", "${id}event.ics").put("etag", "v1").put("ics", event)))).toString())
                .putString("remote_tasks", JSONObject().put(id, JSONArray().put(JSONObject().put("href", "${id}task.ics").put("etag", "t1").put("ics", task))).toString())
                .putBoolean("tasks_checked", true)
                .commit()

            val repository = CalendarRepository(context)
            val base = runBlocking { repository.load(from, to, false) }
            val calendar = base.calendars.single { it.id == id }
            check(calendar.color == lavender && base.events.single { it.title == "색상 일정" }.color == lavender && base.tasks.single().color == lavender)
            repository.setCalendarDisplayColor(calendar, coral)
            val colored = runBlocking { repository.load(from, to, false) }
            check(colored.calendars.single { it.id == id }.color == coral)
            check(colored.events.single { it.title == "색상 일정" }.color == coral)
            check(colored.tasks.single { it.title == "색상 할 일" }.color == coral)
            repository.setCalendarDisplayColor(colored.calendars.single { it.id == id }, null)
            check(repository.applyDisplayColors(colored).events.single { it.title == "색상 일정" }.color == lavender)

            activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            await("색상 일정")
            click("설정")
            scrollTo("색상 캘린더 색상 변경"); click("색상 캘린더 색상 변경")
            await("캘린더 색상")
            await("기본 색상")
            screenshot("calendar-color-picker.png")
            click("파랑 색상 선택")
            waitUntil { CalendarRepository(context).hasCalendarDisplayColor(calendar) }
            check(runBlocking { CalendarRepository(context).load(from, to, false) }.events.single { it.title == "색상 일정" }.color == 0xFF4285F4.toInt())
            scrollTo("색상 캘린더 색상 변경"); click("색상 캘린더 색상 변경")
            screenshot("calendar-color-selected.png")
            click("색상 선택 닫기")
            click("캘린더")
            await("색상 일정")
            screenshot("calendar-custom-color.png")
            runBlocking { WidgetUpdater.update(context) }
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(600)
            val widgetLabel = "${date.monthValue}월 ${date.dayOfMonth}일 일정"
            fun hasWidget(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                val description = node.contentDescription?.toString().orEmpty()
                if (description.startsWith("$widgetLabel 2개") && description.contains("색상 일정") && description.contains("색상 할 일")) return true
                return (0 until node.childCount).any { hasWidget(node.getChild(it)) }
            }
            repeat(4) {
                if (!hasWidget(root())) {
                    runner.uiAutomation.executeShellCommand("input swipe 950 1000 150 1000 350").close()
                    SystemClock.sleep(600)
                }
            }
            waitUntil { hasWidget(root()) }
            screenshot("widget-custom-color.png")
            runner.runOnMainSync { activity?.finish() }
            activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            await("색상 일정")
            click("설정")
            scrollTo("색상 캘린더 색상 변경"); click("색상 캘린더 색상 변경")
            click("기본 색상 선택")
            waitUntil { !CalendarRepository(context).hasCalendarDisplayColor(calendar) }
            check(runBlocking { CalendarRepository(context).load(from, to, false) }.events.single { it.title == "색상 일정" }.color == lavender)
            result.putString("stream", "PASS: Calendar color overrides persist across activity/repository restart, recolor events/tasks, update the home widget, and reset to defaults through the real settings UI.\n")
        } catch (e: Throwable) {
            runner.uiAutomation.takeScreenshot()?.let { bitmap ->
                context.openFileOutput("calendar-color-regression-failure.png", 0).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            result.putString("failure", e.javaClass.simpleName)
        } finally {
            runner.runOnMainSync { activity?.finish() }
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
        runner.finish(if (result.containsKey("failure")) 0 else -1, result)
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; SystemClock.sleep(100) }
        error("Timed out waiting for color persistence")
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(400)
        runner.uiAutomation.takeScreenshot()?.let { bitmap ->
            context.openFileOutput(name, 0).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) runner.uiAutomation.clearCache()
        return runner.uiAutomation.rootInActiveWindow
    }
    private fun await(text: String): AccessibilityNodeInfo {
        repeat(80) {
            find(root(), text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing node: $text")
    }
    private fun scrollTo(text: String) {
        fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
            return null
        }
        repeat(15) {
            if (find(root(), text) != null) return
            scrollable(root())?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        error("Could not scroll to $text")
    }
    private fun click(text: String) {
        repeat(80) {
            var node: AccessibilityNodeInfo? = find(root(), text)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            SystemClock.sleep(100)
        }
        error("Could not click: $text")
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
        return null
    }
}
