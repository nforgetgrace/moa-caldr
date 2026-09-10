package com.moa.calendar

import android.app.Instrumentation
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.moa.calendar.data.CalendarRepository
import com.moa.calendar.data.WidgetMonthSelection
import com.moa.calendar.widget.MonthWidget
import com.moa.calendar.widget.WidgetUpdater
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/** Isolated-emulator regression: month navigation inside the real home-screen month widget. */
internal class WidgetMonthRegression(private val runner: Instrumentation) {
    private val context = runner.targetContext

    fun run() {
        val result = Bundle()
        val prefs = context.getSharedPreferences("moa_calendar", 0)
        val original = prefs.all
        try {
            check(!CalendarRepository(context).naverConnected()) { "Use an isolated emulator without Naver credentials." }
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, MonthWidget::class.java))
            check(ids.isNotEmpty()) { "Place a month widget on the isolated emulator home screen before this test." }
            val today = LocalDate.now()
            val month = YearMonth.from(today)
            val previous = month.minusMonths(1).atDay(15)
            val next = month.plusMonths(1).atDay(15)
            val id = "https://fixture.example.test/months/"
            fun event(uid: String, date: LocalDate, title: String) =
                "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTART;VALUE=DATE:${date.toString().replace("-", "")}\r\nSUMMARY:$title\r\nEND:VEVENT\r\n"
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + event("m-prev", previous, "지난달 일정") + event("m-today", today, "이번달 일정") +
                event("m-next", next, "다음달 일정") + "END:VCALENDAR\r\n"
            prefs.edit()
                .putString("remote", JSONObject().put("calendars", JSONArray().put(JSONObject()
                    .put("id", id).put("name", "월 이동 캘린더").put("account", "fixture").put("color", 0xFFB39DDB.toInt())
                    .put("writable", true).put("events", true).put("tasks", false)))
                    .put("resources", JSONObject().put(id, JSONArray().put(JSONObject().put("href", "${id}months.ics").put("etag", "v1").put("ics", ics)))).toString())
                .putBoolean("tasks_checked", true)
                .commit()
            ids.forEach { WidgetUpdater.navigate(context, it, com.moa.calendar.widget.BaseCalendarWidget.CURRENT_MONTH) }
            runBlocking { WidgetUpdater.update(context) }
            goHome()
            locateWidget()
            await("${month.monthValue}월")
            await("${today.monthValue}월 ${today.dayOfMonth}일 일정 1개: 이번달 일정", partial = true)

            click("이전 달")
            await("${previous.monthValue}월")
            await("${previous.monthValue}월 15일 일정 1개: 지난달 일정", partial = true)
            check(ids.all { WidgetUpdater.monthSelection(context, it)?.month == month.minusMonths(1) }) { "Previous month was not stored per widget." }
            screenshot("widget-previous-month.png")

            click("이전 달")
            val twoBack = month.minusMonths(2)
            await("${twoBack.monthValue}월")

            click("이번 달로 이동")
            await("${month.monthValue}월")
            await("${today.monthValue}월 ${today.dayOfMonth}일 일정 1개: 이번달 일정", partial = true)
            check(ids.all { WidgetUpdater.monthSelection(context, it) == null }) { "Returning to the current month should clear the stored selection." }

            click("다음 달")
            await("${next.monthValue}월")
            await("${next.monthValue}월 15일 일정 1개: 다음달 일정", partial = true)
            screenshot("widget-next-month.png")

            // A selection made yesterday must not survive the date change: the widget returns to the current month on its next update.
            ids.forEach { prefs.edit().putString("widget_month_$it", WidgetMonthSelection(month.minusMonths(3), today.minusDays(1)).encode()).commit() }
            runBlocking { WidgetUpdater.update(context) }
            await("${month.monthValue}월")
            check(find(root(), "이번 달로 이동") == null) { "Current month must not offer a reset action." }

            // On the current month the title keeps opening the app on today's date.
            click("${month.monthValue}월")
            await("나의 캘린더", partial = true)
            await("${today.monthValue}월 ${today.dayOfMonth}일 일정", partial = true)
            result.putString("stream", "PASS: Month widget arrows move to previous/next months with their events, the title returns to the current month, selections expire after the date changes, and the current-month title still opens the app.\n")
        } catch (e: Throwable) {
            runner.uiAutomation.takeScreenshot()?.let { bitmap ->
                context.openFileOutput("widget-month-regression-failure.png", 0).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            result.putString("failure", e.javaClass.simpleName)
        } finally {
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
        // The launcher exposes every home page in one tree; only accept the widget when it is on the visible page so screenshots show it.
        repeat(4) {
            if (find(root(), "이전 달")?.isVisibleToUser == true) return
            runner.uiAutomation.executeShellCommand("input swipe 950 1000 150 1000 350").close()
            SystemClock.sleep(600)
        }
        error("Place a month widget on the isolated emulator home screen before this test.")
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
    private fun await(text: String, partial: Boolean = false): AccessibilityNodeInfo {
        repeat(100) {
            find(root(), text, partial)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing node: $text")
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
    private fun find(node: AccessibilityNodeInfo?, text: String, partial: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        fun matches(value: CharSequence?) = value != null && if (partial) value.contains(text) else value.toString() == text
        if (matches(node.text) || matches(node.contentDescription)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text, partial)?.let { return it }
        return null
    }
}
