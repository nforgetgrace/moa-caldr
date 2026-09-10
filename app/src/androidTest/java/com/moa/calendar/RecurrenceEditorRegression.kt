package com.moa.calendar

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.moa.calendar.data.*
import com.moa.calendar.ui.EventEditor
import com.moa.calendar.ui.MoaTheme
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/** Only synthetic editor data: never writes calendars or contacts a cloud service. */
internal class RecurrenceEditorRegression(private val runner: Instrumentation) {
    private val context = runner.targetContext
    private var activity: MainActivity? = null
    private val saved = AtomicReference<EventDraft?>(null)
    private var editorSession = 0
    private val calendar = CalendarInfo("fixture", "개인 일정", "fixture@example.test", CalendarSource.NAVER, 0xFFB39DDB.toInt())
    private val date = LocalDate.of(2026, 9, 11)
    private val originalStart = date.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    fun run() {
        val result = Bundle()
        try {
            check(!CalendarRepository(context).naverConnected()) { "Use an isolated emulator." }
            activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            RepeatFrequency.entries.forEach { frequency ->
                show(null)
                await("새로운 일정")
                enterTitle("반복 일정 테스트")
                if (frequency != RepeatFrequency.NONE) {
                    click("반복 설정")
                    await("일정 반복")
                    if (frequency == RepeatFrequency.WEEKLY) screenshot("recurrence-options.png")
                    click(frequency.label)
                }
                click("일정 저장")
                waitUntil { saved.get() != null }
                check(saved.get()!!.recurrenceRule == frequency.rule) { "Wrong new recurrence: $frequency" }
            }
            val event = CalendarEvent("series@later", calendar.id, "매주 팀 미팅", originalStart + 7 * DAY,
                originalStart + 7 * DAY + HOUR, source = CalendarSource.NAVER, color = calendar.color,
                recurring = true, recurrenceRule = "FREQ=WEEKLY", seriesStartMillis = originalStart,
                seriesEndMillis = originalStart + HOUR, timeZone = "UTC")
            show(event)
            await("전체 반복 일정에 적용돼요 · 첫 일정의 날짜를 표시합니다")
            await("2026. 9. 11")
            screenshot("recurrence-series-editor.png")
            click("변경사항 저장")
            waitUntil { saved.get() != null }
            check(saved.get()!!.startMillis == originalStart) { "Later occurrence rebased the series." }
            check(saved.get()!!.endMillis == originalStart + HOUR)
            check(saved.get()!!.recurrenceRule == "FREQ=WEEKLY")
            check(saved.get()!!.timeZone == "UTC")
            show(event)
            click("반복 설정")
            click("반복 없음")
            click("변경사항 저장")
            waitUntil { saved.get() != null }
            check(saved.get()!!.recurrenceRule.isEmpty())
            check(saved.get()!!.startMillis == originalStart)
            show(event.copy(recurrenceRule = "FREQ=WEEKLY;COUNT=8;BYDAY=FR"))
            click("반복 설정")
            await("기존 반복 유지")
            click("닫기")
            click("변경사항 저장")
            waitUntil { saved.get() != null }
            check(saved.get()!!.recurrenceRule == "FREQ=WEEKLY;COUNT=8;BYDAY=FR")
            show(event.copy(recurrenceRule = "FREQ=WEEKLY;COUNT=8;BYDAY=FR"))
            click("반복 설정")
            click("매주")
            await("기존 반복 설정을 바꿀까요?")
            check(saved.get() == null)
            click("유지하기")
            await("기존 반복 유지")
            click("닫기")
            click("변경사항 저장")
            waitUntil { saved.get() != null }
            check(saved.get()!!.recurrenceRule == "FREQ=WEEKLY;COUNT=8;BYDAY=FR")
            show(event.copy(recurrenceRule = "FREQ=WEEKLY;COUNT=8;BYDAY=FR"))
            click("반복 설정")
            click("매주")
            await("기존 반복 설정을 바꿀까요?")
            click("반복 변경")
            click("변경사항 저장")
            waitUntil { saved.get() != null }
            check(saved.get()!!.recurrenceRule == "FREQ=WEEKLY")
            show(event.copy(recurrenceReadOnly = true))
            await("일정 상세")
            await("개별 변경·예외가 있거나 원본 반복 정보를 확인할 수 없는 일정은 원본 캘린더에서 수정해 주세요.")
            check(find(root(), "변경사항 저장") == null)
            result.putString("stream", "PASS: Editor selects none/daily/weekly/monthly/yearly, edits the original series dates from a later occurrence, preserves custom rules, and explains exception restrictions.\n")
        } catch (e: Throwable) {
            screenshot("recurrence-editor-failure.png")
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}\n")
            result.putString("failure", e.javaClass.simpleName)
        } finally {
            runner.runOnMainSync { activity?.finish() }
        }
        runner.finish(if (result.containsKey("failure")) 0 else -1, result)
    }

    private fun show(event: CalendarEvent?) {
        saved.set(null)
        val session = ++editorSession
        runner.runOnMainSync {
            activity!!.setContent {
                androidx.compose.runtime.key(session) {
                    MoaTheme {
                        EventEditor(date, listOf(calendar), event, onDismiss = {},
                            onSave = { saved.set(it) }, onDelete = { error("Recurring delete must not be offered.") })
                    }
                }
            }
        }
        runner.waitForIdleSync()
        SystemClock.sleep(500) // Wait for the replaced sheet's entrance animation and accessibility window.
        await(if (event == null) "새로운 일정" else if (event.recurrenceReadOnly) "일정 상세" else "일정 수정")
    }

    private fun enterTitle(text: String) {
        repeat(80) {
            var node: AccessibilityNodeInfo? = find(root(), "일정 제목")
            while (node != null && !node.isEditable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }) == true) return
            SystemClock.sleep(100)
        }
        error("Could not enter title")
    }

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
        repeat(100) { find(root(), text)?.let { return it }; SystemClock.sleep(100) }
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

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; SystemClock.sleep(100) }
        error("Timed out")
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(400)
        runner.uiAutomation.takeScreenshot()?.let { bitmap ->
            context.openFileOutput(name, 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    companion object { const val HOUR = 3_600_000L; const val DAY = 86_400_000L }
}
