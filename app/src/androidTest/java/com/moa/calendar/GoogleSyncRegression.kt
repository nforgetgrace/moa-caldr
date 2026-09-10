package com.moa.calendar

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moa.calendar.data.CalendarRepository
import com.moa.calendar.ui.MoaTheme
import com.moa.calendar.ui.SyncRefreshAction
import com.moa.calendar.ui.rememberGoogleSyncActive
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Isolated-emulator regression for Google sync status reconciliation. */
internal class GoogleSyncRegression(private val runner: Instrumentation) {
    private val context = runner.targetContext
    private var activity: MainActivity? = null

    fun run() {
        val result = Bundle()
        try {
            check(!CalendarRepository(context).naverConnected()) { "Use an isolated emulator without Naver credentials." }
            activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val reads = AtomicInteger(0)
            val activeByAccount = AtomicReference(mapOf("first@example.test" to true, "second@example.test" to false))
            val slowResult = AtomicReference<Boolean?>(null)
            val slowReadsStarted = AtomicInteger(0)
            var pausedReads = 0
            runner.runOnMainSync {
                activity!!.setContent {
                    MoaTheme {
                        var resumed by androidx.compose.runtime.remember { mutableStateOf(true) }
                        var revision by androidx.compose.runtime.remember { mutableIntStateOf(0) }
                        val accounts = androidx.compose.runtime.remember { mutableStateListOf("first@example.test") }
                        val syncing = rememberGoogleSyncActive(resumed, accounts, revision) { names ->
                            reads.incrementAndGet()
                            slowResult.getAndSet(null)?.let { result ->
                                slowReadsStarted.incrementAndGet()
                                delay(1_500)
                                return@rememberGoogleSyncActive result
                            }
                            names.any { activeByAccount.get()[it] == true }
                        }
                        GoogleSyncHarness(
                            syncing = syncing,
                            account = accounts.joinToString(),
                            setActive = { account, active -> activeByAccount.updateAndGet { it + (account to active) } },
                            switchAccount = {
                                accounts.clear()
                                accounts.add("second@example.test")
                                activeByAccount.updateAndGet { it + ("first@example.test" to true) + ("second@example.test" to false) }
                            },
                            clearAccounts = { accounts.clear() },
                            markSecondActive = { activeByAccount.updateAndGet { it + ("second@example.test" to true) } },
                            slowActive = { slowResult.set(true); revision++ },
                            bumpRevision = { revision++ },
                            pause = { resumed = false; pausedReads = reads.get() },
                            resume = { resumed = true; revision++ },
                        )
                    }
                }
            }
            await("캐시 라벨")
            await("동기화 중")
            val activeStartReads = reads.get()
            SystemClock.sleep(2_300)
            await("동기화 중")
            check(reads.get() >= activeStartReads + 1) { "Active Google sync was completed without another status poll." }
            click("first inactive")
            await("일정 새로고침")
            click("first active")
            await("동기화 중")
            await("캐시 라벨")
            click("slow active")
            waitUntil { slowReadsStarted.get() > 0 }
            click("switch account")
            await("일정 새로고침")
            SystemClock.sleep(1_800)
            await("일정 새로고침")
            check(reads.get() >= 1) { "Google status reader did not run." }
            click("switch account")
            await("일정 새로고침")
            click("second active")
            await("동기화 중")
            click("empty accounts")
            await("일정 새로고침")
            val emptyReads = reads.get()
            SystemClock.sleep(2_500)
            check(reads.get() == emptyReads) { "Google status kept polling without accounts." }
            click("switch account")
            click("second active")
            await("동기화 중")
            click("pause")
            await("일정 새로고침")
            val afterPause = pausedReads
            SystemClock.sleep(2_500)
            check(reads.get() == afterPause) { "Google status kept polling while paused." }
            click("resume")
            await("동기화 중")
            click("bump revision")
            await("동기화 중")
            result.putString("stream", "PASS: Google sync status polls missed active/inactive transitions, switches accounts, stops while paused and keeps cached UI visible.\n")
        } catch (e: Throwable) {
            uiScreenshot()
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            result.putString("failure", e.javaClass.simpleName)
        } finally {
            runner.runOnMainSync { activity?.finish() }
        }
        runner.finish(if (result.containsKey("failure")) 0 else -1, result)
    }

    @androidx.compose.runtime.Composable
    private fun GoogleSyncHarness(
        syncing: Boolean,
        account: String,
        setActive: (String, Boolean) -> Unit,
        switchAccount: () -> Unit,
        clearAccounts: () -> Unit,
        markSecondActive: () -> Unit,
        slowActive: () -> Unit,
        bumpRevision: () -> Unit,
        pause: () -> Unit,
        resume: () -> Unit,
    ) {
        Column {
            Text("캐시 라벨")
            Text("account $account")
            SyncRefreshAction(syncing) {}
            Text("first inactive", modifier = androidx.compose.ui.Modifier.clickable { setActive("first@example.test", false) })
            Text("first active", modifier = androidx.compose.ui.Modifier.clickable { setActive("first@example.test", true) })
            Text("switch account", modifier = androidx.compose.ui.Modifier.clickable { switchAccount() })
            Text("empty accounts", modifier = androidx.compose.ui.Modifier.clickable { clearAccounts() })
            Text("second active", modifier = androidx.compose.ui.Modifier.clickable { markSecondActive() })
            Text("slow active", modifier = androidx.compose.ui.Modifier.clickable { slowActive() })
            Text("pause", modifier = androidx.compose.ui.Modifier.clickable { pause() })
            Text("resume", modifier = androidx.compose.ui.Modifier.clickable { resume() })
            Text("bump revision", modifier = androidx.compose.ui.Modifier.clickable { bumpRevision() })
        }
    }

    private fun currentRoot(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) runner.uiAutomation.clearCache()
        return runner.uiAutomation.rootInActiveWindow
    }

    private fun await(text: String): AccessibilityNodeInfo {
        repeat(80) {
            find(currentRoot(), text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing UI node: $text")
    }

    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = await(text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Could not click: $text" }
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(50) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Timed out waiting for condition.")
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun uiScreenshot() {
        runner.uiAutomation.takeScreenshot()?.let { bitmap ->
            context.openFileOutput("google-sync-regression-failure.png", 0).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun <T> AtomicReference<Map<String, T>>.update(transform: (Map<String, T>) -> Map<String, T>) {
        while (true) {
            val before = get()
            if (compareAndSet(before, transform(before))) return
        }
    }
}
