package com.moa.calendar.widget

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.CalendarContract
import com.moa.calendar.data.DeviceCalendars
import android.widget.RemoteViews
import com.moa.calendar.MainActivity
import com.moa.calendar.R
import com.moa.calendar.data.CalendarRepository
import com.moa.calendar.data.CalendarSnapshot
import com.moa.calendar.data.calendarDayPreview
import com.moa.calendar.data.calendarMonthLineCapacity
import kotlin.math.ceil
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.Instant

open class BaseCalendarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { updateAsync(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { updateAsync(context) }
    override fun onEnabled(context: Context) { CalendarSyncJob.schedule(context); updateAsync(context) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH) { CalendarSyncJob.scheduleNow(context); updateAsync(context) }
    }
    private fun updateAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeoutOrNull(8000) { WidgetUpdater.update(context) } }
            catch (_: Exception) { /* Keep the last rendered cache if a device provider is unavailable. */ }
            finally { pending.finish() }
        }
    }
    companion object { const val REFRESH = "com.moa.calendar.REFRESH_WIDGET" }
}

class MonthWidget : BaseCalendarWidget()
class AgendaWidget : BaseCalendarWidget()

object WidgetUpdater {
    suspend fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val monthIds = manager.getAppWidgetIds(ComponentName(context, MonthWidget::class.java))
        val agendaIds = manager.getAppWidgetIds(ComponentName(context, AgendaWidget::class.java))
        if (monthIds.isEmpty() && agendaIds.isEmpty()) return
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val repository = CalendarRepository(context)
        val snapshot = repository.load(today.withDayOfMonth(1).minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(),
            today.plusMonths(2).atStartOfDay(zone).toInstant().toEpochMilli(), false)
        val visible = snapshot.copy(events = snapshot.events.filter { it.calendarId !in repository.hiddenCalendars() })
        monthIds.forEach { manager.updateAppWidget(it, month(context, visible, today, it, manager.getAppWidgetOptions(it))) }
        agendaIds.forEach { manager.updateAppWidget(it, agenda(context, visible, today, it, manager.getAppWidgetOptions(it))) }
    }

    private fun month(context: Context, snapshot: CalendarSnapshot, today: LocalDate, widgetId: Int, options: Bundle): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_month)
        views.setTextViewText(R.id.widget_title, "${today.monthValue}월")
        views.setTextViewText(R.id.widget_status, status(snapshot))
        views.removeAllViews(R.id.widget_days)
        val headings = RemoteViews(context.packageName, R.layout.widget_week_header)
        listOf("일", "월", "화", "수", "목", "금", "토").forEach { heading ->
            val cell = RemoteViews(context.packageName, R.layout.widget_day)
            cell.setTextViewText(R.id.widget_day_text, heading)
            cell.setTextColor(R.id.widget_day_text, Color.rgb(119, 128, 149))
            headings.addView(R.id.widget_week_row, cell)
        }
        views.addView(R.id.widget_days, headings)
        val month = YearMonth.from(today)
        val first = month.atDay(1)
        val offset = first.dayOfWeek.value % 7
        val start = first.minusDays(offset.toLong())
        val weeks = (offset + month.lengthOfMonth() + 6) / 7
        val heightKey = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        val height = options.getInt(heightKey, 300)
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val lineHeight = ceil(14 * fontScale).toInt()
        val fixedHeight = 16 + 40 + 20 + ceil(28 * fontScale).toInt() + 8
        val weekEventCounts = (0 until weeks).map { row ->
            (0 until 7).maxOf { column -> snapshot.events.count { it.occursOn(start.plusDays((row * 7 + column).toLong())) } }
        }
        val capacity = calendarMonthLineCapacity(weekEventCounts, height - fixedHeight, lineHeight)
        repeat(weeks) { row ->
            val week = RemoteViews(context.packageName, R.layout.widget_week)
            repeat(7) { column ->
                val date = start.plusDays((row * 7 + column).toLong())
                val cell = RemoteViews(context.packageName, R.layout.widget_month_day)
                val dayEvents = snapshot.events.filter { it.occursOn(date) }.sortedWith(compareBy<com.moa.calendar.data.CalendarEvent> { !it.allDay }.thenBy { it.startMillis })
                val count = dayEvents.size
                cell.setTextViewText(R.id.widget_day_text, "${date.dayOfMonth}" + if (capacity == 0 && count > 0) "·" else "")
                cell.setContentDescription(R.id.widget_day_root, "${date.monthValue}월 ${date.dayOfMonth}일 일정 ${count}개" + dayEvents.joinToString(prefix = if (count > 0) ": " else "", separator = ", ") { it.title })
                cell.setTextColor(R.id.widget_day_text, when {
                    date == today -> Color.WHITE
                    date.month != today.month -> Color.rgb(160, 167, 182)
                    column == 0 -> Color.rgb(206, 120, 120)
                    column == 6 -> Color.rgb(105, 138, 192)
                    else -> Color.rgb(36, 42, 61)
                })
                if (date == today) cell.setInt(R.id.widget_day_text, "setBackgroundResource", R.drawable.widget_today)
                cell.removeAllViews(R.id.widget_day_events)
                val preview = calendarDayPreview(dayEvents, capacity)
                preview.events.forEach { event ->
                    val entry = RemoteViews(context.packageName, R.layout.widget_month_event)
                    entry.setTextViewText(R.id.widget_day_event, event.title.replace('\n', ' '))
                    entry.setTextColor(R.id.widget_day_event, Color.rgb(36, 42, 61))
                    fun tint(channel: Int) = (channel * .28 + 255 * .72).toInt()
                    entry.setInt(R.id.widget_day_event, "setBackgroundColor", Color.rgb(tint(Color.red(event.color)), tint(Color.green(event.color)), tint(Color.blue(event.color))))
                    if (android.os.Build.VERSION.SDK_INT >= 31) entry.setViewLayoutHeight(R.id.widget_day_event, lineHeight.toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP)
                    cell.addView(R.id.widget_day_events, entry)
                }
                if (capacity > 0 && preview.remaining > 0) {
                    val more = RemoteViews(context.packageName, R.layout.widget_month_event)
                    more.setTextViewText(R.id.widget_day_event, "+${preview.remaining}")
                    more.setTextColor(R.id.widget_day_event, Color.rgb(89, 101, 216))
                    more.setContentDescription(R.id.widget_day_event, "일정 ${preview.remaining}개 더 보기")
                    if (android.os.Build.VERSION.SDK_INT >= 31) more.setViewLayoutHeight(R.id.widget_day_event, lineHeight.toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP)
                    cell.addView(R.id.widget_day_events, more)
                }
                cell.setOnClickPendingIntent(R.id.widget_day_root, open(context, date))
                week.addView(R.id.widget_week_row, cell)
            }
            views.addView(R.id.widget_days, week)
        }
        views.setTextViewText(R.id.widget_footer, "오늘 ${snapshot.events.count { it.occursOn(today) }}개의 일정  →")
        actions(context, views, today, MonthWidget::class.java, widgetId)
        return views
    }

    private fun agenda(context: Context, snapshot: CalendarSnapshot, today: LocalDate, widgetId: Int, options: Bundle): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_agenda)
        views.setTextViewText(R.id.widget_title, "${today.monthValue}월 ${today.dayOfMonth}일")
        views.setTextViewText(R.id.widget_status, status(snapshot))
        views.removeAllViews(R.id.widget_events)
        val heightKey = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        val capacity = ((options.getInt(heightKey, 160) - 105) / 51).coerceIn(1, 5)
        val events = snapshot.events.filter { it.isUpcoming() }.sortedBy { it.startMillis }.take(capacity)
        events.forEach { event ->
            val row = RemoteViews(context.packageName, R.layout.widget_event)
            row.setTextViewText(R.id.widget_event_title, event.title)
            val date = event.date()
            val time = if (event.allDay) "종일" else Instant.ofEpochMilli(event.startMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            row.setTextViewText(R.id.widget_event_time, "${date.monthValue}/${date.dayOfMonth} · $time")
            row.setInt(R.id.widget_event_dot, "setBackgroundColor", event.color)
            row.setOnClickPendingIntent(R.id.widget_event_root, open(context, date))
            views.addView(R.id.widget_events, row)
        }
        views.setTextViewText(R.id.widget_footer, if (events.isEmpty()) "다가오는 일정이 없어요 · 앱 열기  →" else "모든 일정 보기  →")
        actions(context, views, today, AgendaWidget::class.java, widgetId)
        return views
    }

    private fun status(snapshot: CalendarSnapshot): String = when {
        snapshot.errors.isNotEmpty() -> "갱신 확인 필요 · 저장된 일정"
        snapshot.calendars.isEmpty() -> "앱에서 캘린더를 연결해 주세요"
        snapshot.lastSyncMillis > 0 -> "네이버 확인 " + Instant.ofEpochMilli(snapshot.lastSyncMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
        else -> "기기에 저장된 일정"
    }

    private fun open(context: Context, date: LocalDate): PendingIntent = PendingIntent.getActivity(context, date.toEpochDay().toInt(),
        Intent(context, MainActivity::class.java).putExtra("date", date.toString()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun actions(context: Context, views: RemoteViews, today: LocalDate, receiver: Class<*>, id: Int) {
        views.setOnClickPendingIntent(R.id.widget_root, open(context, today))
        views.setOnClickPendingIntent(R.id.widget_footer, open(context, today))
        views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, id,
            Intent(context, receiver).setAction(BaseCalendarWidget.REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
}

class CalendarSyncJob : JobService() {
    private val jobs = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = CoroutineScope(Dispatchers.IO).launch {
            var retry = false
            try {
                if (params.jobId == CONTENT_JOB) {
                    WidgetUpdater.update(applicationContext)
                    return@launch
                }
                val now = LocalDate.now()
                val zone = ZoneId.systemDefault()
                val repository = CalendarRepository(applicationContext)
                val from = params.extras.getLong("from", now.withDayOfMonth(1).minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli())
                val to = params.extras.getLong("to", now.plusMonths(3).atStartOfDay(zone).toInstant().toEpochMilli())
                val result = repository.load(from, to, initialOnly = params.jobId == INITIAL_JOB)
                retry = if (params.jobId == INITIAL_JOB) repository.initialNaverSyncPending() else result.errors.isNotEmpty()
                WidgetUpdater.update(applicationContext)
            } catch (e: Exception) { if (e is CancellationException) throw e; retry = true }
            finally { withContext(NonCancellable + Dispatchers.Main) {
                jobs.remove(params.jobId)
                if (params.jobId == CONTENT_JOB) {
                    // Replace after processing, preserving changes that arrived during this job.
                    // Android stops the old job itself when the same ID is scheduled again.
                    if (!scheduleContentWatch(applicationContext, replace = true)) jobFinished(params, false)
                } else jobFinished(params, retry)
            } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { jobs.values.forEach { it.cancel() }; jobs.clear(); super.onDestroy() }
    companion object {
        const val CONTENT_JOB = 2703
        const val INITIAL_JOB = 2704
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val repository = CalendarRepository(context)
            val job = JobInfo.Builder(2701, ComponentName(context, CalendarSyncJob::class.java))
                .setPeriodic(30 * 60 * 1000L).setPersisted(true)
                .setRequiredNetworkType(if (repository.naverConnected()) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_NONE).build()
            val existing = scheduler.getPendingJob(2701)
            @Suppress("DEPRECATION")
            val changed = existing == null || existing.networkType != job.networkType
            if (changed) scheduler.schedule(job)
            if (repository.initialNaverSyncPending() && scheduler.getPendingJob(INITIAL_JOB) == null) {
                val now = LocalDate.now()
                val zone = ZoneId.systemDefault()
                scheduleInitial(context, now.withDayOfMonth(1).minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    now.plusMonths(3).atStartOfDay(zone).toInstant().toEpochMilli())
            } else if (!repository.naverConnected()) scheduler.cancel(INITIAL_JOB)
            scheduleContentWatch(context)
        }
        fun scheduleInitial(context: Context, from: Long, to: Long) {
            val extras = PersistableBundle().apply { putLong("from", from); putLong("to", to) }
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(INITIAL_JOB, ComponentName(context, CalendarSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setExtras(extras).build())
        }
        fun scheduleContentWatch(context: Context, replace: Boolean = false): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!DeviceCalendars(context).hasReadPermission()) { scheduler.cancel(CONTENT_JOB); return false }
            if (!replace && scheduler.getPendingJob(CONTENT_JOB) != null) return true
            val job = JobInfo.Builder(CONTENT_JOB, ComponentName(context, CalendarSyncJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1000).setTriggerContentMaxDelay(5000).build()
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }
        fun scheduleNow(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(2702, ComponentName(context, CalendarSyncJob::class.java))
                .setOverrideDeadline(0).build())
        }
    }
}

class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        CalendarSyncJob.schedule(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeoutOrNull(8000) { WidgetUpdater.update(context) } }
            catch (_: Exception) { /* Retain the previous widget when the provider is unavailable. */ }
            finally { pending.finish() }
        }
    }
}
